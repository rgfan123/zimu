package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
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

/** Issue #83: 履约方 → 企微群 chatid 映射的公开 HTTP seam（写入侧）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FulfillmentProviderWecomGroupChatApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void groupChatRegistrationKeepsOtherConfigKeysAndSupportsModifyAndClear() {
        // 先配置既有 config 键（outboundMode / townRequired / customerCode），再只登记 chatid
        ResponseEntity<Map> seeded = patch(current("JD"),
                config("outboundMode", "SDK", "townRequired", true, "customerCode", "010K001"),
                writeHeaders("wecom-group-seed-001", "req-wecom-group-seed-001"));
        assertThat(seeded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdConfig(seeded.getBody())).containsEntry("outboundMode", Map.of("present", true, "value", "SDK"));
        assertThat(jdConfig(seeded.getBody())).containsEntry("customerCode", Map.of("present", true, "value", "010K001"));

        ResponseEntity<Map> set = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD001"),
                writeHeaders("wecom-group-set-001", "req-wecom-group-set-001"));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody()).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD001");
        // 其他 config 键不被覆盖
        Map<String, Object> afterSet = jdConfig(set.getBody());
        assertThat(afterSet).containsEntry("outboundMode", Map.of("present", true, "value", "SDK"));
        assertThat(afterSet).containsEntry("townRequired", Map.of("present", true, "value", true));
        assertThat(afterSet).containsEntry("customerCode", Map.of("present", true, "value", "010K001"));

        // 修改：同一履约方换群
        ResponseEntity<Map> mod = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD002"),
                writeHeaders("wecom-group-mod-001", "req-wecom-group-mod-001"));
        assertThat(mod.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mod.getBody()).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD002");

        // 清除：显式 null 表示清除映射
        ResponseEntity<Map> unset = patch(current("JD"), config("wecomGroupChatId", null),
                writeHeaders("wecom-group-unset-001", "req-wecom-group-unset-001"));
        assertThat(unset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unset.getBody()).containsEntry("wecom_group_chat_id", null);
        // 清除后其他 config 键仍在
        Map<String, Object> afterUnset = jdConfig(unset.getBody());
        assertThat(afterUnset).containsEntry("outboundMode", Map.of("present", true, "value", "SDK"));
        assertThat(afterUnset).containsEntry("customerCode", Map.of("present", true, "value", "010K001"));
    }

    @Test
    void thirdPartyProviderSupportsGroupChatRegistration() {
        ResponseEntity<Map> set = patch(current("TP"), config("wecomGroupChatId", "wrJgVnTQAAD003"),
                writeHeaders("wecom-group-tp-001", "req-wecom-group-tp-001"));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody()).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD003");

        // 非京东履约方的 jd_config 仍为空 map，不受影响
        assertThat(set.getBody()).containsEntry("jd_config", Map.of());
    }

    @Test
    void groupChatValueIsTrimmedBeforePersisting() {
        ResponseEntity<Map> set = patch(current("JD"), config("wecomGroupChatId", "  wrJgVnTQAAD004  "),
                writeHeaders("wecom-group-trim-001", "req-wecom-group-trim-001"));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody()).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD004");
    }

    @Test
    void invalidGroupChatValuesAreRejectedWithFieldLevelErrorsAndNothingPersisted() {
        // 先登记一个有效值作为基线：非法值全部拒绝后基线必须原样保留
        ResponseEntity<Map> baseline = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD004"),
                writeHeaders("wecom-group-baseline-001", "req-wecom-group-baseline-001"));
        assertThat(baseline.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Object> invalidValues = List.of(
                // 空串与纯空白：trim 后为空 → 拒绝
                "",
                "   ",
                // 超过 128 字符
                "wr-" + "a".repeat(128),
                // 空白字符与可见 ASCII 之外的值
                "wr jg", "wr\tjg", "wr\njg",
                // 非 ASCII（企微 chatid 是 ASCII 标识符，保守规则拒绝）
                "企微群聊ID",
                // 非字符串
                true);

        for (int i = 0; i < invalidValues.size(); i++) {
            ResponseEntity<Map> response = patch(current("JD"), config("wecomGroupChatId", invalidValues.get(i)),
                    writeHeaders("wecom-group-invalid-" + i, "req-wecom-group-invalid-" + i));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody())
                    .containsEntry("business_code", "FULFILLMENT_PROVIDER_WECOM_GROUP_CHAT_ID_INVALID");
            List<?> fieldErrors = castList(response.getBody().get("field_errors"));
            assertThat(fieldErrors).isNotEmpty();
            assertThat(castMap(fieldErrors.get(0))).containsEntry("field", "config.wecomGroupChatId");
        }

        // 全部拒绝后基线映射保持原样
        Map<String, Object> after = providerDetail(current("JD"));
        assertThat(after).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD004");
    }

    @Test
    void versionConcurrencyAndAuditProjectionFollowTheExistingProviderUpdateContract() {
        ResponseEntity<Map> set = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD005"),
                writeHeaders("wecom-group-ok-001", "req-wecom-group-ok-001"));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 过期版本被拒绝（版本并发沿用既有投影）
        ResponseEntity<Map> stale = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD006"), 0,
                writeHeaders("wecom-group-stale-001", "req-wecom-group-stale-001"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        // 审计沿用既有 Provider update 投影：操作、请求体与响应投影，不额外打印整个 config
        Map<String, Object> audits = http.getForObject(
                "/api/v1/audit-logs?request_id=req-wecom-group-ok-001", Map.class);
        String auditId = ((Map<?, ?>) ((java.util.List<?>) audits.get("items")).getFirst()).get("id").toString();
        Map<String, Object> audit = http.getForObject("/api/v1/audit-logs/" + auditId, Map.class);
        assertThat(audit).containsEntry("operation", "fulfillment_provider.update");
        assertThat(audit).containsEntry("operator", "wecom-group-test");
        Map<String, Object> requestPayload = castMap(audit.get("request_payload"));
        Map<String, Object> body = castMap(requestPayload.get("body"));
        // chatid 是标识符不是凭据：按既有投影回显值，不额外打印整个 config
        assertThat(castMap(body.get("config"))).containsEntry("wecomGroupChatId", "wrJgVnTQAAD005");
        assertThat(castMap(audit.get("response_payload"))).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD005");
    }

    @Test
    void typoKeysRemainRejectedAsUnknownConfigKeys() {
        ResponseEntity<Map> baseline = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD007"),
                writeHeaders("wecom-group-typo-baseline-001", "req-wecom-group-typo-baseline-001"));
        assertThat(baseline.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> response = patch(current("JD"), config("wecomGrupChatId", "wrJgVnTQAAD007"),
                writeHeaders("wecom-group-typo-001", "req-wecom-group-typo-001"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "FULFILLMENT_PROVIDER_CONFIG_KEY_UNKNOWN");
        assertThat(providerDetail(current("JD"))).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD007");
    }

    @Test
    void reminderIntervalSharesConfigWithGroupChatAndKeepsOtherKeys() {
        // 先登记 chatid，再登记提醒间隔：两个 wecom 键共存，其他 config 键不被覆盖
        ResponseEntity<Map> chat = patch(current("JD"), config("wecomGroupChatId", "wrJgVnTQAAD010"),
                writeHeaders("wecom-interval-chat-001", "req-wecom-interval-chat-001"));
        assertThat(chat.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> interval = patch(current("JD"),
                config("wecomReminderIntervalMinutes", 120, "outboundMode", "SDK"),
                writeHeaders("wecom-interval-set-001", "req-wecom-interval-set-001"));
        assertThat(interval.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(interval.getBody()).containsEntry("wecom_reminder_interval_minutes", 120);
        assertThat(interval.getBody()).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD010");
        Map<String, Object> afterSet = jdConfig(interval.getBody());
        assertThat(afterSet).containsEntry("outboundMode", Map.of("present", true, "value", "SDK"));

        // 未配置的第三方履约方投影为 null（默认 = SLA，由生成时快照消费侧决定）
        assertThat(current("TP")).containsEntry("wecom_reminder_interval_minutes", null);

        // 清除（显式 null）恢复默认，chatid 不受影响
        ResponseEntity<Map> cleared = patch(current("JD"), config("wecomReminderIntervalMinutes", null),
                writeHeaders("wecom-interval-clear-001", "req-wecom-interval-clear-001"));
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).containsEntry("wecom_reminder_interval_minutes", null);
        assertThat(cleared.getBody()).containsEntry("wecom_group_chat_id", "wrJgVnTQAAD010");

        // 非法值 422 且不落库
        ResponseEntity<Map> invalid = patch(current("JD"), config("wecomReminderIntervalMinutes", 10081),
                writeHeaders("wecom-interval-invalid-001", "req-wecom-interval-invalid-001"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(invalid.getBody())
                .containsEntry("business_code", "FULFILLMENT_PROVIDER_WECOM_REMINDER_INTERVAL_INVALID");
        assertThat(providerDetail(current("JD"))).containsEntry("wecom_reminder_interval_minutes", null);
    }

    /** 每次实时取当前版本，避免用例间/连续 patch 间的乐观锁干扰。 */
    private Map<String, Object> current(String code) {
        return Arrays.stream(http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .map(value -> (Map<String, Object>) value)
                .filter(value -> code.equals(value.get("provider_code")))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> providerDetail(Map<String, Object> provider) {
        return http.getForEntity("/api/v1/fulfillment-providers/" + provider.get("id"), Map.class).getBody();
    }

    private ResponseEntity<Map> patch(Map<String, Object> provider, Map<String, Object> config, HttpHeaders headers) {
        return patch(provider, config, ((Number) provider.get("version")).longValue(), headers);
    }

    private ResponseEntity<Map> patch(Map<String, Object> provider, Map<String, Object> config, long expectedVersion,
            HttpHeaders headers) {
        return http.exchange(
                "/api/v1/fulfillment-providers/" + provider.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("expected_version", expectedVersion, "config", config), headers),
                Map.class);
    }

    private Map<String, Object> config(Object... pairs) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            config.put((String) pairs[i], pairs[i + 1]);
        }
        return config;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jdConfig(Map<String, Object> body) {
        return (Map<String, Object>) body.get("jd_config");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<?> castList(Object value) {
        return (List<?>) value;
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "wecom-group-test");
        return headers;
    }
}
