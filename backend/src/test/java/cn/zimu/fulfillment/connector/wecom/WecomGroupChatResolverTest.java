package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.FulfillmentProviderWecomConfig;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Issue #83: 履约方 → 企微群 chatid 解析 seam（#84 发送消费侧）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WecomGroupChatResolverTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WecomGroupChatResolver resolver;

    @Autowired
    private FulfillmentProviderRepository providers;

    @Test
    void resolveReadsFreshlyConfiguredChatIdImmediatelyWithoutRestart() {
        long providerId = providerId();

        // 未登记：明确可操作的业务错误，而不是静默返回空
        assertThatThrownBy(() -> resolver.resolve(providerId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请在履约方配置登记企微群");

        // 登记后下一次解析立即读到新值（每次实时读 DB，无需重启）
        patch(config("wecomGroupChatId", "wrJgVnTQAAD101"), "wecom-resolver-set-001");
        assertThat(resolver.resolve(providerId)).isEqualTo("wrJgVnTQAAD101");

        // 修改后下一次解析立即读到新值
        patch(config("wecomGroupChatId", "wrJgVnTQAAD102"), "wecom-resolver-mod-001");
        assertThat(resolver.resolve(providerId)).isEqualTo("wrJgVnTQAAD102");

        // 清除后下一次解析立即恢复明确错误
        patch(config("wecomGroupChatId", null), "wecom-resolver-unset-001");
        assertThatThrownBy(() -> resolver.resolve(providerId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请在履约方配置登记企微群");
    }

    @Test
    void resolveReportsUnknownProviderAndNeverLeaksOtherConfig() {
        assertThatThrownBy(() -> resolver.resolve(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getHttpStatus())
                .isEqualTo(404);

        // 错误信息只含可操作指引，不输出其他 config/密钥内容
        assertThatThrownBy(() -> resolver.resolve(providerId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("config")
                .hasMessageNotContaining("pin")
                .hasMessageContaining("请在履约方配置登记企微群");
    }

    @Test
    void resolveTreatsLegacyInvalidStoredValuesAsNotConfigured() {
        // 直写绕过写入校验的非法存量值（如历史脏数据）：解析 seam 必须视为未登记并给出明确错误，
        // 不得把非法值原样交给 #84 发送
        FulfillmentProvider provider = providers.findByProviderCode("JD").orElseThrow();
        Map<String, Object> config = new LinkedHashMap<>(provider.getConfig());
        config.put(FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY, "wr jg");
        provider.setConfig(config);
        providers.saveAndFlush(provider);

        assertThatThrownBy(() -> resolver.resolve(provider.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请在履约方配置登记企微群");
    }

    private long providerId() {
        return Long.parseLong((String) jdProvider().get("id"));
    }

    private Map<String, Object> jdProvider() {
        return Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .filter(value -> "JD".equals(value.get("provider_code")))
                .findFirst()
                .orElseThrow();
    }

    private ResponseEntity<Map> patch(Map<String, Object> config, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", jdProvider().get("version"));
        body.put("config", config);
        return http.exchange(
                "/api/v1/fulfillment-providers/" + jdProvider().get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders(key)),
                Map.class);
    }

    private Map<String, Object> config(Object... pairs) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            config.put((String) pairs[i], pairs[i + 1]);
        }
        return config;
    }

    private static HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", "wecom-resolver-test");
        return headers;
    }
}
