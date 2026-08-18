package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.ConnectionTestResult;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ConnectorRuntime;
import cn.zimu.fulfillment.connector.PlatformConnector;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * 企业微信长连接连接器诊断：test-connection 反映真实连接状态（订阅成功 / 被踢 / 订阅封顶 / 未就绪），
 * 配置完整并不等于连接可用——以 readiness 的连接状态维度为准，从不把配置当验收。
 */
@Component
public class WecomConnector implements PlatformConnector {

    private final WecomReadinessService readinessService;

    public WecomConnector(WecomReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.WECOM;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(false, false, false, false, true);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorRuntime runtime) {
        WecomConnectionReadiness readiness = readinessService.inspect();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!readiness.configurationReady()) {
            return new ConnectionTestResult(
                    false,
                    now,
                    0,
                    "WECOM_CONNECTION_NOT_READY",
                    "企业微信长连接配置不完整；请查看受权 readiness 诊断");
        }
        return switch (readiness.connectionState()) {
            case "SUBSCRIBED" -> new ConnectionTestResult(
                    true,
                    now,
                    0,
                    "WECOM_CONNECTION_ESTABLISHED",
                    "企业微信长连接已订阅，等待真实业务消息验收");
            case "KICKED" -> new ConnectionTestResult(
                    false,
                    now,
                    0,
                    "WECOM_CONNECTION_KICKED",
                    "企业微信长连接被新连接抢占，已停止自动重连，需人工介入");
            case "FAILED" -> new ConnectionTestResult(
                    false,
                    now,
                    0,
                    "WECOM_CONNECTION_FAILED",
                    "企业微信长连接订阅失败已停止重试，请检查凭据配置");
            default -> new ConnectionTestResult(
                    false,
                    now,
                    0,
                    "WECOM_CONNECTION_NOT_READY",
                    "企业微信长连接尚未建立");
        };
    }
}
