package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.file.SourceImportService;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * <p><b>范围</b>：只读拉取。不实现向平台提交运单号发货（{@code ajaxSendOrderProduct} 等），
 * 回传见后续单独票。文件导入路径（{@code SourceFileParser} 的飞象指纹）不受影响，保持可用。</p>
 */
@Component
public class FeixiangConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(FeixiangConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SourceImportService sourceImportService;
    private final FeixiangPullClient pullClient;
    private final FeixiangOrderTransform transform;

    public FeixiangConnector(
            SourceImportService sourceImportService,
            FeixiangPullClient pullClient,
            FeixiangOrderTransform transform) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.transform = transform;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.FEIXIANG;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        // fileImport/fileExport 保持既有文件模式；onlinePull 置 true
        return new ConnectorCapabilities(true, true, true, false, false);
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
            log.error("飞象列表页解析失效: 平台自报 {} 单，实际解析出 0 单（window={}~{}）", reported, begin, end);
            return failed(
                    channel,
                    "FEIXIANG_ORDER_LIST_UNPARSEABLE",
                    "飞象平台自报窗口内有 " + reported + " 单，但列表页解析出 0 单——"
                            + "列表页 HTML 结构与解析规则不匹配，已停止并报错，未按「无新数据」处理");
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

    /** 详情拉取结果：成功的详情与失败的 order_son_id 分开带出。 */
    private record DetailFetch(List<FeixiangOrderDetail> details, List<String> failedIds) {
        DetailFetch {
            details = details == null ? List.of() : List.copyOf(details);
            failedIds = failedIds == null ? List.of() : List.copyOf(failedIds);
        }
    }
}
