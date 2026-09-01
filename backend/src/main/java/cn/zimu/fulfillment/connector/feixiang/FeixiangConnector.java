package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ExternalWritePermit;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 飞象 Connector：在线拉单（2026-08-28 起走平台私有 JSON/HTML 接口，不再下载导出 Excel）。
 *
 * <p>链路：cookie 会话登录 → {@code GET /esOrder/index/{page}} 按
 * {@code start_create_time}/{@code end_create_time} 真窗口翻页枚举待发货订单 →
 * 逐单 {@code POST /order/ajaxGetSendBeforePro} 取 JSON 详情 →
 * {@link FeixiangOrderTransform} 转结构化行 →
 * {@link SourceImportService#importStructured} 进应用层用例。
 * Connector 不直写任何业务表。</p>
 *
 * <p><b>本 Connector 的首要职责是「绝不静默丢单」。</b>旧实现下载 Excel 时，我们传的
 * {@code start_time}/{@code end_time} 平台不认，回落成只返回当天下单的订单，任何没在下单
 * 当天被拉到的单永久丢失，而界面上一直显示「成功，0 条新数据」。因此这里的失败判定刻意
 * 偏保守：<b>枚举不到订单时，宁可报错，也不报「成功 0 条」</b>——虚惊一场只花操作员一眼，
 * 报错的「成功」要丢一张真实订单。</p>
 *
 * <p><b>范围</b>：拉取 + 在线回传（{@code POST /order/ajaxSendOrderProduct}）。
 * 回传默认<b>关闭</b>：{@code app.feixiang.shipment.write-mode} 未配置时
 * {@link #capabilities()} 的 onlinePush 为假，企微回填文件人工上传路径原样保留。
 * 文件导入路径（{@code SourceFileParser} 的飞象指纹）不受影响，保持可用。</p>
 */
@Component
public class FeixiangConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(FeixiangConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SourceImportService sourceImportService;
    private final FeixiangPullClient pullClient;
    private final FeixiangOrderTransform transform;
    private final FeixiangShipmentPlanner planner;
    private final FeixiangShipmentGateway shipmentGateway;
    private final FeixiangShipmentAttemptStore attemptStore;
    private final FeixiangShipmentWriteGate writeGate;

    public FeixiangConnector(
            SourceImportService sourceImportService,
            FeixiangPullClient pullClient,
            FeixiangOrderTransform transform,
            FeixiangShipmentPlanner planner,
            FeixiangShipmentGateway shipmentGateway,
            FeixiangShipmentAttemptStore attemptStore,
            FeixiangShipmentWriteGate writeGate) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.transform = transform;
        this.planner = planner;
        this.shipmentGateway = shipmentGateway;
        this.attemptStore = attemptStore;
        this.writeGate = writeGate;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.FEIXIANG;
    }

    /**
     * 能力位。
     *
     * <p><b>onlinePush 由运行时写门闩驱动，不是编译期常量。</b>这一位在本仓里同时是路由开关：
     * {@code SourceReturnWecomScanner} 见到它为真就<b>停止</b>把飞象回填文件投递到企微
     * （今天唯一在生产验证过的回传通道），{@code SourceSyncAutoWorker} 见到它为真就把飞象
     * 纳入自动执行循环。因此 OFF / DRY_RUN 下必须保持为假：演练绝不能顺手停掉在跑的人工路径，
     * 也绝不能让自动 Worker 抢在人工首发之前开火。</p>
     */
    @Override
    public ConnectorCapabilities capabilities() {
        // fileImport/fileExport 保持既有文件模式；onlinePull 置 true；onlinePush 见上方说明
        return new ConnectorCapabilities(true, true, true, writeGate.pushCapable(), false);
    }

    @Override
    protected LoginProbe loginProbe() {
        FeixiangPullClient.LoginResult login = pullClient.login();
        return new LoginProbe(login.ok(), login.businessCode(), login.message());
    }

    @Override
    public PullResult pullOrders(PullCursor cursor) {
        SourceChannel channel = channel();
        try {
            FeixiangPullClient.LoginResult login = pullClient.login();
            if (!login.ok()) {
                return failed(channel, login.businessCode(), login.message());
            }
            LocalDate begin = beginDate(cursor);
            LocalDate end = endDate(cursor);
            FeixiangPullClient.PendingOrderList listed =
                    pullClient.listPendingOrders(begin.format(DAY), end.format(DAY));

            PullResult emptyVerdict = verdictForEmptyList(channel, listed, begin, end);
            if (emptyVerdict != null) {
                return emptyVerdict;
            }
            logCollectionGaps(listed, begin, end);

            DetailFetch fetched = fetchDetails(listed.orderSonIds());
            List<StructuredOrderRow> rows = transform.toRows(fetched.details());
            if (rows.isEmpty()) {
                return failed(
                        channel,
                        "FEIXIANG_ORDER_DETAIL_UNAVAILABLE",
                        "枚举到 " + listed.orderSonIds().size() + " 单待发货订单，但没有一单取到可用详情，未导入任何数据");
            }

            String batchNo = "PULL-FEIXIANG-" + LocalDateTime.now(SHANGHAI).format(BATCH_NO);
            Map<String, Object> batch = sourceImportService.importStructured(
                    channel, rows, batchNo, commandContext());
            int accepted = acceptedCount(batch);
            String message = buildMessage(begin, end, listed, fetched, rows.size(), batch, accepted);
            log.info("飞象拉取完成: window={}~{}, listed={}, details={}, rows={}, accepted={}, batchNo={}",
                    begin, end, listed.orderSonIds().size(), fetched.details().size(),
                    rows.size(), accepted, batch.get("batch_no"));
            return success(channel, accepted, message, batch);
        } catch (BusinessException exception) {
            if (DUPLICATE_CODES.contains(exception.getBusinessCode())) {
                // importStructured 内部已按单预检测跳过重复订单（不整批回滚，见 ea8fbb2）；
                // 这里只兜底 importStructured 之外抛出的重复码。
                log.info("飞象拉取命中重复订单（{}），按无新数据处理", exception.getBusinessCode());
                return success(channel, 0, "已拉取但订单已存在（重复拉取防护），无新数据导入");
            }
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (FeixiangPullClient.PullTransportException exception) {
            log.warn("飞象拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        }
    }

    /**
     * 枚举结果为空时的判定——本 Connector 最关键的一段。
     *
     * <p>返回 null 表示「有订单，继续走」；否则返回终态。三种情况区别对待：
     * <ul>
     *   <li>平台自报 0 单 → 真的没单，OK；</li>
     *   <li>平台自报 &gt;0 单 → 列表页解析必然失效（HTML 结构变了或选择器本就没对），
     *       <b>报错</b>，绝不报「成功 0 条」；</li>
     *   <li>平台计数不可用 → 无法区分「没单」与「解析失效」。同样<b>报错</b>：
     *       本票修的就是「一边丢单一边报成功」，不确定时必须让人看见。</li>
     * </ul>
     */
    private PullResult verdictForEmptyList(
            SourceChannel channel,
            FeixiangPullClient.PendingOrderList listed,
            LocalDate begin,
            LocalDate end) {
        if (!listed.orderSonIds().isEmpty()) {
            return null;
        }
        int reported = listed.platformReportedCount();
        if (reported == 0) {
            return success(channel, 0, "飞象 " + begin + " 至 " + end + " 窗口内没有待发货订单（平台计数同为 0）");
        }
        if (reported > 0) {
            // 指纹必须跟着错误一起走：只报「结构不匹配」而不带结构，等于每次排查都要重新抓包。
            // 2026-08-29 生产上就卡在这里——last_error 里除了这句话什么线索都没有。
            String fingerprint = listed.listPageFingerprint();
            log.error(
                    "飞象列表页解析失效: 平台自报 {} 单，实际解析出 0 单（window={}~{}）指纹={}",
                    reported, begin, end, fingerprint);
            return failed(
                    channel,
                    "FEIXIANG_ORDER_LIST_UNPARSEABLE",
                    "飞象平台自报窗口内有 " + reported + " 单，但列表页解析出 0 单——"
                            + "列表页 HTML 结构与解析规则不匹配，已停止并报错，未按「无新数据」处理"
                            + (fingerprint == null ? "" : "；列表页结构指纹：" + fingerprint));
        }
        log.error("飞象列表页解析出 0 单，且平台计数不可用，无法确认是真无单还是解析失效（window={}~{}）", begin, end);
        return failed(
                channel,
                "FEIXIANG_ORDER_LIST_UNVERIFIED",
                "飞象列表页解析出 0 单，且平台订单计数接口不可用，无法确认「窗口内确实没有待发货订单」——"
                        + "为避免重演静默丢单，此处按失败处理");
    }

    /** 翻页截断或采集数少于平台自报数时，显式记录被丢弃的数量，绝不静默截断。 */
    private void logCollectionGaps(
            FeixiangPullClient.PendingOrderList listed, LocalDate begin, LocalDate end) {
        int collected = listed.orderSonIds().size();
        int reported = listed.platformReportedCount();
        if (listed.truncated()) {
            int dropped = listed.droppedCount();
            log.error("飞象列表翻页触顶导致截断: 已采集 {} 单，平台自报 {} 单，被丢弃 {} 单（window={}~{}）",
                    collected,
                    reported < 0 ? "未知" : reported,
                    dropped < 0 ? "未知（平台计数不可用）" : dropped,
                    begin, end);
            return;
        }
        if (reported > collected) {
            log.warn("飞象采集数少于平台自报数: 已采集 {} 单，平台自报 {} 单，差额 {} 单（window={}~{}）；"
                            + "可能是列表页解析漏行或平台计数口径不同，请核对",
                    collected, reported, reported - collected, begin, end);
        }
    }

    /** 逐单取详情；单单失败不阻断其他单，但失败数必须显式带出（不静默丢弃）。 */
    private DetailFetch fetchDetails(List<String> orderSonIds) {
        List<FeixiangOrderDetail> details = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();
        for (String orderSonId : orderSonIds) {
            try {
                FeixiangOrderDetail detail = pullClient.fetchOrderDetail(orderSonId);
                if (detail.receiveInfo() == null) {
                    failedIds.add(orderSonId);
                    log.warn("飞象订单详情缺少 receive_info，本单未入库: order_son_id={}", orderSonId);
                    continue;
                }
                details.add(detail);
            } catch (FeixiangPullClient.PullTransportException exception) {
                // 不回显响应体（可能含收货人 PII）；只记 ID 与原因，其余订单继续。
                failedIds.add(orderSonId);
                log.warn("飞象订单详情拉取失败，本单未入库: order_son_id={}, 原因={}",
                        orderSonId, exception.getMessage());
            }
        }
        return new DetailFetch(List.copyOf(details), List.copyOf(failedIds));
    }

    private String buildMessage(
            LocalDate begin,
            LocalDate end,
            FeixiangPullClient.PendingOrderList listed,
            DetailFetch fetched,
            int rowCount,
            Map<String, Object> batch,
            int accepted) {
        StringBuilder message = new StringBuilder()
                .append("已拉取飞象 ").append(begin).append(" 至 ").append(end)
                .append(" 窗口内待发货订单：枚举 ").append(listed.orderSonIds().size())
                .append(" 单，取到详情 ").append(fetched.details().size())
                .append(" 单，合并为 ").append(rowCount)
                .append(" 张来源订单，导入批次 ").append(batch.get("batch_no"))
                .append("（accepted=").append(accepted).append("）");
        if (!fetched.failedIds().isEmpty()) {
            message.append("；另有 ").append(fetched.failedIds().size())
                    .append(" 单详情拉取失败未入库，请重试或人工核对");
        }
        if (listed.truncated()) {
            int dropped = listed.droppedCount();
            message.append("；列表翻页触顶被丢弃约 ")
                    .append(dropped < 0 ? "未知数量" : dropped + " 单")
                    .append("，请缩小日期窗口后重拉");
        } else if (listed.platformReportedCount() > listed.orderSonIds().size()) {
            // 未触顶却比平台自报数少：多半是列表页解析漏行。只写日志不够——运营在刷新界面上
            // 看到的是 message，必须让差额出现在这里，否则又是一次「看起来成功」的静默丢单。
            message.append("；平台自报 ").append(listed.platformReportedCount())
                    .append(" 单，实际只枚举到 ").append(listed.orderSonIds().size())
                    .append(" 单，差额 ")
                    .append(listed.platformReportedCount() - listed.orderSonIds().size())
                    .append(" 单未取回，请核对列表页解析是否漏行");
        }
        return message.toString();
    }

    // ---------------------------------------------------------------- 在线回传（Phase 2）

    /**
     * 读取平台最新事实；只读，不产生任何远端写效果。
     *
     * <p>{@code addressStatus} 的取值需要说明：飞象<b>没有</b>「地址已变更待确认」这样的
     * 显式接口（聚福宝有）。这里把「详情里收货三要素齐备」当作 CLEAR，把「缺任一要素」
     * 当作 UNKNOWN；真正的地址一致性由 {@code SourceSyncPolicy} 拿内部快照逐字段比对，
     * 不一致即阻断。不编造一个平台并不提供的确认语义。</p>
     */
    @Override
    public SourcePlatformCheckResult checkShipmentResult(SourceShipmentResult result) {
        if (result == null
                || result.channel() != SourceChannel.FEIXIANG
                || isBlank(result.sourceLineRef())) {
            return SourcePlatformCheckResult.unavailable(
                    channel(), "FEIXIANG_SHIPMENT_LINEAGE_REQUIRED", "飞象 Shipment 来源子单血缘不完整");
        }
        FeixiangShipmentPlanner.WritePlan plan;
        try {
            plan = planner.plan(result);
        } catch (RuntimeException exception) {
            return SourcePlatformCheckResult.unavailable(
                    channel(), "FEIXIANG_PLATFORM_CHECK_UNAVAILABLE", "飞象 Shipment 当前事实读取失败");
        }
        if (!plan.available() || "FEIXIANG_WRITE_PAYLOAD_INVALID".equals(plan.businessCode())) {
            return SourcePlatformCheckResult.unavailable(channel(), plan.businessCode(), plan.message());
        }
        boolean receiverComplete = !isBlank(plan.receiverName())
                && !isBlank(plan.receiverPhone())
                && !isBlank(plan.receiverAddress());
        return new SourcePlatformCheckResult(
                true,
                "OK",
                "飞象 Shipment 当前事实已读取",
                plan.platformState(),
                false,
                receiverComplete
                        ? SourcePlatformCheckResult.AddressStatus.CLEAR
                        : SourcePlatformCheckResult.AddressStatus.UNKNOWN,
                plan.receiverName(),
                plan.receiverPhone(),
                plan.receiverAddress(),
                plan.sendableQuantity(),
                plan.carrierMapped() && plan.request() != null,
                plan.effectHash());
    }

    /**
     * dry-run 预览：构造并返回将要发出的完整报文，<b>不发出任何写请求</b>。
     *
     * <p>与真写共用 {@link FeixiangShipmentPlanner}，因此这里给人看的
     * {@code formBody()} 与开火时发出的字符串来自同一段代码。</p>
     */
    public FeixiangShipmentPlanner.WritePlan previewShipmentWrite(SourceShipmentResult result) {
        return planner.plan(result);
    }

    /**
     * 单参重载被<b>主动堵死</b>：没有外部写围栏就意味着绕过人工确认、Shipment CAS 与写租约。
     * MCP、脚本或任何持有 Connector 引用的代码都不得由此发起平台写。
     */
    @Override
    public SourceSyncResult pushShipmentResult(SourceShipmentResult result) {
        return SourceSyncResult.failed(
                "SOURCE_SYNC_EXECUTION_CONTEXT_REQUIRED",
                "飞象在线发货必须通过 Shipment source-sync execute 入口");
    }

    @Override
    public SourceSyncResult pushShipmentResult(SourceShipmentResult result, ExternalWritePermit permit) {
        if (permit == null) {
            return SourceSyncResult.failed(
                    "FEIXIANG_WRITE_PERMIT_REQUIRED", "飞象发货缺少有效外部写许可，未提交平台请求");
        }
        SourceSyncResult invalid = validateShipment(result);
        if (invalid != null) {
            return invalid;
        }
        // 门闩第一道：能力位为假时连 claim 都不做，绝不在注册表里留下一次无意义的占用。
        if (!writeGate.pushCapable()) {
            FeixiangShipmentWriteGate.Decision decision = writeGate.inspectExternalWrite();
            return SourceSyncResult.failed(decision.businessCode(), decision.message());
        }
        FeixiangShipmentAttemptStore.ShipmentAttemptPayload payload =
                new FeixiangShipmentAttemptStore.ShipmentAttemptPayload(
                        result.sourceRef(),
                        result.sourceLineRef(),
                        result.sourceUnitQuantity(),
                        result.carrierOutputValue(),
                        result.firstTrackingNo(),
                        result.expectedPlatformEffectHash());
        FeixiangShipmentAttemptStore.ClaimResult claim;
        try {
            claim = attemptStore.claim(payload);
        } catch (RuntimeException exception) {
            return SourceSyncResult.failed(
                    "FEIXIANG_IDEMPOTENCY_UNAVAILABLE", "飞象发货幂等门禁不可用，未提交外部写请求");
        }
        return switch (claim.decision()) {
            case REPLAY, RECONCILIATION_REQUIRED -> claim.replay() == null
                    ? reconciliationRequired("飞象发货结果需要人工对账", null)
                    : claim.replay();
            case CONFLICT -> SourceSyncResult.failed(
                    "FEIXIANG_IDEMPOTENCY_CONFLICT",
                    "同一飞象子单与运单号已被不同发货请求使用（例如换了物流公司），未提交");
            case IN_PROGRESS -> SourceSyncResult.failed(
                    "FEIXIANG_PUSH_IN_PROGRESS", "同一飞象子单与运单号正在处理，未重复提交");
            case PROCEED -> executeClaimed(result, claim.ownerToken(), permit);
        };
    }

    @Override
    public boolean releaseShipmentIntent(String platformIntentKey) {
        return attemptStore.releaseReconciledNotAccepted(platformIntentKey);
    }

    private SourceSyncResult executeClaimed(
            SourceShipmentResult result, String ownerToken, ExternalWritePermit permit) {
        SourceSyncResult outcome = pushOnce(result, ownerToken, permit);
        String subOrderRef = result.sourceLineRef().trim();
        String trackingNo = result.firstTrackingNo().trim();
        try {
            if (outcome.success()) {
                attemptStore.completeSuccess(subOrderRef, trackingNo, ownerToken, outcome);
            } else if ("RECONCILIATION_REQUIRED".equals(outcome.businessCode())) {
                attemptStore.completeUnknown(subOrderRef, trackingNo, ownerToken, outcome);
            } else {
                attemptStore.release(
                        subOrderRef, trackingNo, ownerToken, outcome.businessCode(), outcome.message());
            }
            return outcome;
        } catch (RuntimeException exception) {
            if (!outcome.success() && !"RECONCILIATION_REQUIRED".equals(outcome.businessCode())) {
                return SourceSyncResult.failed(
                        "FEIXIANG_IDEMPOTENCY_UNAVAILABLE",
                        "飞象发货未成功，且幂等结果未能安全归档；未标记为平台结果未知");
            }
            return reconciliationRequired("飞象外部写结果未能完整归档，需要人工对账", outcome.platformRef());
        }
    }

    /**
     * 一次发货的完整流水线：写前只读核对 → 门闩 → 效果标记 → 不可逆写 → 写后回查。
     *
     * <p><b>「平台受理」不等于成功。</b>飞象的成功响应是 {@code {"status":1,"msg":"","data":[]}}，
     * 不含任何平台单据号，因此没有第二个成功来源；只有写后重新读回、且目标商品行的
     * {@code sn} 恰好等于本次运单号，才返回 ok。回查不符、读不到、读超时一律进人工对账。</p>
     */
    private SourceSyncResult pushOnce(
            SourceShipmentResult result, String ownerToken, ExternalWritePermit permit) {
        String subOrderRef = result.sourceLineRef().trim();
        String trackingNo = result.firstTrackingNo().trim();
        boolean externalWriteStarted = false;
        try {
            FeixiangShipmentPlanner.WritePlan plan = planner.plan(result);
            SourceSyncResult blocked = blockingReason(result, plan);
            if (blocked != null) {
                return blocked;
            }
            // 门闩第二道：紧贴效果标记之前。未放行时不标记效果、不消耗许可、不发请求。
            FeixiangShipmentWriteGate.Decision decision = writeGate.inspectExternalWrite();
            if (!decision.allowed()) {
                return SourceSyncResult.failed(decision.businessCode(), decision.message());
            }

            attemptStore.markEffectStarted(subOrderRef, trackingNo, ownerToken);
            attemptStore.verifyWritePermit(subOrderRef, trackingNo, ownerToken);
            permit.beforeExternalWrite();
            externalWriteStarted = true;

            FeixiangShipmentGateway.SubmitResult submitted =
                    shipmentGateway.submit(plan.orderSonId(), plan.request());
            if (submitted.outcome() == FeixiangShipmentGateway.Outcome.NOT_SENT) {
                // 门闩在最后一刻改判（配置竞态）。请求确定没发出，按安全失败返回；
                // 效果标记已落库，因此这次会保守地消耗掉 ARMED 布防——宁可多问一次人。
                return SourceSyncResult.failed(submitted.businessCode(), submitted.message());
            }
            if (submitted.outcome() == FeixiangShipmentGateway.Outcome.REJECTED) {
                return SourceSyncResult.failed(
                        "FEIXIANG_SHIPMENT_REJECTED",
                        "飞象拒绝发货请求："
                                + FeixiangExternalMessageSanitizer.sanitize(submitted.message(), "平台未提供原因"));
            }
            if (submitted.outcome() == FeixiangShipmentGateway.Outcome.UNKNOWN) {
                return reconciliationRequired("飞象发货响应无法确认", platformRef(plan));
            }

            FeixiangShipmentGateway.VerifyResult verified = shipmentGateway.awaitTrackingApplied(
                    plan.orderSonId(),
                    new LinkedHashSet<>(plan.request().orderProductIds()),
                    trackingNo);
            if (verified.state() == FeixiangShipmentGateway.VerifyState.CONFIRMED) {
                return SourceSyncResult.ok(platformRef(plan));
            }
            return reconciliationRequired(
                    "飞象已受理但写后回查未确认运单号已写入平台："
                            + FeixiangExternalMessageSanitizer.sanitize(verified.message(), "回查结果未知"),
                    platformRef(plan));
        } catch (RuntimeException exception) {
            if (externalWriteStarted) {
                return reconciliationRequired("飞象发货调用结果未知，请到平台核对", null);
            }
            return SourceSyncResult.failed(
                    "FEIXIANG_PLATFORM_UNAVAILABLE", "飞象发货前检查失败，尚未提交外部写请求");
        }
    }

    /** 写前只读核对；返回 null 表示可以提交。全部判据都在发出请求<b>之前</b>。 */
    private SourceSyncResult blockingReason(
            SourceShipmentResult result, FeixiangShipmentPlanner.WritePlan plan) {
        if (!plan.available()) {
            return SourceSyncResult.failed(plan.businessCode(), plan.message());
        }
        if (!FeixiangShipmentPlanner.WritePlan.STATE_SHIPPABLE.equals(plan.platformState())) {
            return SourceSyncResult.failed(
                    "FEIXIANG_ORDER_NOT_SHIPPABLE",
                    "飞象订单当前不可提交发货（平台状态：" + plan.platformState() + "），未提交物流");
        }
        if (plan.request() == null) {
            return SourceSyncResult.failed(
                    "OK".equals(plan.businessCode()) ? "FEIXIANG_WRITE_PLAN_UNAVAILABLE" : plan.businessCode(),
                    plan.message());
        }
        if (!FeixiangShipmentPlanner.sameReceiver(result, plan)) {
            return SourceSyncResult.failed(
                    "FEIXIANG_RECEIVER_MISMATCH", "Shipment 与飞象提交前最新收货信息不一致，未提交物流");
        }
        if (plan.sendableQuantity() == null
                || !plan.sendableQuantity().equals(result.sourceUnitQuantity())) {
            return SourceSyncResult.failed(
                    "FEIXIANG_SHIPMENT_QUANTITY_MISMATCH",
                    "飞象提交前最新可发份数与拟回传份数不一致，未提交物流");
        }
        if (!isBlank(result.expectedPlatformEffectHash())
                && !result.expectedPlatformEffectHash().equals(plan.effectHash())) {
            return SourceSyncResult.failed(
                    "FEIXIANG_WRITE_PLAN_CHANGED", "飞象商品行或承运商代码在确认后变化，未提交物流");
        }
        return null;
    }

    private SourceSyncResult validateShipment(SourceShipmentResult result) {
        if (result == null || result.channel() != SourceChannel.FEIXIANG) {
            return SourceSyncResult.failed("FEIXIANG_CHANNEL_MISMATCH", "发货结果不属于飞象渠道");
        }
        if (isBlank(result.sourceLineRef())) {
            return SourceSyncResult.failed("FEIXIANG_SUB_ORDER_REQUIRED", "飞象来源子单号不能为空");
        }
        if (result.shipmentId() == null) {
            return SourceSyncResult.failed("FEIXIANG_SHIPMENT_ID_REQUIRED", "飞象在线回传缺少 Shipment 血缘");
        }
        if (result.sourceUnitQuantity() == null || result.sourceUnitQuantity() <= 0) {
            return SourceSyncResult.failed(
                    "FEIXIANG_SOURCE_QUANTITY_INVALID", "飞象来源平台发货份数必须为正整数");
        }
        if (!"SHIPPED".equals(result.outcome())) {
            return SourceSyncResult.failed(
                    "FEIXIANG_OUTCOME_NOT_SHIPPABLE", "P0 只允许完整已发货结果回传飞象");
        }
        if (isBlank(result.carrierOutputValue())) {
            return SourceSyncResult.failed("FEIXIANG_CARRIER_REQUIRED", "飞象物流公司不能为空");
        }
        if (isBlank(result.firstTrackingNo())) {
            return SourceSyncResult.failed("FEIXIANG_TRACKING_REQUIRED", "飞象快递单号不能为空");
        }
        if (isBlank(result.receiverName())
                || isBlank(result.receiverPhone())
                || isBlank(result.receiverAddress())) {
            return SourceSyncResult.failed("FEIXIANG_RECEIVER_REQUIRED", "飞象发货缺少 Shipment 收货信息");
        }
        return null;
    }

    /** 平台没有下发任何单据号；用子订单 ID 作为可追溯锚点，字符集与审计白名单一致。 */
    private static String platformRef(FeixiangShipmentPlanner.WritePlan plan) {
        return plan == null || plan.orderSonId() == null ? null : "order_son_id:" + plan.orderSonId();
    }

    private static SourceSyncResult reconciliationRequired(String detail, String platformRef) {
        String message = detail == null || detail.isBlank() ? "飞象发货结果未知" : detail;
        return SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED", message + "；禁止盲目重提，请到平台核对", platformRef);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 详情拉取结果：成功的详情与失败的 order_son_id 分开带出。 */
    private record DetailFetch(List<FeixiangOrderDetail> details, List<String> failedIds) {
        DetailFetch {
            details = details == null ? List.of() : List.copyOf(details);
            failedIds = failedIds == null ? List.of() : List.copyOf(failedIds);
        }
    }
}
