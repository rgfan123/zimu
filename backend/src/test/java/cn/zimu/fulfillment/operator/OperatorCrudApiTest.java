package cn.zimu.fulfillment.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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

/** Issue #89: 运营人员与企微 userid 映射的公开 HTTP seam（写入侧 + 团队解析读取侧）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorCrudApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private InternalOperatorRepository operators;

    /** 同一 Testcontainers 容器在类内所有用例间共享：每例先清空登记簿，避免用例间数据污染。 */
    @BeforeEach
    void cleanRegistry() {
        operators.deleteAll();
    }

    @Test
    void createNormalizesAndPersistsAndRejectsDuplicateUserid() {
        ResponseEntity<Map> created = create(Map.of(
                "display_name", "  张三  ",
                "responsible_team", " order_ops ",
                "wecom_userid", " zhangsan ",
                "active", true), "operator-create-001");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("display_name", "张三");
        assertThat(created.getBody()).containsEntry("responsible_team", "ORDER_OPS");
        assertThat(created.getBody()).containsEntry("wecom_userid", "zhangsan");
        assertThat(created.getBody()).containsEntry("active", true);
        assertThat(created.getBody()).containsEntry("version", 0);

        // 同一企微 userid 重复登记 → 409，不静默覆盖、不产生第二条记录
        ResponseEntity<Map> duplicate = create(Map.of(
                "display_name", "李四",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-create-002");
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("business_code", "WECOM_USERID_EXISTS");

        // 未绑定 userid 的人员可以登记（需要推送时由解析 seam 给出明确提示，不静默跳过）
        ResponseEntity<Map> unbound = create(Map.of(
                "display_name", "王五",
                "responsible_team", "ORDER_OPS"), "operator-create-003");
        assertThat(unbound.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(unbound.getBody()).containsEntry("wecom_userid", null);
    }

    @Test
    void concurrentCreatesWithSameUseridYieldExactlyOneSuccessAndOneConflict() throws Exception {
        // 两个事务同时通过 existsByWecomUserid 预查后各自 flush，只有数据库唯一索引能兜底：
        // 恰一个创建成功（201），另一个稳定翻译为 409 WECOM_USERID_EXISTS，数据保持唯一。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<ResponseEntity<Map>> first = () -> {
                start.await();
                return create(Map.of(
                        "display_name", "并发甲",
                        "responsible_team", "ORDER_OPS",
                        "wecom_userid", "concurrent-userid"), "operator-concurrent-001");
            };
            Callable<ResponseEntity<Map>> second = () -> {
                start.await();
                return create(Map.of(
                        "display_name", "并发乙",
                        "responsible_team", "ORDER_OPS",
                        "wecom_userid", "concurrent-userid"), "operator-concurrent-002");
            };
            Future<ResponseEntity<Map>> future1 = pool.submit(first);
            Future<ResponseEntity<Map>> future2 = pool.submit(second);
            start.countDown();

            ResponseEntity<Map> response1 = future1.get(30, TimeUnit.SECONDS);
            ResponseEntity<Map> response2 = future2.get(30, TimeUnit.SECONDS);
            List<ResponseEntity<Map>> responses = List.of(response1, response2);

            assertThat(responses.stream()
                    .filter(response -> response.getStatusCode() == HttpStatus.CREATED).count())
                    .as("恰有一个并发创建成功")
                    .isEqualTo(1);
            ResponseEntity<Map> conflict = responses.stream()
                    .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("另一个并发请求必须返回 409"));
            assertThat(conflict.getBody()).containsEntry("business_code", "WECOM_USERID_EXISTS");

            // 数据唯一：登记簿里该 userid 只有一行，绝不静默覆盖
            assertThat(list().getBody()).satisfies(body ->
                    assertThat((List<?>) castMap(body).get("items")).hasSize(1));

            // 审计正确：只有成功那次写审计，失败请求无成功审计记录
            int audits = auditSummaries("req-operator-concurrent-001").size()
                    + auditSummaries("req-operator-concurrent-002").size();
            assertThat(audits).as("并发去重后只产生一条成功审计").isEqualTo(1);

            // 幂等正确：成功请求的同 key 重放返回相同结果，不再产生第二行
            String winnerKey = response1.getStatusCode() == HttpStatus.CREATED
                    ? "operator-concurrent-001" : "operator-concurrent-002";
            ResponseEntity<Map> winner = response1.getStatusCode() == HttpStatus.CREATED ? response1 : response2;
            ResponseEntity<Map> replayed = create(Map.of(
                    "display_name", winnerKey.equals("operator-concurrent-001") ? "并发甲" : "并发乙",
                    "responsible_team", "ORDER_OPS",
                    "wecom_userid", "concurrent-userid"), winnerKey);
            assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replayed.getBody()).isEqualTo(winner.getBody());
            assertThat(list().getBody()).satisfies(body ->
                    assertThat((List<?>) castMap(body).get("items")).hasSize(1));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidUseridAndTeamValuesAreRejectedWithFieldErrorsAndNothingPersisted() {
        ResponseEntity<Map> badUserid = create(Map.of(
                "display_name", "张三",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhang san"), "operator-invalid-001");
        assertThat(badUserid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(badUserid.getBody()).containsEntry("business_code", "OPERATOR_WECOM_USERID_INVALID");
        List<?> useridErrors = castList(badUserid.getBody().get("field_errors"));
        assertThat(useridErrors).isNotEmpty();
        assertThat(castMap(useridErrors.get(0))).containsEntry("field", "wecom_userid");

        // 超过 64 字符的 userid 拒绝（企微 userid 最长 64 字节）
        ResponseEntity<Map> longUserid = create(Map.of(
                "display_name", "张三",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "u" + "a".repeat(64)), "operator-invalid-002");
        assertThat(longUserid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(longUserid.getBody()).containsEntry("business_code", "OPERATOR_WECOM_USERID_INVALID");

        // 纯空白/超长责任团队拒绝
        ResponseEntity<Map> blankTeam = create(Map.of(
                "display_name", "张三",
                "responsible_team", "   "), "operator-invalid-003");
        assertThat(blankTeam.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blankTeam.getBody()).containsEntry("business_code", "OPERATOR_TEAM_INVALID");
        ResponseEntity<Map> longTeam = create(Map.of(
                "display_name", "张三",
                "responsible_team", "T".repeat(33)), "operator-invalid-004");
        assertThat(longTeam.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 姓名为空 → 422 字段级错误（写入规则统一走服务层，与企微群 chatid 校验同一模式）
        ResponseEntity<Map> blankName = create(Map.of(
                "display_name", "  ",
                "responsible_team", "ORDER_OPS"), "operator-invalid-005");
        assertThat(blankName.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blankName.getBody()).containsEntry("business_code", "OPERATOR_DISPLAY_NAME_INVALID");

        // 全部拒绝后没有任何记录落库
        assertThat(list().getBody()).satisfies(body ->
                assertThat((List<?>) castMap(body).get("items")).isEmpty());
    }

    @Test
    void listSupportsPaginationQueryAndTeamFilter() {
        create(Map.of("display_name", "张三", "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-list-001");
        create(Map.of("display_name", "李四", "responsible_team", "ORDER_OPS",
                "wecom_userid", "lisi"), "operator-list-002");
        create(Map.of("display_name", "王五", "responsible_team", "CUSTOMER_OPS"), "operator-list-003");

        // 团队筛选
        Map<String, Object> teamPage = list("responsible_team", "ORDER_OPS");
        assertThat(((List<?>) teamPage.get("items"))).hasSize(2);

        // 姓名/userid 模糊检索
        Map<String, Object> queryPage = list("query", "lisi");
        List<?> queryItems = (List<?>) queryPage.get("items");
        assertThat(queryItems).hasSize(1);
        assertThat(castMap(queryItems.get(0))).containsEntry("display_name", "李四");

        // 分页形状
        assertThat(teamPage).containsKeys("page", "size", "total_elements", "total_pages");
        assertThat(teamPage.get("total_elements")).isEqualTo(2);
    }

    @Test
    void patchUpdatesClearsUseridDeactivatesAndEnforcesVersionConcurrency() {
        ResponseEntity<Map> created = create(Map.of(
                "display_name", "张三",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-patch-000");
        String id = String.valueOf(created.getBody().get("id"));

        // 改名 + 换绑 userid（先 trim）
        ResponseEntity<Map> renamed = patch(id, Map.of(
                "expected_version", 0,
                "display_name", "张三丰",
                "wecom_userid", " zhangsanfeng "), "operator-patch-001");
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody()).containsEntry("display_name", "张三丰");
        assertThat(renamed.getBody()).containsEntry("wecom_userid", "zhangsanfeng");
        assertThat(renamed.getBody()).containsEntry("version", 1);

        // 空串显式清除绑定（null = 不改动）
        ResponseEntity<Map> cleared = patch(id, Map.of(
                "expected_version", 1, "wecom_userid", ""), "operator-patch-002");
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).containsEntry("wecom_userid", null);

        // 责任团队变更走归一化
        ResponseEntity<Map> teamChanged = patch(id, Map.of(
                "expected_version", 2, "responsible_team", " sku_ops "), "operator-patch-003");
        assertThat(teamChanged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(teamChanged.getBody()).containsEntry("responsible_team", "SKU_OPS");

        // 停用（不物理删除；解析 seam 不再返回停用人员）
        ResponseEntity<Map> deactivated = patch(id, Map.of(
                "expected_version", 3, "active", false), "operator-patch-004");
        assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deactivated.getBody()).containsEntry("active", false);

        // 过期版本 → 乐观锁冲突
        ResponseEntity<Map> stale = patch(id, Map.of(
                "expected_version", 0, "display_name", "改不动"), "operator-patch-005");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        // 空补丁 → 400
        ResponseEntity<Map> empty = patch(id, Map.of(
                "expected_version", 4), "operator-patch-006");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody()).containsEntry("business_code", "PATCH_EMPTY");

        // 未知 id → 404；GET 详情一致
        ResponseEntity<Map> missing = patch("999999", Map.of(
                "expected_version", 0, "display_name", "x"), "operator-patch-007");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> detail = http.getForEntity("/api/v1/operators/" + id, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsEntry("display_name", "张三丰");
        assertThat(detail.getBody()).containsEntry("active", false);
    }

    @Test
    void writesAreAuditedWithOperatorAndPayloadAndReplayIsIdempotent() {
        ResponseEntity<Map> first = create(Map.of(
                "display_name", "张三",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-audit-001");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 同一幂等键重放：返回相同结果，不产生第二条记录
        ResponseEntity<Map> replayed = create(Map.of(
                "display_name", "张三",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-audit-001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(list().getBody()).satisfies(body ->
                assertThat((List<?>) castMap(body).get("items")).hasSize(1));
        // same-key replay 不重复写审计：该 request_id 仍只有一条
        assertThat(auditSummaries("req-operator-audit-001")).hasSize(1);

        // 创建审计：列表投影给出操作/操作人；详情投影（/audit-logs/{id}）给出请求/响应体可追溯
        Map<String, Object> createAudit = firstAudit("req-operator-audit-001");
        assertThat(createAudit).containsEntry("operation", "operator.create");
        assertThat(createAudit).containsEntry("operator", "operator-api-test");
        Map<String, Object> createDetail = firstAuditDetail("req-operator-audit-001");
        assertThat(castMap(createDetail.get("request_payload")))
                .containsEntry("display_name", "张三")
                .containsEntry("responsible_team", "ORDER_OPS")
                .containsEntry("wecom_userid", "zhangsan");
        assertThat(castMap(createDetail.get("response_payload"))).containsEntry("display_name", "张三");

        // 更新审计沿用同一投影
        String id = String.valueOf(first.getBody().get("id"));
        ResponseEntity<Map> patched = patch(id, Map.of(
                "expected_version", 0, "wecom_userid", ""), "operator-audit-002");
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> updateAudit = firstAudit("req-operator-audit-002");
        assertThat(updateAudit).containsEntry("operation", "operator.update");
        Map<String, Object> updateDetail = firstAuditDetail("req-operator-audit-002");
        assertThat(castMap(castMap(updateDetail.get("request_payload")).get("body")))
                .containsEntry("wecom_userid", "");
        assertThat(castMap(updateDetail.get("response_payload"))).containsEntry("wecom_userid", null);
    }

    @Test
    void patchReplayWithSameKeyAfterMutationReturnsOriginalBodyOnceAndOneAudit() {
        ResponseEntity<Map> created = create(Map.of(
                "display_name", "张三",
                "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-replay-000");
        String id = String.valueOf(created.getBody().get("id"));

        // 首次 PATCH：换绑 userid。审计语义需要记录变更前 userid，但幂等键只能绑定
        // 稳定请求内容——若把读库得到的变更前状态混入幂等 payload，首次提交后重放会
        // 因 payload hash 变化被误判为同 key 不同请求（409）。
        ResponseEntity<Map> first = patch(id, Map.of(
                "expected_version", 0,
                "wecom_userid", "zhangsanfeng"), "operator-replay-001");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("wecom_userid", "zhangsanfeng");
        assertThat(first.getBody()).containsEntry("version", 1);

        // 首次变更已提交后，用同一 Idempotency-Key 重放完全相同的 PATCH（含相同的
        // expected_version）：必须返回首次执行的原始响应体，不冲突、不再执行写。
        ResponseEntity<Map> replayed = patch(id, Map.of(
                "expected_version", 0,
                "wecom_userid", "zhangsanfeng"), "operator-replay-001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());

        // 重放不重复执行业务写：版本不再次 +1；只产生一条审计记录
        ResponseEntity<Map> detail = http.getForEntity("/api/v1/operators/" + id, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsEntry("wecom_userid", "zhangsanfeng");
        assertThat(detail.getBody()).containsEntry("version", 1);
        assertThat(auditSummaries("req-operator-replay-001")).hasSize(1);
    }

    @Test
    void teamResolutionEndpointReturnsStructuredDiagnostics() {
        create(Map.of("display_name", "张三", "responsible_team", "ORDER_OPS",
                "wecom_userid", "zhangsan"), "operator-resolve-001");
        create(Map.of("display_name", "王五", "responsible_team", "ORDER_OPS"), "operator-resolve-002");
        create(Map.of("display_name", "李四", "responsible_team", "CUSTOMER_OPS",
                "wecom_userid", "lisi"), "operator-resolve-003");

        // 团队名大小写/空白由服务端归一化
        ResponseEntity<Map> resolution = http.getForEntity(
                "/api/v1/operator-team-resolutions?responsible_team=order_ops", Map.class);
        assertThat(resolution.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolution.getBody()).containsEntry("responsible_team", "ORDER_OPS");
        assertThat(resolution.getBody()).containsEntry("status", "PARTIALLY_BOUND");
        assertThat(resolution.getBody()).containsEntry("pushable", false);
        assertThat(stringList(resolution.getBody().get("pushable_user_ids"))).containsExactly("zhangsan");
        assertThat(stringList(resolution.getBody().get("unbound_member_names"))).containsExactly("王五");

        // 无人员团队 → 结构化 NO_MEMBERS（200），不是异常
        ResponseEntity<Map> noMembers = http.getForEntity(
                "/api/v1/operator-team-resolutions?responsible_team=SKU_OPS", Map.class);
        assertThat(noMembers.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noMembers.getBody()).containsEntry("status", "NO_MEMBERS");
        assertThat(noMembers.getBody()).containsEntry("pushable", false);

        // 空白团队 → 明确 422（含 % 的 URL 用 URI 对象传递，避免模板处理器二次编码）
        ResponseEntity<Map> blank = http.getForEntity(
                URI.create("/api/v1/operator-team-resolutions?responsible_team=%20%20"), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blank.getBody()).containsEntry("business_code", "OPERATOR_TEAM_REQUIRED");

        // 强制全员可推送（require_pushable=true）：存在未绑定人员 → fail-closed 422，消息含团队、
        // 未绑定人员名单与「先与机器人打招呼」的可操作应对；普通诊断仍是 200 结构化结果
        ResponseEntity<Map> forced = http.getForEntity(
                "/api/v1/operator-team-resolutions?responsible_team=order_ops&require_pushable=true", Map.class);
        assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(forced.getBody()).containsEntry("business_code", "OPERATOR_TEAM_NOT_PUSHABLE");
        assertThat(String.valueOf(forced.getBody().get("message")))
                .contains("ORDER_OPS", "王五", "打招呼");

        // 全员已绑定的团队在 require_pushable=true 下仍 200 返回结构化可推送结果
        ResponseEntity<Map> forcedOk = http.getForEntity(
                "/api/v1/operator-team-resolutions?responsible_team=customer_ops&require_pushable=true",
                Map.class);
        assertThat(forcedOk.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forcedOk.getBody()).containsEntry("pushable", true);
        assertThat(stringList(forcedOk.getBody().get("pushable_user_ids"))).containsExactly("lisi");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ResponseEntity<Map> create(Map<String, Object> body, String idempotencyKey) {
        return http.exchange(
                "/api/v1/operators",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(idempotencyKey)),
                Map.class);
    }

    private ResponseEntity<Map> patch(String id, Map<String, Object> body, String idempotencyKey) {
        return http.exchange(
                "/api/v1/operators/" + id,
                HttpMethod.PATCH,
                new HttpEntity<>(body, writeHeaders(idempotencyKey)),
                Map.class);
    }

    private ResponseEntity<Map> list() {
        return http.getForEntity("/api/v1/operators", Map.class);
    }

    private Map<String, Object> list(String name, String value) {
        return http.getForObject("/api/v1/operators?" + name + "=" + value, Map.class);
    }

    private List<?> auditSummaries(String requestId) {
        Map<?, ?> audits = http.getForObject("/api/v1/audit-logs?request_id=" + requestId, Map.class);
        return (List<?>) audits.get("items");
    }

    private Map<String, Object> firstAudit(String requestId) {
        return castMap(auditSummaries(requestId).getFirst());
    }

    /** 审计详情投影（/audit-logs/{id}）：与既有审计 seam 一致，请求/响应体只在详情返回。 */
    private Map<String, Object> firstAuditDetail(String requestId) {
        return http.getForObject("/api/v1/audit-logs/" + firstAudit(requestId).get("id"), Map.class);
    }

    private static HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", "operator-api-test");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<?> castList(Object value) {
        return (List<?>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }
}
