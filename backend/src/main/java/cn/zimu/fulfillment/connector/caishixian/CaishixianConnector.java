package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ExternalWritePermit;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceReceiverNormalizer;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 彩食鲜 Connector：在线拉单（JSON 直连版）。
 *
 * <p>pullOrders 链路：登录（login-token 续期）→ {@code orderList} 按 totalNum 真翻页取完
 * → 逐单 {@code orderDetail} 补收货地址与商品明细 → {@link CaishixianOrderTransform}
 * → {@link SourceImportService#importStructured}（与聚福宝结构化拉取同形：raw 行血缘、
 * 重复订单逐单跳过不整批回滚、confirm/履约导出管线复用）。</p>
 *
 * <p><b>拉取对账（本票核心可观测性）</b>：平台在 orderList 响应里自报
 * {@code number.waitDepotNum}（当前待发货单数）。每次拉取都把
 * {@code 实取行数 / totalNum / waitDepotNum} 写进日志与拉取结果 message；
 * 三者不一致时以 {@code CAISHIXIAN_PULL_RECONCILIATION_MISMATCH} 标记打 WARN——
 * 「窗口过滤是否吞单」从此在生产里自动定案（waitDepotNum &gt; totalNum = 窗口在吞单；
 * 实取 &lt; totalNum = 翻页丢行），不再靠猜。</p>
 *
 * <p>旧「导出任务」拉取链路已移除（pageSize:10 静默截断 + 窗口不可观测两缺陷的载体）；
 * 平台后台手工导出的 xlsx 仍可走既有文件上传导入路径兜底，SourceFileParser 彩食鲜指纹未动。</p>
 *
 * <p>合规：真实网络只在 {@link #pullOrders} / {@link #testConnection} 的登录探测时发生；
 * 凭据只走环境变量，凭据缺失返回 {@code CREDENTIALS_REQUIRED} 失败而非抛异常。
 * testConnection/日期计算/结果装配等公共实现见 {@link AbstractHttpPullConnector}。</p>
 */
@Component
public class CaishixianConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(CaishixianConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 页大小沿用抓包实测值 10（pageSize 语义基于单次观测，翻页取完不依赖它更大）。 */
    static final int PAGE_SIZE = 10;

    /**
     * 翻页保护上限（10 × 500 = 5000 单，远超该渠道日常量级）。触顶不静默：丢弃数
     * 显式进 WARN 日志与拉取结果 message，运维据此告警。
     */
    static final int MAX_PAGES = 500;

    /** 对账不一致的日志标记（告警规则按它匹配）。 */
    static final String RECONCILIATION_MARKER = "CAISHIXIAN_PULL_RECONCILIATION_MISMATCH";

    private final SourceImportService sourceImportService;
    private final CaishixianPullClient pullClient;
    private final CaishixianOrderTransform transform;
    private final CaishixianShipmentGateway shipmentGateway;

    public CaishixianConnector(
            SourceImportService sourceImportService,
            CaishixianPullClient pullClient,
            CaishixianOrderTransform transform,
            CaishixianShipmentGateway shipmentGateway) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.transform = transform;
        this.shipmentGateway = shipmentGateway;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.CAISHIXIAN;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(true, true, true, true, false);
    }

    @Override
    protected LoginProbe loginProbe() {
        CaishixianPullClient.LoginResult login = pullClient.login();
        return new LoginProbe(login.ok(), login.businessCode(), login.message());
    }

    @Override
    public PullResult pullOrders(PullCursor cursor) {
        SourceChannel channel = channel();
        try {
            CaishixianPullClient.LoginResult login = pullClient.login();
            if (!login.ok()) {
                return failed(channel, login.businessCode(), login.message());
            }
            String begin = beginDate(cursor).format(DAY);
            String end = endDate(cursor).format(DAY);
            PagedOrders paged = fetchAllPages(login.token(), begin, end);
            String reconciliation = reconciliationSummary(begin, end, paged);
            if (paged.mismatch()) {
                // 告警点：waitDepotNum 与实取行数不一致（窗口过滤吞单的直接证据），
                // 或翻页保护上限触顶产生显式丢弃。运维告警按 RECONCILIATION_MARKER 匹配。
                log.warn("{}: {}", RECONCILIATION_MARKER, reconciliation);
            } else {
                log.info("彩食鲜拉取对账一致: {}", reconciliation);
            }
            if (paged.orders().isEmpty()) {
                // 0 行也要把对账带回结果——「今天拉取三次全部 0 行」正是需要 waitDepotNum
                // 说话的场景（waitDepotNum>0 而实取 0 行即平台明说还有待发货单没进窗口）。
                return success(channel, 0, "彩食鲜本窗口未拉到订单；" + reconciliation);
            }
            List<StructuredOrderRow> rows = toRows(login.token(), paged.orders());
            String batchNo = "PULL-CAISHIXIAN-" + LocalDateTime.now(SHANGHAI).format(BATCH_NO);
            Map<String, Object> batch = sourceImportService.importStructured(
                    channel, rows, batchNo, commandContext());
            int accepted = acceptedCount(batch);
            String message = "已拉取彩食鲜订单 " + rows.size() + " 单，导入批次 " + batch.get("batch_no")
                    + "（accepted=" + accepted + "）；" + reconciliation;
            log.info("彩食鲜拉取完成: batchNo={}, pulled={}, accepted={}", batch.get("batch_no"), rows.size(), accepted);
            return success(channel, accepted, message, batch);
        } catch (BusinessException exception) {
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (CaishixianPullClient.PullTransportException exception) {
            log.warn("彩食鲜拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        }
    }

    /** 翻页聚合结果（含对账事实）。droppedByGuard 只在翻页保护上限触顶时非 0。 */
    record PagedOrders(
            List<JsonNode> orders,
            int totalNum,
            Integer waitDepotNum,
            int droppedByGuard) {
        PagedOrders {
            orders = List.copyOf(orders);
        }

        /** 对账不一致 = waitDepotNum 与实取行数不一致，或翻页保护产生显式丢弃。 */
        boolean mismatch() {
            return droppedByGuard > 0
                    || orders.size() != totalNum
                    || (waitDepotNum != null && waitDepotNum != orders.size());
        }
    }

    /**
     * orderList 按 totalNum 翻页取完。终止条件：本页为空、累计行数 ≥ totalNum、
     * 或触达 {@link #MAX_PAGES} 保护上限（触顶时把丢弃数算清楚，绝不静默）。
     * totalNum 每页刷新（拉取期间平台侧订单状态可能变化），waitDepotNum 取最后一次非空值。
     */
    private PagedOrders fetchAllPages(String token, String begin, String end) {
        List<JsonNode> orders = new ArrayList<>();
        int totalNum = 0;
        Integer waitDepotNum = null;
        int droppedByGuard = 0;
        for (int pageNum = 1; ; pageNum++) {
            if (pageNum > MAX_PAGES) {
                droppedByGuard = Math.max(0, totalNum - orders.size());
                log.warn("{}: 翻页达到保护上限 {} 页，剩余 {} 行未取（totalNum={}）",
                        RECONCILIATION_MARKER, MAX_PAGES, droppedByGuard, totalNum);
                break;
            }
            CaishixianPullClient.OrderPage page =
                    pullClient.pullOrderPage(token, begin, end, pageNum, PAGE_SIZE);
            totalNum = page.totalNum();
            if (page.waitDepotNum() != null) {
                waitDepotNum = page.waitDepotNum();
            }
            orders.addAll(page.orders());
            log.info("彩食鲜 orderList 第 {} 页: +{} 行（累计 {} / totalNum={} / waitDepotNum={}）",
                    pageNum, page.orders().size(), orders.size(), totalNum, waitDepotNum);
            if (page.orders().isEmpty() || orders.size() >= totalNum) {
                break;
            }
        }
        return new PagedOrders(orders, totalNum, waitDepotNum, droppedByGuard);
    }

    /** 逐单补 detail 后转换。单单 detail 失败只把该单转人工复核（transform 内），不废整批。 */
    private List<StructuredOrderRow> toRows(String token, List<JsonNode> listItems) {
        List<StructuredOrderRow> rows = new ArrayList<>(listItems.size());
        for (JsonNode listItem : listItems) {
            String platformOrderId = listItem.path("id").asText("").trim();
            JsonNode detail = null;
            if (!platformOrderId.isBlank()) {
                try {
                    detail = pullClient.pullOrderDetail(token, platformOrderId);
                } catch (CaishixianPullClient.PullTransportException exception) {
                    log.warn("彩食鲜 orderDetail 失败（该单转人工复核，不废整批）: platformOrderId={}, err={}",
                            platformOrderId, exception.getMessage());
                }
            } else {
                log.warn("彩食鲜 orderList 行缺少 id，无法补 detail（该单转人工复核）: orderCode={}",
                        listItem.path("orderCode").asText(""));
            }
            rows.add(transform.toRow(listItem, detail));
        }
        return List.copyOf(rows);
    }

    /**
     * 对账口径（进日志 + 拉取结果 message，UI 与审计都能看到）：
     * 实取行数 / totalNum（平台自报窗口内总数）/ waitDepotNum（平台自报当前待发货单数）。
     * waitDepotNum &gt; totalNum ⇒ 待发货单没全落进窗口（窗口过滤吞单的直接证据）；
     * 实取 &lt; totalNum ⇒ 翻页丢行（含保护上限触顶的显式丢弃）。
     */
    private String reconciliationSummary(String begin, String end, PagedOrders paged) {
        StringBuilder text = new StringBuilder("对账：窗口 ").append(begin).append("~").append(end)
                .append(" 实取 ").append(paged.orders().size()).append(" 单 / totalNum=").append(paged.totalNum())
                .append(" / 平台待发货 waitDepotNum=")
                .append(paged.waitDepotNum() == null ? "未报告" : paged.waitDepotNum());
        if (paged.droppedByGuard() > 0) {
            text.append("【翻页保护上限触顶，显式丢弃 ").append(paged.droppedByGuard()).append(" 行】");
        }
        if (paged.waitDepotNum() != null && paged.waitDepotNum() != paged.orders().size()) {
            text.append("【不一致：平台报告待发货 ").append(paged.waitDepotNum())
                    .append(" 单但本窗口实取 ").append(paged.orders().size())
                    .append(" 单，窗口过滤可能在吞单，请核对】");
        } else if (paged.orders().size() != paged.totalNum()) {
            text.append("【不一致：实取行数与 totalNum 不符，翻页可能丢行，请核对】");
        }
        return text.toString();
    }

    @Override
    public SourcePlatformCheckResult checkShipmentResult(SourceShipmentResult result) {
        SourceSyncResult invalid = validateShipment(result);
        if (invalid != null) {
            return SourcePlatformCheckResult.unavailable(channel(), invalid.businessCode(), invalid.message());
        }
        try {
            CaishixianShipmentGateway.PlatformOrderSnapshot snapshot = shipmentGateway.inspect(
                    result.sourceRef().trim(), result.sourceLineRef().trim());
            boolean receiverComplete = snapshot != null
                    && nonBlank(snapshot.receiverName())
                    && nonBlank(snapshot.receiverPhone())
                    && nonBlank(snapshot.receiverAddress());
            return new SourcePlatformCheckResult(
                    true,
                    "OK",
                    "彩食鲜 Shipment 当前事实已读取",
                    snapshot == null ? null : Integer.toString(snapshot.orderStatus()),
                    false,
                    receiverComplete
                            ? SourcePlatformCheckResult.AddressStatus.CLEAR
                            : SourcePlatformCheckResult.AddressStatus.UNKNOWN,
                    snapshot == null ? null : snapshot.receiverName(),
                    snapshot == null ? null : snapshot.receiverPhone(),
                    snapshot == null ? null : snapshot.receiverAddress(),
                    snapshot == null ? null : snapshot.sendableQuantity(),
                    carrier(result.carrierOutputValue()) != null);
        } catch (RuntimeException exception) {
            // 与聚福宝 2026-08-29 同款观测补洞：静默吞掉会让「每 10 分钟失败一次」在
            // 日志里零痕迹（2026-08-31 生产实证：11 单卡 CHECK_UNAVAILABLE 三天无人知）。
            // 类型+消息+来源单号必须留下，才能分清契约漂移/登录失效/平台风控。
            log.warn(
                    "彩食鲜 Shipment 事实读取失败 source_ref={} line_ref={} type={} message={}",
                    result.sourceRef(),
                    result.sourceLineRef(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            return SourcePlatformCheckResult.unavailable(
                    channel(), "CAISHIXIAN_PLATFORM_CHECK_UNAVAILABLE", "彩食鲜 Shipment 当前事实读取失败");
        }
    }

    /** 禁止绕过 source-sync 的人工确认、Shipment CAS 与外部写租约。 */
    @Override
    public SourceSyncResult pushShipmentResult(SourceShipmentResult result) {
        return SourceSyncResult.failed(
                "SOURCE_SYNC_EXECUTION_CONTEXT_REQUIRED",
                "彩食鲜在线发货必须通过 Shipment source-sync execute 入口");
    }

    @Override
    public SourceSyncResult pushShipmentResult(
            SourceShipmentResult result,
            ExternalWritePermit permit) {
        SourceSyncResult invalid = validateShipment(result);
        if (invalid != null) {
            return invalid;
        }
        AtomicBoolean externalWriteStarted = new AtomicBoolean();
        try {
            CaishixianShipmentGateway.PlatformOrderSnapshot before = shipmentGateway.inspect(
                    result.sourceRef().trim(), result.sourceLineRef().trim());
            if (before == null || !before.present() || before.orderStatus() != 3) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_ORDER_NOT_SHIPPABLE",
                        "彩食鲜订单当前不是待发货状态，未上传回填文件");
            }
            if (!sameReceiver(result, before)) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_RECEIVER_MISMATCH",
                        "Shipment 与彩食鲜当前收货信息不一致，未上传回填文件");
            }
            if (before.sendableQuantity() == null
                    || before.sendableQuantity().compareTo(result.sourceUnitQuantity()) != 0) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_SHIPMENT_QUANTITY_MISMATCH",
                        "Shipment 来源份数与彩食鲜当前可发数量不一致，未上传回填文件");
            }
            CaishixianShipmentGateway.CarrierOption carrier = carrier(result.carrierOutputValue());
            if (carrier == null) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_CARRIER_UNMAPPED",
                        "正式运单的物流公司未命中彩食鲜实时字典，未上传回填文件");
            }
            if (result.artifact() == null || !result.artifact().present()) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_SHIPMENT_ARTIFACT_REQUIRED",
                        "缺少单 Shipment 彩食鲜回填工作簿，未上传");
            }
            CaishixianShipmentGateway.UploadAck uploaded = shipmentGateway.upload(result.artifact(), () -> {
                permit.beforeExternalWrite();
                externalWriteStarted.set(true);
            });
            if (uploaded.outcome() == CaishixianShipmentGateway.UploadAck.Outcome.REJECTED) {
                String code = safeCode(uploaded.platformCode());
                return SourceSyncResult.failed(
                        "CAISHIXIAN_UPLOAD_REJECTED",
                        "彩食鲜明确拒绝发货回填（业务码：" + code + "）");
            }
            CaishixianShipmentGateway.Verification verification = shipmentGateway.awaitVerified(
                    before.platformOrderId(), carrier.code(), result.firstTrackingNo().trim());
            if (verification.verified()) {
                return SourceSyncResult.ok(verification.platformOrderId());
            }
            return reconciliationRequired(
                    verification.safeMessage(),
                    before.platformOrderId());
        } catch (RuntimeException exception) {
            if (externalWriteStarted.get()) {
                return reconciliationRequired("彩食鲜上传或写后查询结果未知", null);
            }
            return SourceSyncResult.failed(
                    "CAISHIXIAN_PLATFORM_UNAVAILABLE",
                    "彩食鲜发货前查询失败，尚未上传回填文件");
        }
    }

    private SourceSyncResult validateShipment(SourceShipmentResult result) {
        if (result == null || result.channel() != SourceChannel.CAISHIXIAN) {
            return SourceSyncResult.failed("CAISHIXIAN_CHANNEL_MISMATCH", "发货结果不属于彩食鲜渠道");
        }
        if (result.shipmentId() == null || result.shipmentId() <= 0
                || !nonBlank(result.sourceRef()) || !nonBlank(result.sourceLineRef())) {
            return SourceSyncResult.failed("CAISHIXIAN_SHIPMENT_LINEAGE_REQUIRED", "彩食鲜 Shipment 来源血缘不完整");
        }
        BigDecimal quantity = result.sourceUnitQuantity();
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0) {
            return SourceSyncResult.failed("CAISHIXIAN_QUANTITY_INVALID", "彩食鲜回填数量必须是正整数来源份数");
        }
        if (!"SHIPPED".equals(result.outcome())) {
            return SourceSyncResult.failed("CAISHIXIAN_OUTCOME_NOT_SHIPPABLE", "P0 只允许完整已发货结果回传彩食鲜");
        }
        if (!nonBlank(result.carrierOutputValue()) || !nonBlank(result.firstTrackingNo())) {
            return SourceSyncResult.failed("CAISHIXIAN_TRACKING_REQUIRED", "彩食鲜回填缺少物流公司或正式运单号");
        }
        if (!nonBlank(result.receiverName()) || !nonBlank(result.receiverPhone()) || !nonBlank(result.receiverAddress())) {
            return SourceSyncResult.failed("CAISHIXIAN_RECEIVER_REQUIRED", "彩食鲜回填缺少 Shipment 收货信息");
        }
        return null;
    }

    private CaishixianShipmentGateway.CarrierOption carrier(String code) {
        if (!nonBlank(code)) {
            return null;
        }
        return shipmentGateway.carrierOptions().stream()
                .filter(option -> code.trim().equals(option.code()))
                .findFirst()
                .orElse(null);
    }

    private boolean sameReceiver(
            SourceShipmentResult result,
            CaishixianShipmentGateway.PlatformOrderSnapshot platform) {
        return SourceReceiverNormalizer.sameName(result.receiverName(), platform.receiverName())
                && SourceReceiverNormalizer.samePhone(result.receiverPhone(), platform.receiverPhone())
                && SourceReceiverNormalizer.sameAddress(result.receiverAddress(), platform.receiverAddress());
    }

    private SourceSyncResult reconciliationRequired(String message, String platformRef) {
        return SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED",
                (nonBlank(message) ? message : "彩食鲜发货结果未知") + "；禁止盲目重提，请到平台核对",
                platformRef);
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9._-]{1,64}")
                ? code
                : "CAISHIXIAN_PLATFORM_REJECTED";
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

}
