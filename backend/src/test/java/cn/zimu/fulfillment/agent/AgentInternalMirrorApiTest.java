package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 12 票验收（/internal 只读镜像，服务身份面）：
 * <ul>
 *   <li>镜像只读——agent-runs 查询 + agents 列表/详情/版本历史，全部 GET、无任何写端点
 *       （结构性扫描 + HTTP 405 双层断言）；</li>
 *   <li>Basic Auth（/api）与 internal-auth（/internal）各自鉴权正确且互不通用；</li>
 *   <li>镜像与 /api 同一投影（列表/详情/版本链/运行列表/详情，含 run_mode 默认排除
 *       PREVIEW 的过滤语义）。</li>
 * </ul>
 */
@Testcontainers
@ActiveProfiles("production-auth-test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.gateway.basic-auth.username=business-admin",
            "app.gateway.basic-auth.password=business-admin-password",
            "app.internal-auth.service-name=trusted-agent-service",
            "app.internal-auth.bearer-token=internal-agent-token-for-tests"
        })
class AgentInternalMirrorApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // ------------------------------------------------------------------
    // 鉴权：/internal 需要 internal-auth（Bearer 服务身份），与 /api 的 Basic Auth 互不通用
    // ------------------------------------------------------------------

    @Test
    void internalReadRequiresTheConfiguredServiceIdentity() {
        HttpHeaders basicOnInternal = headersWith("trusted-agent-service", null);
        basicOnInternal.setBasicAuth("business-admin", "business-admin-password");
        for (HttpHeaders headers : List.of(
                new HttpHeaders(),
                headersWith("trusted-agent-service", null),          // 无 Bearer
                headersWith("trusted-agent-service", "wrong-token"), // 错误 Bearer
                basicOnInternal)) {                                  // Basic 不能充当服务身份
            ResponseEntity<Map> response = http.exchange(
                    "/internal/v1/agents",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody())
                    .containsEntry("business_code", "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED");
        }
    }

    @Test
    void matchingInternalIdentityReachesTheMirror() {
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/agents",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("items");
    }

    @Test
    void internalBearerCannotAuthorizeApiAndBasicCannotAuthorizeInternal() {
        // Bearer 服务身份不能访问 /api（需 Basic Auth 人工面）
        ResponseEntity<Map> apiWithBearer = http.exchange(
                "/api/v1/agents",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class);
        assertThat(apiWithBearer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(apiWithBearer.getBody()).containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED");

        // Basic 人工面凭据不能访问 /internal（需服务身份）
        ResponseEntity<Map> internalWithBasic = http.exchange(
                "/internal/v1/agents",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(internalWithBasic.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(internalWithBasic.getBody())
                .containsEntry("business_code", "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED");
    }

    // ------------------------------------------------------------------
    // 镜像只读：无任何写端点（结构性 + HTTP 双层断言）
    // ------------------------------------------------------------------

    @Test
    void internalMirrorHasOnlyGetMappings() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = entry.getKey().getPathPatternsCondition().getPatternValues();
            boolean isMirror = patterns.stream().anyMatch(pattern ->
                    pattern.startsWith("/internal/v1/agents") || pattern.startsWith("/internal/v1/agent-runs"));
            if (!isMirror) {
                continue;
            }
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            assertThat(methods)
                    .as("internal 镜像端点必须只读: %s -> %s", patterns, entry.getValue())
                    .containsOnly(RequestMethod.GET);
        }
    }

    @Test
    void internalMirrorRejectsWriteVerbsOverHttp() {
        for (String path : List.of("/internal/v1/agents", "/internal/v1/agents/meta-agent", "/internal/v1/agent-runs")) {
            for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
                ResponseEntity<Map> response = http.exchange(
                        path,
                        method,
                        new HttpEntity<>(Map.of(), internalHeaders()),
                        Map.class);
                assertThat(response.getStatusCode())
                        .as("%s %s 必须是只读镜像（拒绝写动词）", method, path)
                        .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
                assertThat(response.getBody()).containsEntry("business_code", "METHOD_NOT_ALLOWED");
            }
        }
    }

    // ------------------------------------------------------------------
    // 镜像 = 与 /api 同一投影（列表/详情/版本链/运行列表/详情）
    // ------------------------------------------------------------------

    @Test
    void internalMirrorReturnsSameProjectionAsApiForAgents() {
        String detail = "/api/v1/agents/procurement-price-agent";
        String versions = "/api/v1/agents/procurement-price-agent/versions";

        assertThat(http.exchange(
                        "/internal/v1/agents/procurement-price-agent",
                        HttpMethod.GET,
                        new HttpEntity<>(internalHeaders()),
                        Map.class)
                .getBody())
                .isEqualTo(http.exchange(
                        detail,
                        HttpMethod.GET,
                        new HttpEntity<>(businessHeaders()),
                        Map.class)
                        .getBody());

        assertThat(http.exchange(
                        "/internal/v1/agents/procurement-price-agent/versions",
                        HttpMethod.GET,
                        new HttpEntity<>(internalHeaders()),
                        List.class)
                .getBody())
                .isEqualTo(http.exchange(
                        versions,
                        HttpMethod.GET,
                        new HttpEntity<>(businessHeaders()),
                        List.class)
                        .getBody());
    }

    @Test
    void internalMirrorAppliesSameFiltersAndDefaultRunModeExclusion() {
        insertDefinition("mirror-e2e", "镜像验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String liveId = insertRun("mirror-e2e", "SUCCESS", null, "LIVE");
        String previewId = insertRun("mirror-e2e", "FAILED", "AGENT_OUTPUT_INVALID", "PREVIEW");

        // 默认不返回 PREVIEW（与 /api 同一过滤语义）
        ResponseEntity<Map> defaultList = http.exchange(
                "/internal/v1/agent-runs?slug=mirror-e2e",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class);
        assertThat(runIds(itemsOf(defaultList.getBody()))).containsExactly(liveId);

        ResponseEntity<Map> previewList = http.exchange(
                "/internal/v1/agent-runs?slug=mirror-e2e&run_mode=PREVIEW",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class);
        assertThat(runIds(itemsOf(previewList.getBody()))).containsExactly(previewId);

        // 详情含工具调用序列（镜像与 /api 同投影）
        jdbc.update(
                "INSERT INTO app.agent_tool_calls (run_id, sequence_no, tool_name, args_summary, result_summary, latency_ms, status)"
                        + " VALUES (?, 1, 'search_skus', '{\"sku\":\"1001\"}', '1 条结果', 50, 'SUCCESS')",
                liveId);
        ResponseEntity<Map> internalDetail = http.exchange(
                "/internal/v1/agent-runs/" + liveId,
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class);
        assertThat(internalDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) internalDetail.getBody().get("tool_calls")).hasSize(1);

        // 非法过滤参数同样 400（校验语义一致）
        ResponseEntity<Map> invalid = http.exchange(
                "/internal/v1/agent-runs?outcome=FOO",
                HttpMethod.GET,
                new HttpEntity<>(internalHeaders()),
                Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private static List<String> runIds(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("run_id")).toList();
    }

    private static HttpHeaders businessHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "business-admin");
        headers.setBasicAuth("business-admin", "business-admin-password");
        return headers;
    }

    private static HttpHeaders internalHeaders() {
        return headersWith("trusted-agent-service", "internal-agent-token-for-tests");
    }

    private static HttpHeaders headersWith(String operator, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", operator);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    private String insertRun(String slug, String status, String errorType, String runMode) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        boolean finished = !"RUNNING".equals(status);
        jdbc.update(
                """
                INSERT INTO app.agent_runs
                    (run_id, thread_id, agent_slug, agent_version, prompt_version, model, provider,
                     input_digest, status, error_type, latency_ms, token_usage, business_entity_type,
                     business_entity_id, run_mode, intent, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                "thread-mirror",
                slug,
                "1",
                null,
                "none",
                null,
                "ab".repeat(32),
                status,
                errorType,
                finished ? 1234 : null,
                "{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}",
                "PROCUREMENT_TICKET",
                "42",
                runMode,
                "purchase-inquiry",
                now().minusHours(1),
                finished ? now().minusHours(1).plusSeconds(30) : null);
        return runId;
    }

    private void insertDefinition(
            String slug, String name, int version, String status, boolean enabled,
            String activatedBy, OffsetDateTime activatedAt, boolean allowWrite, List<String> tools) {
        jdbc.update(
                """
                INSERT INTO app.agent_definitions
                    (agent_slug, name, description, system_prompt, prompt_version, model_ref,
                     enabled, version, status, activated_by, activated_at, allow_write,
                     guard_exemptions, output_schema, tool_whitelist, input_format)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, NULL, ?::jsonb, 'NATURAL_LANGUAGE')
                """,
                slug,
                name,
                "测试定义",
                "你是测试 Agent。",
                "t12-v1",
                "app.agent",
                enabled,
                version,
                status,
                activatedBy,
                activatedAt,
                allowWrite,
                tools.isEmpty() ? "[]"
                        : "[" + tools.stream().map(tool -> "\"" + tool + "\"").collect(java.util.stream.Collectors.joining(",")) + "]");
    }
}
