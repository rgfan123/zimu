package cn.zimu.fulfillment.connector.feixiang;

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
 * 飞象 Connector：在线拉单（ticket 08，文件化链路）。
 *
 * <p>pullOrders 链路：cookie 会话登录（表单 + 302 判定）→ 导出直下 xlsx 字节
 * → 复用现有文件解析管线 {@link SourceImportService#upload}（与文件导入行为一致）。
 * 飞象无 JSON 订单接口，维持文件形态；产物命中「飞象 v1」21 列指纹（误命名 .csv 实为 xlsx）。</p>
 *
 * <p>合规：真实网络只在 {@link #pullOrders} / {@link #testConnection} 的登录探测时发生；
 * 凭据只走环境变量，凭据缺失返回 {@code CREDENTIALS_REQUIRED} 失败而非抛异常。
 * testConnection/日期计算/结果装配等公共实现见 {@link AbstractHttpPullConnector}。</p>
 */
@Component
public class FeixiangConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(FeixiangConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SourceImportService sourceImportService;
    private final FeixiangPullClient pullClient;

    public FeixiangConnector(SourceImportService sourceImportService, FeixiangPullClient pullClient) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.FEIXIANG;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        // fileImport/fileExport 保持既有文件模式；onlinePull 置 true（ticket 08）
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
            byte[] xlsx = pullClient.pullDeliverExport(begin.format(DAY), end.format(DAY));
            String filename = "feixiang-deliver-" + end.format(DAY) + ".xlsx";
            Map<String, Object> batch = sourceImportService.upload(
                    xlsx,
                    filename,
                    "NEW",
                    null,
                    "platform-pull-" + channel.name().toLowerCase() + "-" + System.nanoTime(),
                    commandContext());
            int accepted = acceptedCount(batch);
            String message = "已拉取飞象待发货订单，导入批次 " + batch.get("batch_no")
                    + "（accepted=" + accepted + "）";
            log.info("飞象拉取完成: {}", message);
            return success(channel, accepted, message, batch);
        } catch (BusinessException exception) {
            if (DUPLICATE_CODES.contains(exception.getBusinessCode())) {
                log.info("飞象拉取命中重复订单（{}），按无新数据处理", exception.getBusinessCode());
                return success(channel, 0, "已拉取但订单已存在（重复拉取防护），无新数据导入");
            }
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (FeixiangPullClient.PullTransportException exception) {
            log.warn("飞象拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        }
    }
}
