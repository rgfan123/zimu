package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 三平台 P0 走文件传输；在线 API 未获文档/凭据前明确不可用。 */
public abstract class ExcelPlatformConnector implements PlatformConnector {

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(true, true, false, false, false);
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorRuntime runtime) {
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (!runtime.enabled()) {
            return new ConnectionTestResult(false, checkedAt, 0, "CONNECTOR_DISABLED", "Connector 已停用");
        }
        if ("EXCEL".equals(runtime.transportMode())) {
            return new ConnectionTestResult(true, checkedAt, 0, "EXCEL_ADAPTER_READY", "文件 Adapter 可用");
        }
        if (!runtime.credentialConfigured() || runtime.endpoint() == null || runtime.endpoint().isBlank()) {
            return new ConnectionTestResult(false, checkedAt, 0, "CREDENTIALS_REQUIRED", "在线 API 尚未配置端点或凭据引用");
        }
        return new ConnectionTestResult(false, checkedAt, 0, "CAPABILITY_UNAVAILABLE", "该渠道在线 API Client 尚未接入");
    }
}
