package cn.zimu.fulfillment.connector.zhonghui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** #117 的真实 HTTP 回归：选择 REAL Adapter 仍不会绕过默认关闭的写门闩。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.zhonghui-pms.client-mode=REAL",
            "app.zhonghui-pms.write-mode=OFF",
            "app.zhonghui-pms.base-url=http://127.0.0.1:1",
            "app.zhonghui-pms.username=test-user",
            "app.zhonghui-pms.password=test-password"
        })
class ZhonghuiPmsWriteModeApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void statusExposesClosedLatchAndHttpWritesFailBeforeNetworkAccess() {
        Map<String, Object> status = http.getForObject("/api/v1/zhonghui-pms/status", Map.class);
        assertThat(status).containsEntry("client_mode", "REAL");
        assertThat(status).containsEntry("write_mode", "OFF");
        assertThat(status).containsEntry("external_writes_enabled", false);
        assertThat(status).containsEntry("live_ready", false);

        ResponseEntity<Map> login = http.exchange(
                "/api/v1/zhonghui-pms/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("auth_code", "5620", "captcha_no", "captcha"), writeHeaders("login")),
                Map.class);
        assertDisabled(login);

        ResponseEntity<Map> batch = http.exchange(
                "/api/v1/zhonghui-pms/batch-uploads",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("sku_ids", List.of("1")), writeHeaders("batch")),
                Map.class);
        assertDisabled(batch);
    }

    private HttpHeaders writeHeaders(String suffix) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Operator", "zhonghui-write-mode-test");
        headers.set("X-Request-Id", "req-zhonghui-write-mode-" + suffix);
        headers.set("Idempotency-Key", "idem-zhonghui-write-mode-" + suffix);
        return headers;
    }

    private void assertDisabled(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry(
                "business_code", "ZHONGHUI_PMS_WRITE_MODE_DISABLED");
    }
}
