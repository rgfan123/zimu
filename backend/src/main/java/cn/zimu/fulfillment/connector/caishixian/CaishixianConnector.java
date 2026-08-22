package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.file.SourceImportService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 彩食鲜 Connector：在线拉单（ticket 07，文件化链路）。
 *
 * <p>pullOrders 链路：登录（login-token 续期）→ 发起导出任务 → 轮询完成 → 下载 xlsx 字节
 * → 复用现有文件解析管线 {@link SourceImportService#upload}（raw 行血缘、confirm、履约导出
 * 全链路与文件导入一致）。产物是真实文件字节，无需自定义 transform。</p>
 *
 * <p>合规：真实网络只在 {@link #pullOrders} / {@link #testConnection} 的登录探测时发生；
 * 凭据只走环境变量，凭据缺失返回 {@code CREDENTIALS_REQUIRED} 失败而非抛异常。
 * testConnection/日期计算/结果装配等公共实现见 {@link AbstractHttpPullConnector}。</p>
 */
@Component
public class CaishixianConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(CaishixianConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SourceImportService sourceImportService;
    private final CaishixianPullClient pullClient;

    public CaishixianConnector(SourceImportService sourceImportService, CaishixianPullClient pullClient) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.CAISHIXIAN;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        // fileImport/fileExport 保持既有文件模式；onlinePull 置 true（ticket 07）
        return new ConnectorCapabilities(true, true, true, false, false);
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
            LocalDate begin = beginDate(cursor);
            LocalDate end = endDate(cursor);
            byte[] xlsx = pullClient.pullDeliverExport(login.token(), begin.format(DAY), end.format(DAY));
            String filename = "caishixian-deliver-" + end.format(DAY) + ".xlsx";
            Map<String, Object> batch = sourceImportService.upload(
                    xlsx,
                    filename,
                    "NEW",
                    null,
                    "platform-pull-" + channel.name().toLowerCase() + "-" + System.nanoTime(),
                    commandContext());
            int accepted = acceptedCount(batch);
            String message = "已拉取彩食鲜待发货订单，导入批次 " + batch.get("batch_no")
                    + "（accepted=" + accepted + "）";
            log.info("彩食鲜拉取完成: {}", message);
            return success(channel, accepted, message, batch);
        } catch (BusinessException exception) {
            if (DUPLICATE_CODES.contains(exception.getBusinessCode())) {
                log.info("彩食鲜拉取命中重复订单（{}），按无新数据处理", exception.getBusinessCode());
                return success(channel, 0, "已拉取但订单已存在（重复拉取防护），无新数据导入");
            }
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (CaishixianPullClient.PullTransportException exception) {
            log.warn("彩食鲜拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        }
    }
}
