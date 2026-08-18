package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.JdWarehouseClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdWarehouseClientSafetyTest {

    @Test
    void sdkExceptionsBecomeAStablePublicMessageInsteadOfLeakingTheRawFailure() {
        JdWarehouseClient client = new JdWarehouseClient(
                new ObjectMapper(),
                mock(AuditLogService.class),
                "not-a-valid-jd-url",
                "configured-app-key",
                "configured-app-secret",
                "configured-access-token",
                "",
                "");

        JdResult result = client.queryOwners(Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("SDK_CALL_FAILED");
        assertThat(result.message()).isEqualTo("京东服务暂时不可用，请稍后重试");
        assertThat(result.message()).doesNotContain("URL", "http", "Exception", "not-a-valid-jd-url");
    }
}
