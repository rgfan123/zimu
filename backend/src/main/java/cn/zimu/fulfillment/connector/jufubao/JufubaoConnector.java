package cn.zimu.fulfillment.connector.jufubao;

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
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 聚福宝 Connector：在线拉单（ticket 09，JSON 直连 → 结构化导入）。
 *
 * <p>pullOrders 链路：登录（JFB_SESSION_CID + 3 JWT + CSRF 头）→ {@code orders/query}
 * 分页拉取（no_delivery，page_token 游标）→ {@link JufubaoOrderTransform} 转换为
 * {@link StructuredOrderRow} → 走 {@link SourceImportService#importStructured}
 * （建批次 + raw 行血缘 + 行级跳过，confirm/履约导出与文件导入复用）。</p>
 *
 * <p>合规：真实网络只在 {@link #pullOrders} / {@link #testConnection} 的登录探测时发生；
 * 凭据只走环境变量，凭据缺失返回 {@code CREDENTIALS_REQUIRED} 失败而非抛异常。
 * testConnection/日期计算/结果装配等公共实现见 {@link AbstractHttpPullConnector}。</p>
 */
@Component
public class JufubaoConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(JufubaoConnector.class);
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SourceImportService sourceImportService;
    private final JufubaoPullClient pullClient;
    private final JufubaoOrderTransform transform;

    public JufubaoConnector(
            SourceImportService sourceImportService,
            JufubaoPullClient pullClient,
            JufubaoOrderTransform transform) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.transform = transform;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.JUFUBAO;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        // fileImport/fileExport 保持既有文件模式；onlinePull 置 true（ticket 09）
        return new ConnectorCapabilities(true, true, true, false, false);
    }

    @Override
    protected LoginProbe loginProbe() {
        JufubaoPullClient.LoginResult login = pullClient.login();
        return new LoginProbe(login.ok(), login.businessCode(), login.message());
    }

    @Override
    public PullResult pullOrders(PullCursor cursor) {
        SourceChannel channel = channel();
        try {
            JufubaoPullClient.LoginResult login = pullClient.login();
            if (!login.ok()) {
                return failed(channel, login.businessCode(), login.message());
            }
            long[] range = epochRange(cursor);
            List<Map<String, Object>> orders = pullClient.pullOrders(range[0], range[1]);
            if (orders.isEmpty()) {
                return PullResult.empty(channel, null);
            }
            List<StructuredOrderRow> rows = transform.toRows(orders);
            String batchNo = "PULL-JUFUBAO-" + LocalDateTime.now(SHANGHAI).format(BATCH_NO);
            Map<String, Object> batch = sourceImportService.importStructured(
                    SourceChannel.JUFUBAO, rows, batchNo, commandContext());
            String message = "已拉取聚福宝待发货订单 " + orders.size() + " 单，导入批次 " + batch.get("batch_no");
            log.info("聚福宝拉取完成: {}", message);
            return success(channel, orders.size(), message, batch);
        } catch (BusinessException exception) {
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (JufubaoPullClient.PullTransportException exception) {
            log.warn("聚福宝拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 拉取区间（Asia/Shanghai）：起点为 since 日 00:00（默认 30 天前），终点为 until 次日 00:00（含 until 全天）。 */
    private long[] epochRange(PullCursor cursor) {
        LocalDate begin = beginDate(cursor);
        LocalDate end = endDate(cursor);
        long startEpoch = begin.atStartOfDay(SHANGHAI).toEpochSecond();
        long endEpoch = end.plusDays(1).atStartOfDay(SHANGHAI).toEpochSecond();
        return new long[] {startEpoch, endEpoch};
    }
}
