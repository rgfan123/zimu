package cn.zimu.fulfillment.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.wecom.WecomConnectionManager;
import cn.zimu.fulfillment.connector.wecom.WecomMessageDispatchHandler;
import cn.zimu.fulfillment.message.InterpretationInput;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.InterpretationWorker;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageInterpreter;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.InterpretationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * wecom-message-intake 13 一期整链验收（后端侧，可重复本地执行）。
 *
 * <p>接缝约定（spec Testing Decisions）：三条主验收接缝之一的管理 API + 长连接接收链路；
 * 企微推送通过公共接收接缝 {@link WecomMessageDispatchHandler#onFrame}（原 HTTP 加密回调已被
 * 长连接替换），模型只在 {@link MessageInterpreter} 边界使用测试替身，媒体网络与真实企微
 * 不在本地链路。全部断言通过公共 HTTP 管理 API 完成，不直写业务表冒充业务闭环（仅测试
 * 夹具为构造第三方便利数据使用 /internal 接口与定位查询）。
 *
 * <p>默认使用 Testcontainers PostgreSQL；设置 APP_TEST_DB_URL/APP_TEST_DB_USERNAME/
 * APP_TEST_DB_PASSWORD 时改用外部数据库（本地验收环境 zimu-accept-pg:15432），
 * 供 jar 重启恢复证据检查共享同一数据源。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.file-store.root=${java.io.tmpdir}/zimu-e2e-acceptance")
class WecomEndToEndAcceptanceTest {

    private static final String EXTERNAL_DB_URL = System.getenv("APP_TEST_DB_URL");
    private static final String EXTERNAL_DB_USERNAME = System.getenv("APP_TEST_DB_USERNAME");
    private static final String EXTERNAL_DB_PASSWORD = System.getenv("APP_TEST_DB_PASSWORD");

    private static final String BOT_ID = "AIBOT-E2E-ACCEPT";
    private static final String GROUP_ID = "CHAT-E2E-ACCEPT";
    private static final String OPERATOR = "e2e-acceptance-reviewer";
    private static final String ADMIN_PASSWORD = "e2e-acceptance-admin-password";

    /** 每次运行独立前缀：重复执行同一验收脚本不会与历史证据冲突。 */
    private static final String RUN_PREFIX = "ACC-" + System.currentTimeMillis();
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startPostgres() {
        if (EXTERNAL_DB_URL == null) {
            postgres.start();
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (EXTERNAL_DB_URL == null) {
            postgres.stop();
        }
    }

    @DynamicPropertySource
    static void acceptanceConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.message-worker.poll-ms", () -> "100");
        registry.add("app.message-worker.lease-seconds", () -> "30");
        registry.add("app.message-worker.backoff-seconds", () -> "5");
        registry.add("app.gateway.basic-auth.username", () -> OPERATOR);
        registry.add("app.gateway.basic-auth.password", () -> ADMIN_PASSWORD);
        if (EXTERNAL_DB_URL == null) {
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
            registry.add("spring.datasource.username", () -> postgres.getUsername());
            registry.add("spring.datasource.password", () -> postgres.getPassword());
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> EXTERNAL_DB_USERNAME);
            registry.add("spring.datasource.password", () -> EXTERNAL_DB_PASSWORD);
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter acceptanceTestInterpreter() {
            return AcceptanceInterpreterControl::next;
        }
    }

    /** 测试替身解释器：先注入结果队列，Worker 依次消费（与既有 message/order 测试同款接缝）。 */
    static final class AcceptanceInterpreterControl {

        private static final ArrayBlockingQueue<InterpretationResult> RESULTS = new ArrayBlockingQueue<>(64);

        static void queue(InterpretationResult result) {
            RESULTS.offer(result);
        }

        static InterpretationResult next(InterpretationInput ignored) {
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("e2e acceptance interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            RESULTS.clear();
        }
    }

    @MockitoBean
    private WecomConnectionManager wecomConnectionManager;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WecomMessageDispatchHandler wecomDispatchHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private InterpretationService interpretationService;

    @BeforeEach
    void setUp() {
        AcceptanceInterpreterControl.reset();
        when(wecomConnectionManager.respond(any(), any())).thenReturn(true);
        // 上一条用例（含历史失败运行的残留）遗留的任意状态任务退役：
        // 避免后续用例的 Worker 轮询把它们当成自己的任务消费（队列型测试替身逐条消费）
        jdbc.update(
                "UPDATE app.async_tasks SET status='SUCCEEDED', lease_owner=NULL, lease_until=NULL "
                        + "WHERE status IN ('PENDING', 'RUNNING', 'FINALIZING')");
        // 第三方便利数据依赖飞象来源映射：把种子渠道映射复制到 FEIXIANG（与既有运单验收同一夹具）
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                SELECT customer_id, 'FEIXIANG', 'FX-MEMBER-TRACKING'
                FROM app.customer_source_refs WHERE source_channel='WECOM'
                ON CONFLICT (source_channel, source_customer_ref) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'FEIXIANG', 'FX-PRODUCT-TRACKING', '子牧羊小腿', '标准箱', 1.000, sku_id, true
                FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
    }

    // ------------------------------------------------------------------
    // 1. 消息接收：模拟企微推送 → 固定回执「已接收」+ msgid 幂等
    // ------------------------------------------------------------------

    @Test
    void wecomPushSendsFixedReceiptOnceAndDuplicateMsgidPersistsOnce() {
        String messageId = nextId("MSG-RECEIPT");
        pushText(messageId, "客户要两盒子牧羊小腿");

        // 固定回执「已接收」：每次回调都应答同一固定文本（长连接 ack 语义，通道重试才能终止）
        List<JsonNode> receipts = captureReceipts("REQ-" + messageId);
        assertThat(receipts).hasSize(1);
        assertThat(receipts.getFirst().path("msgtype").asText()).isEqualTo("text");
        assertThat(receipts.getFirst().path("text").path("content").asText()).isEqualTo("已接收");

        // 同一 msgid 重复回调（通道重试）：业务落库只一次，回执仍应答同一固定文本（无副作用、不重复草稿）
        pushText(messageId, "客户要两盒子牧羊小腿");
        List<JsonNode> replayedReceipts = captureReceipts("REQ-" + messageId);
        assertThat(replayedReceipts).hasSize(2);
        assertThat(replayedReceipts.get(1).path("text").path("content").asText()).isEqualTo("已接收");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.channel_messages "
                                + "WHERE corp_id=? AND connection_id=? AND message_id=?",
                        Long.class,
                        BOT_ID,
                        "wecom-long-connection",
                        messageId))
                .isEqualTo(1);
        Map<String, Object> detail = channelMessageDetail(messageId);
        Long submissionId = Long.valueOf(detail.get("submission_id").toString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.message_submissions WHERE id=?",
                        Long.class,
                        submissionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE payload_ref=?",
                        Long.class,
                        "submission:" + submissionId))
                .isEqualTo(1);

        // 原始协议载荷作为证据落库，不丢失企微消息 ID 与会话
        assertThat(detail.get("message_id")).isEqualTo(messageId);
        assertThat(detail.get("chat_id")).isEqualTo(GROUP_ID);
        assertThat(detail.get("chat_type")).isEqualTo("group");
        assertThat(detail.get("sender_user_id")).isEqualTo("USER-FWD-E2E");
    }

    @Test
    void duplicateMsgidWithDifferentContentStillResolvesToTheFirstSubmission() {
        String messageId = nextId("MSG-DUP");
        pushText(messageId, "第一版内容");
        pushText(messageId, "通道重试的不同内容");

        Map<String, Object> detail = channelMessageDetail(messageId);
        assertThat(detail.get("content")).isEqualTo("第一版内容");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.message_submissions WHERE source_message_id=?",
                        Long.class,
                        Long.valueOf(detail.get("id").toString())))
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2. Worker 异步解释：版本化 MessageInterpretation + MessageIntent
    // ------------------------------------------------------------------

    @Test
    void workerProducesVersionedInterpretationWithIntent() {
        AcceptanceInterpreterControl.queue(customerOrderResult());
        String messageId = nextId("MSG-WORKER");
        pushText(messageId, "客户要两盒子牧羊小腿，月结");
        makeSubmissionTaskDue(messageId);
        pollWorker();

        Map<String, Object> submission = submissionOf(messageId);
        assertThat(submission.get("status")).isEqualTo("DRAFTED");
        assertThat(submission.get("current_intent")).isEqualTo("CUSTOMER_ORDER");
        List<?> interpretations = (List<?>) submission.get("interpretations");
        assertThat(interpretations).hasSize(1);
        Map<?, ?> first = (Map<?, ?>) interpretations.getFirst();
        assertThat(first.get("version")).isEqualTo(1);
        assertThat(first.get("intent")).isEqualTo("CUSTOMER_ORDER");
        assertThat(first.get("provider")).isEqualTo("test-provider");
        assertThat(first.get("model")).isEqualTo("test-model");
        assertThat(first.get("prompt_version")).isEqualTo("test-prompt-v1");

        // 重新解释追加版本，不覆盖历史
        AcceptanceInterpreterControl.queue(nonBusinessResult());
        ResponseEntity<Map> queued = http.exchange(
                "/api/v1/message-submissions/" + submission.get("id") + "/reinterpret",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("reinterpret-" + messageId, "req-reinterpret-" + messageId)),
                Map.class);
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.OK);
        makeSubmissionTaskDue(submission);
        pollWorker();

        Map<String, Object> refreshed = submissionOf(messageId);
        assertThat(((List<?>) refreshed.get("interpretations"))).hasSize(2);
        assertThat(refreshed.get("current_intent")).isEqualTo("NON_BUSINESS");
    }

    // ------------------------------------------------------------------
    // 3. ReviewCase：订单草稿 / 运单草稿各产生一个 OPEN 的 ORDER_OPS 待办
    // ------------------------------------------------------------------

    @Test
    void orderAndTrackingDraftsEachCreateExactlyOneOpenOrderOpsCase() throws Exception {
        AcceptanceInterpreterControl.queue(customerOrderResult());
        String orderMessageId = nextId("MSG-CASE-ORDER");
        pushText(orderMessageId, "客户要两盒子牧羊小腿");
        makeSubmissionTaskDue(orderMessageId);
        pollWorker();
        Map<String, Object> orderDraft = awaitOrderDraft(orderMessageId);
        Map<String, Object> orderCase = openCaseOf(orderDraft, "ORDER_DRAFT");
        assertThat(orderCase.get("responsible_team")).isEqualTo("ORDER_OPS");
        assertThat(orderCase.get("reason_code")).isEqualTo("WECOM_ORDER_DRAFT");
        assertThat(castStrings(orderCase.get("allowed_actions")))
                .containsExactly("CONFIRM_ORDER_DRAFT", "REJECT_ORDER_DRAFT");

        // 运单草稿：一条消息多行 → 逐行草稿，各一个 OPEN 事项
        String[] names = {nextName(), nextName()};
        List<Map<String, Object>> trackingLines = List.of(
                line(names[0], nextTrackingNo(), null, null, null),
                line(names[1], nextTrackingNo(), null, null, null));
        AcceptanceInterpreterControl.queue(trackingResult(trackingLines));
        createThirdPartyOrders(names);
        String trackingMessageId = nextId("MSG-CASE-TRACKING");
        pushTracking(trackingMessageId, trackingLines);
        makeSubmissionTaskDue(trackingMessageId);
        pollWorker();

        Map<String, Object> trackingSubmission = submissionOf(trackingMessageId);
        assertThat(trackingSubmission.get("current_intent")).isEqualTo("SUPPLIER_TRACKING");
        List<Map<String, Object>> drafts = trackingDraftsOf(trackingSubmission);
        assertThat(drafts).hasSize(2);
        // 运单事项按“恰好一个主体”约束只挂 provider_tracking_draft_id（message_submission_id 为空）
        long openCases = jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases rc "
                        + "JOIN app.provider_tracking_drafts d ON d.id = rc.provider_tracking_draft_id "
                        + "WHERE d.submission_id=? AND rc.status='OPEN' AND rc.responsible_team='ORDER_OPS'",
                Long.class,
                Long.valueOf(trackingSubmission.get("id").toString()));
        assertThat(openCases).isEqualTo(2);
        for (Map<String, Object> draft : drafts) {
            Map<String, Object> reviewCase = openCaseOf(draft, "TRACKING_DRAFT");
            assertThat(reviewCase.get("responsible_team")).isEqualTo("ORDER_OPS");
            assertThat(reviewCase.get("reason_code")).isEqualTo("WECOM_TRACKING_DRAFT");
        }
    }

    // ------------------------------------------------------------------
    // 4. 订单确认：公共 API 修订草稿 → 确认成单 → CanonicalOrder/事件/版本/审计
    // ------------------------------------------------------------------

    @Test
    void orderDraftSupplementThenConfirmCreatesCanonicalOrderAndResolvesCase() {
        AcceptanceInterpreterControl.queue(customerOrderResult());
        String messageId = nextId("MSG-ORDER-CONFIRM");
        pushText(messageId, "客户要两盒子牧羊小腿，月结");
        makeSubmissionTaskDue(messageId);
        pollWorker();
        Map<String, Object> draft = awaitOrderDraft(messageId);

        // 修订草稿：数量 2 → 3
        ResponseEntity<Map> supplemented = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/supplement",
                Map.of(
                        "expected_revision", ((Number) draft.get("revision")).longValue(),
                        "receiver", Map.of(
                                "name", "张三",
                                "phone", "13800000000",
                                "province", "上海市",
                                "city", "上海市",
                                "district", "浦东新区",
                                "town", "测试街道",
                                "address", "测试路 1 号"),
                        "settlement_method", "MONTHLY",
                        "items", List.of(Map.of("line_no", 1, "quantity", 3))),
                "supplement-" + messageId,
                "req-supplement-" + messageId);
        assertThat(supplemented.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> revised = get("/api/v1/order-drafts/" + draft.get("id"));
        assertThat(revised.get("status")).isEqualTo("OPEN");
        assertThat(((Number) revised.get("revision")).longValue())
                .isEqualTo(((Number) draft.get("revision")).longValue() + 1);
        assertThat(castMapList(castMapList(revised.get("lines")).getFirst().get("sku_candidates")))
                .isNotEmpty();

        // 确认成单：幂等键 + 期望版本 + 操作员身份
        Map<String, Object> command = confirmationCommand(revised);
        String idempotencyKey = "confirm-order-" + messageId;
        String requestId = "req-confirm-order-" + messageId;
        ResponseEntity<Map> confirmed = postCommand(
                "/api/v1/order-drafts/" + revised.get("id") + "/confirm",
                command,
                idempotencyKey,
                requestId);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody())
                .containsEntry("status", "CONFIRMED")
                .containsEntry("confirmed_by", OPERATOR);
        String orderId = confirmed.getBody().get("confirmed_order_id").toString();
        String sourceOrderNo = revised.get("source_order_no").toString();

        // 同幂等键重放：返回原结果，不产生第二张订单
        ResponseEntity<Map> replayed = postCommand(
                "/api/v1/order-drafts/" + revised.get("id") + "/confirm",
                command,
                idempotencyKey,
                "req-confirm-order-replay-" + messageId);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody().get("confirmed_order_id")).isEqualTo(orderId);
        assertThat(canonicalOrders(sourceOrderNo)).containsEntry("total_elements", 1);

        // CanonicalOrder 事实
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        assertThat(order)
                .containsEntry("source_channel", "WECOM")
                .containsEntry("source_ref", sourceOrderNo)
                .containsEntry("receiver_name", "张三")
                .containsEntry("order_status", "SKU_MAPPED");
        assertThat(castMapList(order.get("lines")).getFirst())
                .containsEntry("product_name", "子牧羊小腿")
                .containsEntry("requested_quantity", 3);

        // ReviewCase 解决 + 事件 + 版本 + 审计
        Map<String, Object> resolved = get("/api/v1/review-cases/" + revised.get("review_case_id"));
        assertThat(resolved)
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolved_by", OPERATOR);
        assertThat(castMap(resolved.get("resolution")))
                .containsEntry("resolution_type", "ORDER_DRAFT_CONFIRMED")
                .containsEntry("order_id", orderId);

        List<Map<String, Object>> timeline = castMapList(getList("/api/v1/orders/" + orderId + "/timeline"));
        assertThat(timeline.stream().map(item -> item.get("event_type_code")).toList())
                .contains("ORDER_RECEIVED", "SKU_MAPPED", "ORDER_DRAFT_CONFIRMED");
        assertThat(castMapList(getList("/api/v1/orders/" + orderId + "/versions"))).hasSizeGreaterThanOrEqualTo(2);

        assertThat(castMapList(get("/api/v1/audit-logs?request_id=" + requestId + "&size=20").get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "ORDER_DRAFT_CONFIRMED")
                        .containsEntry("order_id", orderId)
                        .containsEntry("operator", OPERATOR));
    }

    // ------------------------------------------------------------------
    // 5. 运单确认：单条确认 + 批量确认（逐行成功/失败不回滚）
    // ------------------------------------------------------------------

    @Test
    void trackingSingleConfirmAndBatchConfirmWithPerLineResults() throws Exception {
        String[] singleNames = {nextName()};
        createThirdPartyOrders(singleNames);
        List<Map<String, Object>> singleLine = List.of(line(singleNames[0], nextTrackingNo(), null, null, null));
        AcceptanceInterpreterControl.queue(trackingResult(singleLine));
        String singleMessageId = nextId("MSG-TRACKING-SINGLE");
        pushTracking(singleMessageId, singleLine);
        makeSubmissionTaskDue(singleMessageId);
        pollWorker();
        Map<String, Object> singleDraft = singleTrackingDraft(submissionOf(singleMessageId));
        String singleTrackingNo = singleLine.getFirst().get("tracking_no").toString();

        ResponseEntity<Map> single = postCommand(
                "/api/v1/tracking-drafts/" + singleDraft.get("id") + "/confirm",
                Map.of(
                        "expected_draft_revision", ((Number) singleDraft.get("revision")).longValue(),
                        "expected_case_version", ((Number) singleDraft.get("review_case_version")).longValue()),
                "confirm-tracking-single-" + singleMessageId,
                "req-confirm-tracking-single-" + singleMessageId);
        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> refreshed = get("/api/v1/tracking-drafts/" + singleDraft.get("id"));
        assertThat(refreshed.get("status")).isEqualTo("CONFIRMED");
        assertThat(refreshed.get("confirmed_by")).isEqualTo(OPERATOR);

        // SHIPPED 且无伪发货时间；Tracking.received_at 表示运单接收
        Map<String, Object> order = orderByReceiverName(singleNames[0]);
        Map<String, Object> shipment = shipmentOf(order);
        assertThat(shipment)
                .containsEntry("shipment_status", "SHIPPED")
                .containsEntry("shipped_at", null);
        Map<?, ?> tracking = (Map<?, ?>) shipment.get("tracking");
        assertThat(tracking.get("tracking_number")).isEqualTo(singleTrackingNo);
        assertThat(tracking.get("received_at")).isNotNull();
        Map<String, Object> caseDetail = get("/api/v1/review-cases/" + singleDraft.get("review_case_id"));
        assertThat(caseDetail.get("status")).isEqualTo("RESOLVED");
        assertThat(castMap(caseDetail.get("resolution")).get("resolution_type"))
                .isEqualTo("TRACKING_CONFIRMED");
        assertThat(castMapList(getList("/api/v1/orders/" + order.get("id") + "/timeline")).stream()
                        .map(item -> item.get("event_type_code"))
                        .toList())
                .contains("TRACKING_RECEIVED");
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=req-confirm-tracking-single-"
                                + singleMessageId
                                + "&size=20")
                        .get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "tracking_draft.confirm")
                        .containsEntry("operator", OPERATOR));

        // 批量确认：3 行，第二行给过期版本 → 逐行 2 成功 / 1 失败不回滚
        String[] batchNames = {nextName(), nextName(), nextName()};
        createThirdPartyOrders(batchNames);
        List<Map<String, Object>> batchLinesInput = List.of(
                line(batchNames[0], nextTrackingNo(), null, null, null),
                line(batchNames[1], nextTrackingNo(), null, null, null),
                line(batchNames[2], nextTrackingNo(), null, null, null));
        AcceptanceInterpreterControl.queue(trackingResult(batchLinesInput));
        String batchMessageId = nextId("MSG-TRACKING-BATCH");
        pushTracking(batchMessageId, batchLinesInput);
        makeSubmissionTaskDue(batchMessageId);
        pollWorker();
        List<Map<String, Object>> batchDrafts = trackingDraftsOf(submissionOf(batchMessageId));
        assertThat(batchDrafts).hasSize(3);

        List<Map<String, Object>> batchLines = new ArrayList<>();
        batchLines.add(batchLine(batchDrafts.get(0), "line-key-" + batchMessageId + "-1"));
        Map<String, Object> stale = batchLine(batchDrafts.get(1), "line-key-" + batchMessageId + "-2");
        stale.put("expected_draft_revision", 99L);
        batchLines.add(stale);
        batchLines.add(batchLine(batchDrafts.get(2), "line-key-" + batchMessageId + "-3"));

        ResponseEntity<Map> batched = postCommand(
                "/api/v1/tracking-drafts/batch-confirm",
                Map.of("lines", batchLines),
                "batch-confirm-" + batchMessageId,
                "req-batch-confirm-" + batchMessageId);
        assertThat(batched.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> batchBody = batched.getBody();
        assertThat(batchBody.get("success_count")).isEqualTo(2);
        assertThat(batchBody.get("failure_count")).isEqualTo(1);
        Map<?, ?> failed = ((List<?>) batchBody.get("results")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> !Boolean.TRUE.equals(item.get("success")))
                .findFirst()
                .orElseThrow();
        assertThat(failed.get("draft_id")).isEqualTo(batchDrafts.get(1).get("id"));
        assertThat(failed.get("business_code")).isEqualTo("VERSION_CONFLICT");

        assertThat(get("/api/v1/tracking-drafts/" + batchDrafts.get(0).get("id")).get("status"))
                .isEqualTo("CONFIRMED");
        assertThat(get("/api/v1/tracking-drafts/" + batchDrafts.get(2).get("id")).get("status"))
                .isEqualTo("CONFIRMED");
        assertThat(get("/api/v1/tracking-drafts/" + batchDrafts.get(1).get("id")).get("status"))
                .isEqualTo("OPEN");

        // 失败行取最新版本后用新幂等键单独补确认成功
        Map<String, Object> fresh = get("/api/v1/tracking-drafts/" + batchDrafts.get(1).get("id"));
        ResponseEntity<Map> retried = postCommand(
                "/api/v1/tracking-drafts/" + batchDrafts.get(1).get("id") + "/confirm",
                Map.of(
                        "expected_draft_revision", ((Number) fresh.get("revision")).longValue(),
                        "expected_case_version", ((Number) fresh.get("review_case_version")).longValue()),
                "confirm-batch-retry-" + batchMessageId,
                "req-confirm-batch-retry-" + batchMessageId);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/tracking-drafts/" + batchDrafts.get(1).get("id")).get("status"))
                .isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------
    // 6. 重启恢复：租约超时后 Worker 用幂等键恢复处理，不重复创建证据
    // ------------------------------------------------------------------

    @Test
    void leaseExpiredTaskResumesAndNeverDuplicatesEvidence() {
        AcceptanceInterpreterControl.queue(customerOrderResult());
        String messageId = nextId("MSG-RECOVERY");
        pushText(messageId, "重启恢复验证：客户要一盒子牧羊小腿");

        // 模拟“崩溃前已领取、租约超时”的任务（RUNNING + 过期租约，等价于进程重启）
        Map<String, Object> submission = submissionOf(messageId);
        Long submissionId = Long.valueOf(submission.get("id").toString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks "
                                + "WHERE payload_ref=? AND status='PENDING' AND attempts=0",
                        Long.class,
                        "submission:" + submissionId))
                .isEqualTo(1);
        jdbc.update(
                "UPDATE app.async_tasks SET status='RUNNING', lease_owner='crashed-worker-e2e', "
                        + "lease_until = statement_timestamp() - interval '1 second', "
                        + "next_run_at = statement_timestamp() - interval '1 second' "
                        + "WHERE payload_ref = ?",
                "submission:" + submissionId);

        // 新 Worker 进程（模拟重启）重新领取并处理；重复领取由幂等键收敛
        pollWorker();
        pollWorker();

        // 幂等重放：解释只追加一次、草稿与复核事项各只有一个
        Map<String, Object> refreshed = submissionOf(messageId);
        assertThat(refreshed.get("status")).isEqualTo("DRAFTED");
        assertThat(((List<?>) refreshed.get("interpretations"))).hasSize(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.order_drafts WHERE submission_id=?",
                        Long.class,
                        submissionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases rc "
                                + "JOIN app.order_drafts d ON d.id = rc.order_draft_id "
                                + "WHERE d.submission_id=? AND rc.status='OPEN'",
                        Long.class,
                        submissionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "submission:" + submissionId))
                .isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------
    // 消息推送接缝（长连接帧 → WecomMessageDispatchHandler）
    // ------------------------------------------------------------------

    private void pushText(String messageId, String content) {
        String plaintext = "{\"msgid\":\"" + messageId + "\","
                + "\"aibotid\":\"" + BOT_ID + "\","
                + "\"chatid\":\"" + GROUP_ID + "\","
                + "\"chattype\":\"group\","
                + "\"from\":{\"userid\":\"USER-FWD-E2E\"},"
                + "\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + content + "\"}}";
        dispatch(plaintext, messageId);
    }

    private void pushTracking(String messageId, List<Map<String, Object>> lines) {
        String plaintext = "{\"msgid\":\"" + messageId + "\","
                + "\"aibotid\":\"" + BOT_ID + "\","
                + "\"chatid\":\"" + GROUP_ID + "\","
                + "\"chattype\":\"group\","
                + "\"from\":{\"userid\":\"USER-FWD-E2E\"},"
                + "\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"第三方发货回传（验收批次）\"}}";
        dispatch(plaintext, messageId);
    }

    private void dispatch(String bodyJson, String messageId) {
        try {
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("cmd", "aibot_msg_callback");
            frame.putObject("headers").put("req_id", "REQ-" + messageId);
            frame.set("body", objectMapper.readTree(bodyJson));
            wecomDispatchHandler.onFrame("aibot_msg_callback", frame);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode receiptCaptor() {
        return any();
    }

    @SuppressWarnings("unchecked")
    private List<JsonNode> captureReceipts(String reqId) {
        org.mockito.ArgumentCaptor<JsonNode> captor = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(wecomConnectionManager, atLeast(0)).respond(eq(reqId), captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------------
    // Worker 手动驱动（验收环境 worker 由脚本关闭，本测试手动轮询）
    // ------------------------------------------------------------------

    private void pollWorker() {
        new InterpretationWorker(taskStore, interpretationService, true, 30, 60).poll();
    }

    /** 只把当前提交的解释任务置为到期：避免误消费其他提交遗留任务（队列型测试替身逐条消费）。 */
    private void makeSubmissionTaskDue(Map<String, Object> submission) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at = CURRENT_TIMESTAMP - interval '1 second' "
                        + "WHERE status = 'PENDING' AND payload_ref = ?",
                "submission:" + submission.get("id"));
    }

    private void makeSubmissionTaskDue(String messageId) {
        makeSubmissionTaskDue(submissionOf(messageId));
    }

    // ------------------------------------------------------------------
    // HTTP 助手（全部走公共管理 API）
    // ------------------------------------------------------------------

    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Object getList(String path) {
        ResponseEntity<Object> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Object.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> postCommand(String path, Map<String, Object> command, String idempotencyKey, String requestId) {
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(command, headers), Map.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(OPERATOR, ADMIN_PASSWORD);
        headers.set("X-Operator", OPERATOR);
        return headers;
    }

    private static HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(OPERATOR, ADMIN_PASSWORD);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", OPERATOR);
        return headers;
    }

    // ------------------------------------------------------------------
    // 查询助手
    // ------------------------------------------------------------------

    private Map<String, Object> channelMessage(String messageId) {
        List<Map<String, Object>> items = castMapList(get("/api/v1/channel-messages?size=200").get("items"));
        Map<String, Object> summary = items.stream()
                .filter(item -> messageId.equals(item.get("message_id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("channel message not found: " + messageId));
        return get("/api/v1/channel-messages/" + summary.get("id"));
    }

    private Map<String, Object> channelMessageDetail(String messageId) {
        return channelMessage(messageId);
    }

    private Map<String, Object> submissionOf(String messageId) {
        Map<String, Object> message = channelMessageDetail(messageId);
        return get("/api/v1/message-submissions/" + message.get("submission_id"));
    }

    private Map<String, Object> awaitOrderDraft(String messageId) {
        return awaitUntil(
                () -> {
                    try {
                        return orderDraftOf(messageId);
                    } catch (AssertionError ignored) {
                        return null;
                    }
                },
                value -> value != null,
                Duration.ofSeconds(15));
    }

    private Map<String, Object> orderDraftOf(String messageId) {
        Map<String, Object> submission = submissionOf(messageId);
        List<Map<String, Object>> drafts = castMapList(
                get("/api/v1/order-drafts?submission_id=" + submission.get("id") + "&size=20").get("items"));
        assertThat(drafts).hasSize(1);
        return drafts.getFirst();
    }

    private Map<String, Object> singleTrackingDraft(Map<String, Object> submission) {
        List<Map<String, Object>> drafts = trackingDraftsOf(submission);
        assertThat(drafts).hasSize(1);
        return drafts.getFirst();
    }

    private List<Map<String, Object>> trackingDraftsOf(Map<String, Object> submission) {
        return castMapList(get("/api/v1/tracking-drafts?submission_id=" + submission.get("id") + "&size=50").get("items"));
    }

    private Map<String, Object> openCaseOf(Map<String, Object> draft, String subjectType) {
        List<Map<String, Object>> items = castMapList(get("/api/v1/review-cases?status=OPEN&size=200").get("items"));
        List<Map<String, Object>> matches = items.stream()
                .filter(item -> subjectType.equals(item.get("subject_type")))
                .filter(item -> draft.get("id").toString().equals(String.valueOf(item.get("subject_id"))))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private Map<String, Object> canonicalOrders(String sourceOrderNo) {
        return get("/api/v1/orders?source_channel=WECOM&query="
                + URLEncoder.encode(sourceOrderNo, StandardCharsets.UTF_8)
                + "&size=20");
    }

    private Map<String, Object> orderByReceiverName(String receiverName) {
        List<Map<String, Object>> items = castMapList(get("/api/v1/orders?query=" + receiverName + "&size=20").get("items"));
        assertThat(items).as("按收货人定位订单: %s", receiverName).hasSize(1);
        return get("/api/v1/orders/" + items.getFirst().get("id"));
    }

    private Map<String, Object> shipmentOf(Map<String, Object> order) {
        ResponseEntity<Map[]> response = http.getForEntity("/api/v1/orders/" + order.get("id") + "/shipments", Map[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        return response.getBody()[0];
    }

    // ------------------------------------------------------------------
    // 第三方便利数据：内部建单 + 源订单导入确认（测试夹具，非业务闭环断言）
    // ------------------------------------------------------------------

    private void createThirdPartyOrders(String[] receiverNames) throws Exception {
        for (String name : receiverNames) {
            createThirdPartyOrder(name);
        }
    }

    private void createThirdPartyOrder(String receiverName) throws Exception {
        String sourceRef = nextId("TRK-ORDER");
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        String row = String.join(",", List.of(
                sourceRef,
                "FX-MEMBER-TRACKING",
                "子牧羊小腿",
                "FX-PRODUCT-TRACKING",
                sourceRef + "-LINE",
                "2.000",
                receiverName,
                "13800000000",
                "上海市浦东新区测试路1号",
                "2026-08-11 10:00:00",
                "",
                "",
                ""));
        byte[] source = ("\uFEFF" + header + "\r\n" + row + "\r\n").getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(source) {
            @Override
            public String getFilename() {
                return sourceRef + ".csv";
            }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = writeHeaders("source-import-" + sourceRef, "req-source-import-" + sourceRef);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> imported = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> confirmed = http.exchange(
                "/api/v1/import-batches/" + imported.getBody().get("id") + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("confirm-" + sourceRef, "req-confirm-" + sourceRef)),
                Map.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 解释结果构造（模型边界测试替身输出）
    // ------------------------------------------------------------------

    private static InterpretationResult customerOrderResult() {
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of(
                        "customer", Map.of("name", "子牧测试客户"),
                        "customer_ref", "WECOM-CUSTOMER-001",
                        "receiver", Map.of(
                                "name", "张三",
                                "phone", "13800000000",
                                "address", "上海市浦东新区测试路 1 号"),
                        "settlement_method", "MONTHLY",
                        "items", List.of(Map.of(
                                "product", "子牧羊小腿",
                                "spec", "500g/盒",
                                "unit", "盒",
                                "quantity", 2,
                                "source_sku_ref", "WECOM-SKU-JD-001"))),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);
    }

    private static InterpretationResult nonBusinessResult() {
        return new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("note", "验收改判非业务"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);
    }

    private static InterpretationResult trackingResult(List<Map<String, Object>> lines) {
        return new InterpretationResult(
                MessageIntent.SUPPLIER_TRACKING,
                Map.of("lines", lines),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);
    }

    private static Map<String, Object> line(
            String name, String trackingNo, String taskNo, String carrier, String shipment) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", name);
        line.put("tracking_no", trackingNo);
        line.put("task_no", taskNo);
        line.put("carrier", carrier);
        line.put("shipment", shipment);
        return line;
    }

    // ------------------------------------------------------------------
    // 确认命令构造
    // ------------------------------------------------------------------

    private Map<String, Object> confirmationCommand(Map<String, Object> draft) {
        Map<?, ?> customer = castMapList(draft.get("customer_candidates")).getFirst();
        Map<?, ?> line = castMapList(draft.get("lines")).getFirst();
        Map<?, ?> sku = castMapList(line.get("sku_candidates")).getFirst();
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("expected_revision", ((Number) draft.get("revision")).longValue());
        command.put("expected_case_version", ((Number) draft.get("review_case_version")).longValue());
        command.put("customer", Map.of("customer_id", customer.get("customer_id")));
        command.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800000000",
                "province", "上海市",
                "city", "上海市",
                "district", "浦东新区",
                "town", "测试街道",
                "address", "测试路 1 号"));
        command.put("settlement", Map.of(
                "method", "MONTHLY",
                "settlement_time", Instant.parse("2026-08-31T16:00:00Z").toString()));
        command.put("items", List.of(Map.of(
                "line_no", line.get("line_no"),
                "sku_id", sku.get("sku_id"),
                "quantity", 3)));
        command.put("remark", "已对照企微原始消息和主数据（一期整链验收）");
        return command;
    }

    private static Map<String, Object> batchLine(Map<String, Object> draft, String lineKey) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("draft_id", draft.get("id"));
        line.put("idempotency_key", lineKey);
        line.put("expected_draft_revision", ((Number) draft.get("revision")).longValue());
        line.put("expected_case_version", ((Number) draft.get("review_case_version")).longValue());
        return line;
    }

    // ------------------------------------------------------------------
    // 通用助手
    // ------------------------------------------------------------------

    private static String nextId(String kind) {
        return RUN_PREFIX + "-" + kind + "-" + SEQ.incrementAndGet();
    }

    /** 每次运行唯一的收货人姓名：重复执行验收脚本不与历史订单冲突。 */
    private static String nextName() {
        return "验收客" + SEQ.incrementAndGet();
    }

    /** 每次运行唯一的运单号（SF 前缀命中顺丰物流公司映射）。 */
    private static String nextTrackingNo() {
        int base = Math.abs(RUN_PREFIX.hashCode() % 900000000) + 100000000;
        return "SF" + (base + SEQ.incrementAndGet() * 1000);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStrings(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static <T> T awaitUntil(Supplier<T> supplier, Predicate<T> predicate, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T value;
        do {
            value = supplier.get();
            if (predicate.test(value)) {
                return value;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting acceptance result", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("条件在 " + timeout + " 内未满足，最后值: " + value);
    }
}
