package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Ticket 02: 本地客户档案维护京东客户编码(单条维护 + 批量导入)的公开 HTTP seam。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CustomerJdCodeApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void operatorMaintainsJdCustomerCodeWithAuditTrailAndUniqueness() {
        Map<String, Object> customer = customer("CUST-WECOM-0001");
        long version = ((Number) customer.get("version")).longValue();

        ResponseEntity<Map> patched = patch(customer, version, "JD-CUST-API-001",
                "req-customer-jd-code-001");
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(patched.getBody())).containsEntry("jd_customer_code", "JD-CUST-API-001");

        ResponseEntity<Map> detail = http.getForEntity(
                "/api/v1/customers/" + customer.get("id"), Map.class);
        assertThat(attributes(detail.getBody())).containsEntry("jd_customer_code", "JD-CUST-API-001");

        // 变更留痕:操作人 + 变更前后值
        Map<String, Object> audits = http.getForObject(
                "/api/v1/audit-logs?request_id=req-customer-jd-code-001", Map.class);
        String auditId = ((Map<?, ?>) ((List<?>) audits.get("items")).getFirst()).get("id").toString();
        Map<String, Object> audit = http.getForObject("/api/v1/audit-logs/" + auditId, Map.class);
        assertThat(audit).containsEntry("operator", "customer-jd-code-test");
        assertThat(castMap(castMap(audit.get("request_payload"))).get("jd_customer_code_before")).isNull();

        ResponseEntity<Map> changed = patch(customer, ((Number) patched.getBody().get("version")).longValue(),
                "JD-CUST-API-002", "req-customer-jd-code-002");
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> changeAudit = http.getForObject(
                "/api/v1/audit-logs/" + auditDetailId("req-customer-jd-code-002"), Map.class);
        assertThat(castMap(castMap(changeAudit.get("request_payload"))))
                .containsEntry("jd_customer_code_before", "JD-CUST-API-001");

        // 唯一性:另一客户不能复用同一京东客户编码
        String secondCode = "CUST-UNIQUE-" + token();
        Map<String, Object> second = createCustomer(secondCode, "京东编码冲突客户");
        ResponseEntity<Map> duplicate = patch(second, ((Number) second.get("version")).longValue(),
                "JD-CUST-API-002", "req-customer-jd-code-duplicate-001");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("business_code", "JD_CUSTOMER_CODE_EXISTS");

        // 清空后不再承载该键（空字符串为显式清空；null 表示不修改）
        ResponseEntity<Map> cleared = patch(second, ((Number) second.get("version")).longValue(), "",
                "req-customer-jd-code-clear-001");
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(attributes(cleared.getBody())).doesNotContainKey("jd_customer_code");
    }

    @Test
    void batchImportIsRigorousIdempotentAndNeverSilentlyOverwrites() {
        String customerA = "CUST-A-" + token();
        String customerB = "CUST-B-" + token();
        createCustomer(customerA, "导入客户甲");
        createCustomer(customerB, "导入客户乙");

        ResponseEntity<Map> imported = importRows(writeHeaders("customer-jd-import-001", "req-customer-jd-import-001"),
                row(customerA, "JD-IMP-A"), row(customerB, "JD-IMP-B"));
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody()).containsEntry("accepted_count", 2).containsEntry("skipped_count", 0);

        // 同一份档案重复导入:不产生重复记录,也不翻转已维护的值
        ResponseEntity<Map> replayed = importRows(writeHeaders("customer-jd-import-001", "req-customer-jd-import-001"),
                row(customerA, "JD-IMP-A"), row(customerB, "JD-IMP-B"));
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(imported.getBody());
        assertThat(customerJdCode(customerA)).isEqualTo("JD-IMP-A");

        // 已有不同值:显式报错,不静默覆盖
        ResponseEntity<Map> conflict = importRows(writeHeaders("customer-jd-import-002", "req-customer-jd-import-002"),
                row(customerA, "JD-IMP-A-DIFFERENT"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(conflict.getBody()).containsEntry("business_code", "CUSTOMER_JD_CODE_IMPORT_CONFLICT");
        assertThat(customerJdCode(customerA)).isEqualTo("JD-IMP-A");

        // 文件内重复行:显式报错
        ResponseEntity<Map> duplicateRow = importRows(
                writeHeaders("customer-jd-import-003", "req-customer-jd-import-003"),
                row(customerA, "JD-IMP-A"), row(customerA, "JD-IMP-A"));
        assertThat(duplicateRow.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicateRow.getBody()).containsEntry("business_code", "CUSTOMER_JD_CODE_IMPORT_DUPLICATE_ROW");

        // 未知客户与超长编码:显式报错
        ResponseEntity<Map> unknown = importRows(writeHeaders("customer-jd-import-004", "req-customer-jd-import-004"),
                row("CUST-NOPE-" + token(), "JD-IMP-X"));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknown.getBody()).containsEntry("business_code", "CUSTOMER_JD_CODE_IMPORT_CUSTOMER_UNKNOWN");

        ResponseEntity<Map> tooLong = importRows(writeHeaders("customer-jd-import-005", "req-customer-jd-import-005"),
                Map.of("customer_code", customerA, "jd_customer_code", "J".repeat(65)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooLong.getBody()).containsEntry("business_code", "CUSTOMER_JD_CODE_IMPORT_INVALID_ROW");

        // 京东客户编码被其他客户占用:显式报错
        ResponseEntity<Map> occupied = importRows(writeHeaders("customer-jd-import-006", "req-customer-jd-import-006"),
                row(customerB, "JD-IMP-A"));
        assertThat(occupied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(occupied.getBody()).containsEntry("business_code", "CUSTOMER_JD_CODE_IMPORT_CONFLICT");
        assertThat(customerJdCode(customerB)).isEqualTo("JD-IMP-B");
    }

    private Map<String, Object> createCustomer(String code, String name) {
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/customers",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "customer_code", code,
                        "customer_name", name,
                        "active", true),
                        writeHeaders("customer-create-" + token(), "req-customer-create-" + token())),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private Map<String, Object> customer(String code) {
        var page = http.getForObject("/api/v1/customers?page=0&size=200", Map.class);
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .filter(row -> code.equals(row.get("code")))
                .findFirst()
                .orElseThrow();
    }

    private ResponseEntity<Map> patch(Map<String, Object> customer, long expectedVersion, String jdCustomerCode,
            String requestId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("expected_version", expectedVersion);
        body.put("jd_customer_code", jdCustomerCode);
        return http.exchange(
                "/api/v1/customers/" + customer.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders("customer-jd-code-" + requestId, requestId)),
                Map.class);
    }

    private ResponseEntity<Map> importRows(HttpHeaders headers, Map<String, Object>... rows) {
        return http.exchange(
                "/api/v1/customers/jd-customer-code-imports",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("rows", List.of(rows)), headers),
                Map.class);
    }

    private Map<String, Object> row(String customerCode, String jdCustomerCode) {
        return Map.of("customer_code", customerCode, "jd_customer_code", jdCustomerCode);
    }

    private String customerJdCode(String customerCode) {
        Map<String, Object> body = http.getForEntity("/api/v1/customers/" + customer(customerCode).get("id"), Map.class)
                .getBody();
        Object value = attributes(body).get("jd_customer_code");
        return value == null ? null : value.toString();
    }

    private String auditDetailId(String requestId) {
        Map<String, Object> audits = http.getForObject(
                "/api/v1/audit-logs?request_id=" + requestId, Map.class);
        return ((Map<?, ?>) ((List<?>) audits.get("items")).getFirst()).get("id").toString();
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> body) {
        return (Map<String, Object>) body.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "customer-jd-code-test");
        return headers;
    }
}
