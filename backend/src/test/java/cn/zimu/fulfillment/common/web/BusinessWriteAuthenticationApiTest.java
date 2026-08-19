package cn.zimu.fulfillment.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("production-auth-test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.gateway.basic-auth.username=business-admin",
            "app.gateway.basic-auth.password=business-admin-password",
            "app.internal-auth.service-name=trusted-order-intake",
            "app.internal-auth.bearer-token=internal-service-token-for-tests"
        })
class BusinessWriteAuthenticationApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // 与 DemoSeedHttpAcceptanceTest 同款：让 actuator 的 Redis 健康指标真实 UP，
    // 从而 /actuator/health 豁免断言的是「探活可达且正常」，而不是依赖本地 Redis。
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void forgedOperatorCannotReadTheDirectBusinessApi() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "forged-browser-operator");
        headers.set("X-Request-Id", "req-business-read-forged-001");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/customers",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED")
                .containsEntry("request_id", "req-business-read-forged-001")
                .containsEntry("trace_id", "req-business-read-forged-001");
    }

    @Test
    void matchingBasicCredentialsAuthorizeADirectBusinessRead() {
        HttpHeaders headers = authenticatedBusinessHeaders();

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/customers",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("items", "total_elements");
    }

    @Test
    void matchingBusinessOperatorWithTheWrongBasicSecretIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "business-admin");
        headers.setBasicAuth("business-admin", "wrong-business-password");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/customers",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED");
    }

    @Test
    void forgedOperatorCannotAuthorizeADirectBusinessWrite() {
        HttpHeaders headers = writeHeaders("forged-browser-operator", "business-auth-direct-forged-001");
        headers.set("X-Request-Id", "req-business-auth-forged-001");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/customers",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "customer_code", "AUTH-DIRECT-FORGED",
                        "customer_name", "未认证直连客户",
                        "active", true), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED");
        assertThat(response.getBody())
                .containsEntry("request_id", "req-business-auth-forged-001")
                .containsEntry("trace_id", "req-business-auth-forged-001");
    }

    @Test
    void matchingBasicCredentialsAuthorizeADirectBusinessWrite() {
        HttpHeaders headers = writeHeaders("business-admin", "business-auth-direct-matching-001");
        headers.setBasicAuth("business-admin", "business-admin-password");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/customers",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "customer_code", "AUTH-DIRECT-MATCHING",
                        "customer_name", "已认证直连客户",
                        "active", true), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("code", "AUTH-DIRECT-MATCHING");
    }

    @Test
    void forgedOperatorCannotAuthorizeTheSideEffectingJdOutboundPreviewGet() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "forged-browser-operator");
        headers.set("X-Request-Id", "req-jd-preview-auth-forged-001");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/999999999/jd-so-order-preview",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED")
                .containsEntry("request_id", "req-jd-preview-auth-forged-001")
                .containsEntry("trace_id", "req-jd-preview-auth-forged-001");
    }

    @Test
    void matchingBasicCredentialsReachTheSideEffectingJdOutboundPreviewGet() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/shipments/not-a-number/jd-so-order-preview",
                HttpMethod.GET,
                new HttpEntity<>(authenticatedBusinessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("business_code", "INVALID_IDENTIFIER");
    }

    @Test
    void forgedOperatorCannotAuthorizeABusinessFileDownloadGet() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "demo-admin");
        headers.set("X-Request-Id", "req-business-download-forged-001");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/fulfillment-exports/not-a-number/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED")
                .containsEntry("request_id", "req-business-download-forged-001")
                .containsEntry("trace_id", "req-business-download-forged-001");
    }

    @Test
    void matchingBasicCredentialsReachTheBusinessFileDownloadGet() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/fulfillment-exports/not-a-number/file",
                HttpMethod.GET,
                new HttpEntity<>(authenticatedBusinessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("business_code", "INVALID_IDENTIFIER");
    }

    @Test
    void directInternalWriteRequiresTheConfiguredServiceIdentity() {
        HttpHeaders headers = writeHeaders("forged-internal-service", "internal-auth-missing-001");
        headers.set("X-Request-Id", "req-internal-auth-missing-001");

        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED")
                .containsEntry("request_id", "req-internal-auth-missing-001")
                .containsEntry("trace_id", "req-internal-auth-missing-001");
    }

    @Test
    void directInternalReadRequiresTheConfiguredServiceIdentity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "forged-internal-service");
        headers.set("X-Request-Id", "req-internal-read-missing-001");

        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED")
                .containsEntry("request_id", "req-internal-read-missing-001")
                .containsEntry("trace_id", "req-internal-read-missing-001");
    }

    @Test
    void matchingInternalIdentityReachesAnInternalReadBoundary() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "trusted-order-intake");
        headers.setBearerAuth("internal-service-token-for-tests");

        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void matchingInternalServiceNameWithTheWrongBearerSecretIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "trusted-order-intake");
        headers.setBearerAuth("wrong-internal-service-token");

        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED");
    }

    @Test
    void internalBearerCannotAuthorizeAnArbitraryClaimedOperator() {
        HttpHeaders headers = writeHeaders("forged-internal-service", "internal-auth-forged-001");
        headers.setBearerAuth("internal-service-token-for-tests");

        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED");
    }

    @Test
    void matchingInternalBearerAndServerOwnedServiceNameReachTheCommandBoundary() {
        HttpHeaders headers = writeHeaders("trusted-order-intake", "internal-auth-matching-001");
        headers.setBearerAuth("internal-service-token-for-tests");

        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
    }

    @Test
    void unknownPathOutsideTheWhitelistIsRejectedWithoutCredentials() {
        // 不在豁免白名单里的全新路径（无任何控制器）：策略兜底要求业务操作人，
        // 未认证访问在进入路由前就被过滤器拒绝。以后新增端点忘了登记，本用例会拦住。
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req-unknown-path-anon-001");

        ResponseEntity<Map> response = http.exchange(
                "/v1/future-endpoint/things",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED")
                .containsEntry("request_id", "req-unknown-path-anon-001")
                .containsEntry("trace_id", "req-unknown-path-anon-001");
    }

    @Test
    void whitelistedDemoEntryRemainsReachableWithoutCredentials() {
        // Demo 入口（含写端点）在豁免白名单内：生产策略下保持既有无认证行为不变。
        ResponseEntity<List> response = http.exchange(
                "/demo/v1/scenarios",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat((Map) response.getBody().get(0)).containsEntry("scenario_code", "HAPPY_PATH");
    }

    @Test
    void whitelistedActuatorHealthRemainsReachableWithoutCredentials() {
        // 健康检查在豁免白名单内：探活请求无需管理凭据。
        ResponseEntity<Map> response = http.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    private static HttpHeaders writeHeaders(String operator, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", operator);
        return headers;
    }

    private static HttpHeaders authenticatedBusinessHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "business-admin");
        headers.setBasicAuth("business-admin", "business-admin-password");
        return headers;
    }
}
