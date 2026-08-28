package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.card.source.BatchConfirmedCardSource;
import cn.zimu.fulfillment.connector.wecom.card.source.BusinessFollowUpDraftCardSource;
import cn.zimu.fulfillment.connector.wecom.card.source.BusinessFollowUpResultCardSource;
import cn.zimu.fulfillment.connector.wecom.card.source.CardDeepLinks;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 通用业务卡投递管道验收（V55，真实 PostgreSQL）。
 *
 * <p>重点验证三条围栏语义，它们决定了「会不会给人发重复卡 / 过期卡」：
 * <ul>
 *   <li>同一 (域, 实体, 版本) 重复入队幂等——业务侧多次触发同一事件是常态；</li>
 *   <li>UNKNOWN 行不得被再次认领发送——外部效果未知时重发会让人收到两张一样的卡；</li>
 *   <li>未配置路由不发卡且不报错——没配群是部署选择，不该让业务写失败。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            // Worker 关掉：本用例验证围栏语义，不验证轮询
            "app.wecom-business-card.enabled=false",
            "app.wecom-business-card.base-url=https://zimu.test",
            "app.wecom-business-card.routes.alert.type=GROUP",
            "app.wecom-business-card.routes.alert.chat-id=wr-alert-group"
        })
class WecomBusinessCardPipelineIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WecomBusinessCardStore store;

    @Autowired
    private WecomBusinessCardEnqueuer enqueuer;

    @Autowired
    private WecomBusinessCardSourceRegistry registry;

    @Autowired
    private WecomBusinessCardRouteProperties routes;

    @Autowired
    private MessageSubmissionService submissions;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void reset() {
        // 投递行与告警都要清：扫描用例断言「扫得到 / 扫不到」，上一个用例遗留的告警会污染结果
        jdbc.execute("TRUNCATE app.wecom_business_cards RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE app.operational_alerts RESTART IDENTITY CASCADE");
        jdbc.execute(
                "TRUNCATE app.business_followup_approvals, app.business_followup_draft_versions,"
                        + " app.business_followups RESTART IDENTITY CASCADE");
    }

    @Test
    void v55TableExistsWithTheExpectedFence() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_schema='app' AND table_name='wecom_business_cards'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void allCardDomainsAreRegistered() {
        // 名册是棘轮：新增一个域必须在这里显式登记。忘了登记的表现是
        // 「这类卡从来不发」——没有任何报错，只能靠人发现。
        assertThat(registry.domains())
                .containsExactlyInAnyOrder(
                        ReviewCaseCard.DOMAIN,
                        OperationalAlertCard.DOMAIN,
                        BatchConfirmedCard.DOMAIN,
                        JdOutboundFailureCard.DOMAIN,
                        BusinessFollowUpDraftCard.DOMAIN,
                        BusinessFollowUpResultCard.DOMAIN,
                        PreShipConfirmCard.DOMAIN,
                        BatchPreShipConfirmCard.DOMAIN,
                        ShipmentResultCard.DOMAIN);
    }

    @Test
    void readyFollowupDraftRoutesToTheDesignatedActiveOperatorAndIsScannedOnce() {
        FollowupFixture fixture = seedReadyFollowup();
        WecomBusinessCardSource source = registry.find(BusinessFollowUpDraftCard.DOMAIN).orElseThrow();

        assertThat(source.route(fixture.followupId())).get()
                .satisfies(route -> {
                    assertThat(route.type()).isEqualTo(WecomBusinessCardSource.RouteType.SINGLE);
                    assertThat(route.chatId()).isEqualTo(fixture.reviewerUserid());
                });
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20))
                .contains(WecomTaskId.ofVersion(
                        BusinessFollowUpDraftCard.DOMAIN, fixture.followupId(), 1));
        enqueuer.enqueue(WecomTaskId.ofVersion(
                BusinessFollowUpDraftCard.DOMAIN, fixture.followupId(), 1));

        ObjectNodePair cards = renderBothRoutes(source, fixture.followupId(), 1);
        assertThat(cards.single().toString())
                .contains("BF-", "张三", "已核对，等待 +1", "13800138000", "上海市浦东新区某路 1 号", "原切牛排")
                .doesNotContain("不应上卡的自由文本");
        assertThat(cards.group().toString())
                .contains("已脱敏", "张*", "138****8000", "上海市浦东新区某路…")
                .doesNotContain("张三", "13800138000", "某路 1 号");

        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='NEEDS_INPUT'"
                        + " WHERE followup_id=? AND version=1",
                fixture.followupId());
        jdbc.update("UPDATE app.business_followups SET stage='NEEDS_INPUT' WHERE id=?", fixture.followupId());
        assertThat(source.render(fixture.followupId(), 1)).get()
                .as("NEEDS_INPUT 草稿必须通知 +1，但不得暴露确认回调")
                .satisfies(card -> assertThat(card.toString())
                        .contains("不可确认", "需要补充")
                        .doesNotContain(BusinessFollowUpDraftCard.CONFIRM_BUTTON_KEY));

        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20))
                .doesNotContain(WecomTaskId.ofVersion(
                        BusinessFollowUpDraftCard.DOMAIN, fixture.followupId(), 1));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema='app' AND table_name='wecom_business_cards'"
                                + " AND column_name IN ('content','body','payload')",
                        Integer.class))
                .as("通用 outbox 不持久化卡片正文")
                .isZero();

        jdbc.update(
                "UPDATE app.internal_operators SET active=FALSE WHERE id=?",
                fixture.reviewerOperatorId());
        assertThat(source.route(fixture.followupId()))
                .as("指定 +1 停用后，单聊或配置群聊都不应绕过人员门禁")
                .isEmpty();
    }

    @Test
    void resultSourceRendersOnlyAfterTheApprovalDecisionWasApplied() {
        FollowupFixture fixture = seedReadyFollowup();
        long approvalId = seedConfirmApproval(fixture, "confirm-1");
        WecomBusinessCardSource source = registry.find(BusinessFollowUpResultCard.DOMAIN).orElseThrow();

        assertThat(source.render(approvalId, 1)).isEmpty();
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='CONFIRMED'"
                        + " WHERE followup_id=? AND version=1",
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followups SET stage='CONFIRMED',"
                        + " current_confirmed_draft_version=1 WHERE id=?",
                fixture.followupId());

        assertThat(source.render(approvalId, 1))
                .as("草稿状态已改但 Approval 尚未应用时仍不得播报")
                .isEmpty();
        jdbc.update(
                "UPDATE app.business_followup_approvals"
                        + " SET application_status='APPLIED', applied_at=CURRENT_TIMESTAMP"
                        + " WHERE id=?",
                approvalId);
        assertThat(source.render(approvalId, 1)).get()
                .satisfies(card -> assertThat(card.toString())
                        .contains("followup-result_" + approvalId + "_v1", "已确认", "跟进审批人"));
        jdbc.update(
                "UPDATE app.business_followups SET stage='PENDING_APPROVAL' WHERE id=?",
                fixture.followupId());
        assertThat(source.render(approvalId, 1))
                .as("结果卡由不可变 Approval 和草稿处置结果决定，不跟随 Follow-up 当前阶段漂移")
                .isPresent();
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20))
                .contains(WecomTaskId.ofVersion(BusinessFollowUpResultCard.DOMAIN, approvalId, 1));
        jdbc.update(
                "UPDATE app.business_followup_approvals"
                        + " SET application_status='FAILED', application_failure_code='FOLLOWUP_APPROVAL_APPLY_FAILED',"
                        + " applied_at=NULL WHERE id=?",
                approvalId);
        assertThat(source.render(approvalId, 1)).get()
                .satisfies(card -> assertThat(card.toString())
                        .contains("处理失败", "FOLLOWUP_APPROVAL_APPLY"));
    }

    /** 终态播报卡缺深链只是少一个跳转，不再是发不发得出去的前提。 */
    @Test
    void followUpResultRendersWithoutADeepLinkBase() {
        FollowupFixture fixture = seedReadyFollowup();
        long approvalId = seedConfirmApproval(fixture, "confirm-no-base-url");
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='CONFIRMED'"
                        + " WHERE followup_id=? AND version=1",
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followup_approvals"
                        + " SET application_status='APPLIED', applied_at=CURRENT_TIMESTAMP"
                        + " WHERE id=?",
                approvalId);
        WecomBusinessCardSource source =
                new BusinessFollowUpResultCardSource(jdbc, routes, new CardDeepLinks("  "));

        assertThat(source.route(approvalId))
                .as("路由由指定审批人决定，与深链配置无关")
                .isPresent();
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20))
                .contains(WecomTaskId.ofVersion(BusinessFollowUpResultCard.DOMAIN, approvalId, 1));
        assertThat(source.render(approvalId, 1)).get().satisfies(card -> {
            assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
            assertThat(card.has("card_action")).isFalse();
            assertThat(card.path("task_id").asText())
                    .isEqualTo("followup-result_" + approvalId + "_v1");
            assertThat(card.path("button_list").get(0).path("key").asText())
                    .isEqualTo(BusinessFollowUpResultCard.ACKNOWLEDGE_BUTTON_KEY);
            assertThat(card.path("main_title").path("title").asText()).isEqualTo("客户跟进审批已确认");
        });
    }

    /**
     * 草稿卡的深链门禁保持原样：它的「回后台补充」是带参动作，没有后台地址就真的做不成。
     *
     * <p>终态播报卡（{@code followup-result}）不再列在这里——它已改成 button_interaction，
     * 见 {@link #followUpResultRendersWithoutADeepLinkBase()}。
     */
    @Test
    void draftSourceFailsClosedWhenNoDeepLinkBaseIsConfigured() {
        WecomBusinessCardSource draftSource =
                new BusinessFollowUpDraftCardSource(jdbc, mapper, routes, new CardDeepLinks("  "));

        assertThat(draftSource.route(1)).isEmpty();
        assertThat(draftSource.pending(OffsetDateTime.now().minusHours(1), 20)).isEmpty();
    }

    /**
     * 三张播报卡改成 {@code button_interaction} 之后的核心断言：**缺 base-url 也照发**。
     *
     * <p>这正是积压卡片能自愈的前提——渲染不再依赖一个本部署永远配不出来的 https 深链，
     * 任务被重新驱动起来就能渲染成功。同时验证 {@code task_id} 的域与版本语义没变：
     * 生产库里已有 {@code batch_49/50/51} 这类 task_id，改卡型不得破坏这个契约。
     */
    @Test
    void batchConfirmedRendersAsButtonInteractionWithOrWithoutABaseUrl() {
        String suffix = java.util.UUID.randomUUID().toString();
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref,
                     status, uploaded_by, processed_at)
                VALUES (?, 'SOURCE_ORDER', 'CAISHIXIAN', 'TEST', '1', ?, 'test.xlsx',
                        repeat('a', 64), ?, 'COMPLETED', 'tester', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "BATCH-CARD-" + suffix,
                "fingerprint-" + suffix,
                "test://" + suffix);
        WecomBusinessCardSource missingBase =
                new BatchConfirmedCardSource(jdbc, routes, new CardDeepLinks("  "));
        WecomBusinessCardSource configuredBase =
                new BatchConfirmedCardSource(jdbc, routes, new CardDeepLinks("https://zimu.test/"));

        assertThat(missingBase.render(batchId, 1))
                .as("没配深链基址也必须发得出去——播报卡的信息本身不依赖跳转")
                .get()
                .satisfies(card -> {
                    assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
                    assertThat(card.has("card_action"))
                            .as("交互卡的 card_action 是可选的，没有基址就不要编一个出来")
                            .isFalse();
                    assertThat(card.path("task_id").asText())
                            .as("域与版本语义不变：生产库里已有 batch_<id>_v<version> 这类 task_id")
                            .isEqualTo("batch_" + batchId + "_v1");
                    assertThat(card.path("button_list")).hasSize(1);
                    assertThat(card.path("button_list").get(0).path("key").asText())
                            .isEqualTo(BatchConfirmedCard.ACKNOWLEDGE_BUTTON_KEY);
                    assertThat(card.path("button_list").get(0).path("text").asText())
                            .isEqualTo("知道了");
                    // 播报卡的信息完整性：没人点按钮时这些字段仍然把事情说清楚了
                    assertThat(card.path("main_title").path("title").asText())
                            .isEqualTo("整批确认已完成");
                    assertThat(card.path("horizontal_content_list")).hasSize(3);
                });
        assertThat(missingBase.pending(OffsetDateTime.now().minusHours(1), 20))
                .as("扫描不再被深链配置挡住")
                .extracting(WecomTaskId::entityId)
                .contains(batchId);
        assertThat(missingBase.route(batchId))
                .as("路由同样不该被深链配置挡住")
                .isEqualTo(routes.resolve(BatchConfirmedCard.DOMAIN));
        assertThat(configuredBase.render(batchId, 1)).get().satisfies(card -> {
            assertThat(card.path("card_type").asText()).isEqualTo("button_interaction");
            assertThat(card.path("card_action").path("url").asText())
                    .as("将来公网入口上了 HTTPS，配上 base-url 即自动恢复跳转")
                    .startsWith("https://zimu.test/fulfillment/shipments?batch_no=");
        });
    }

    /**
     * 生产积压卡片（batch_49/50/51）的自愈边界，两半都要如实钉住。
     *
     * <p><b>能的一半</b>：投递行停在 FAILED——那是旧代码 {@code text_notice} 缺 card_action
     * 抛异常留下的痕迹。投递围栏允许 FAILED 再发一次，而现在的渲染已经不依赖深链，会成功。
     *
     * <p><b>不能的一半</b>：这些实体不会被扫描重新发现——{@code pending()} 靠
     * {@code LEFT JOIN wecom_business_cards ... c.id IS NULL} 排除已建卡的实体。
     * 而它们的 {@code async_tasks} 行在耗尽 3 次尝试后是终态 {@code FAILED}，
     * {@code claim()} 只捡 PENDING 与租约过期的 RUNNING/FINALIZING。
     * 也就是说：光换代码不会让它们自己回来，需要一次把任务重新置为 PENDING 的清理动作。
     */
    @Test
    void aCardLeftFailedByTheOldRenderCanBeResentButIsNoLongerRediscoveredByScanning() {
        String suffix = java.util.UUID.randomUUID().toString();
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref,
                     status, uploaded_by, processed_at)
                VALUES (?, 'SOURCE_ORDER', 'CAISHIXIAN', 'TEST', '1', ?, 'test.xlsx',
                        md5(?) || md5(?), ?, 'COMPLETED', 'tester', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "BATCH-STUCK-" + suffix,
                "fingerprint-" + suffix,
                // content_sha256 唯一：同类里另一个用例已占用 repeat('a', 64)
                suffix,
                suffix,
                "test://" + suffix);
        WecomBusinessCard card = store.create(
                WecomTaskId.ofVersion(BatchConfirmedCard.DOMAIN, batchId, 1),
                WecomBusinessCardSource.RouteType.GROUP,
                "wr-batch-group");
        // 复现生产形态：旧渲染抛异常 → recordRetryable 把投递行落成 FAILED
        jdbc.update(
                "UPDATE app.wecom_business_cards"
                        + " SET status='FAILED', last_error='WECOM_CARD_RENDER_FAILED' WHERE id=?",
                card.id());
        // 生产没配 base-url，自愈判定必须在「没有深链」这个前提下做
        WecomBusinessCardSource source =
                new BatchConfirmedCardSource(jdbc, routes, new CardDeepLinks(""));

        assertThat(store.beginSend(card.id(), 1).action())
                .as("FAILED 的投递行允许再发一次——自愈的前提在围栏这边是成立的")
                .isEqualTo(WecomBusinessCardStore.CardSendAction.SEND);
        assertThat(source.render(batchId, 1))
                .as("新渲染不再依赖深链，重新驱动后就能发出去")
                .isPresent();
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20))
                .as("但扫描不会重新发现它：投递行已存在，pending() 按设计跳过")
                .extracting(WecomTaskId::entityId)
                .doesNotContain(batchId);
    }

    @Test
    void configuredRouteResolvesAndUnconfiguredOnesStaySilent() {
        assertThat(routes.resolve(OperationalAlertCard.DOMAIN)).get()
                .extracting(WecomBusinessCardSource.Route::chatId)
                .isEqualTo("wr-alert-group");
        // 未配置即不发；这是部署选择，不该抛异常也不该有告警噪声
        assertThat(routes.resolve(ReviewCaseCard.DOMAIN)).isEmpty();
    }

    @Test
    void enqueueIsIdempotentForTheSameEntityVersion() {
        WecomTaskId taskId = WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 77, 0);

        Optional<Long> first = enqueuer.enqueue(taskId);
        Optional<Long> second = enqueuer.enqueue(taskId);

        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_business_cards"
                                + " WHERE card_domain=? AND entity_id=? AND entity_version=?",
                        Integer.class,
                        taskId.domain(),
                        taskId.entityId(),
                        taskId.version()))
                .isEqualTo(1);
    }

    @Test
    void unroutableDomainIsSkippedWithoutFailingTheBusinessWrite() {
        // review 域未配 chat-id：不建行、不抛异常
        Optional<Long> enqueued = enqueuer.enqueue(WecomTaskId.ofVersion(ReviewCaseCard.DOMAIN, 1, 0));
        assertThat(enqueued).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_business_cards", Integer.class))
                .isZero();
    }

    @Test
    void newerEntityVersionGetsItsOwnCardRow() {
        enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 88, 0));
        enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 88, 1));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_business_cards WHERE entity_id = 88",
                        Integer.class))
                .isEqualTo(2);
    }

    @Test
    void sendPermitIsGrantedOnceThenSkippedWhileSending() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 5, 0)).orElseThrow();

        assertThat(store.beginSend(cardId).action()).isEqualTo(WecomBusinessCardStore.CardSendAction.SEND);
        // 已在 SENDING：并发的第二个 Worker 不得重复发送
        assertThat(store.beginSend(cardId).action())
                .isEqualTo(WecomBusinessCardStore.CardSendAction.SKIP_HANDLED);
    }

    @Test
    void unknownOutcomeForbidsBlindResend() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 6, 0)).orElseThrow();
        store.beginSend(cardId);
        store.recordUnknown(cardId, "WECOM_ACK_TIMEOUT");

        // ACK 超时的外部效果未知：卡片可能已经送达，重发会让人收到两张
        assertThat(store.beginSend(cardId).action())
                .isEqualTo(WecomBusinessCardStore.CardSendAction.SKIP_UNKNOWN);
        assertThat(store.load(cardId).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void reclaimedSendingAttemptBecomesUnknownAndStaleFailureCannotOverwriteIt() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 61, 0)).orElseThrow();
        assertThat(store.beginSend(cardId, 1).action())
                .isEqualTo(WecomBusinessCardStore.CardSendAction.SEND);

        // 模拟进程在外部提交附近崩溃：任务租约过期后第二次 claim。
        assertThat(store.beginSend(cardId, 2).action())
                .isEqualTo(WecomBusinessCardStore.CardSendAction.SKIP_UNKNOWN);
        assertThat(store.load(cardId).status()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM app.wecom_business_cards WHERE id = ?",
                        String.class,
                        cardId))
                .isEqualTo(WecomBusinessCardStore.RESTART_OUTCOME_UNKNOWN);

        // 旧进程的迟到失败不能把 UNKNOWN 降级成可重试 FAILED。
        store.recordRetryable(cardId, "WECOM_CONNECTION_RESET");
        assertThat(store.load(cardId).status()).isEqualTo("UNKNOWN");
    }

    @Test
    void lateAcknowledgementCanReconcileAnUnknownCardToSent() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 62, 0)).orElseThrow();
        store.beginSend(cardId, 1);
        store.beginSend(cardId, 2);

        store.recordSent(cardId, "req-late-ack", java.time.Instant.now());

        assertThat(store.load(cardId).status()).isEqualTo("SENT");
    }

    @Test
    void retryableFailureBecomesSendableAgain() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 7, 0)).orElseThrow();
        store.beginSend(cardId);
        store.recordRetryable(cardId, "WECOM_RATE_LIMITED");

        WecomBusinessCardStore.CardSendPermit permit = store.beginSend(cardId);
        assertThat(permit.action()).isEqualTo(WecomBusinessCardStore.CardSendAction.SEND);
        assertThat(permit.attempt()).isEqualTo(2);
    }

    @Test
    void sentCardIsTheOnlyOneThatCanAuthorizeACallback() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 8, 0)).orElseThrow();
        String taskId = store.load(cardId).taskId();
        assertThat(taskId).matches("alert_8_v0_[0-9a-f]{32}");
        assertThat(taskId).isNotEqualTo("alert_8_v0");

        // 未送达的卡不该有人能点
        assertThat(store.findSentByTaskId(taskId)).isEmpty();

        store.beginSend(cardId);
        store.recordSent(cardId, "req-1", java.time.Instant.now());
        assertThat(store.findSentByTaskId(taskId)).isPresent();
    }

    @Test
    void supersededCardIsTerminalAndNeverSendsAgain() {
        long cardId = enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, 9, 0)).orElseThrow();
        store.beginSend(cardId);
        store.recordSuperseded(cardId, "WECOM_CARD_FACTS_SUPERSEDED");

        assertThat(store.beginSend(cardId).action())
                .isEqualTo(WecomBusinessCardStore.CardSendAction.SKIP_HANDLED);
    }

    @Test
    void sourceRendersNothingWhenTheAlertIsAlreadyAcknowledged() {
        // 没有对应告警行 → 事实已变，不发卡（渲染期按当前事实判定，不用入队快照）
        Optional<com.fasterxml.jackson.databind.node.ObjectNode> rendered =
                registry.find(OperationalAlertCard.DOMAIN).orElseThrow().render(999_999, 0);
        assertThat(rendered).isEmpty();
    }

    @Test
    void sourceRendersTheCurrentFactsForAnOpenAlert() {
        long orderId = seedOrder();
        jdbc.update(
                """
                INSERT INTO app.operational_alerts
                    (alert_no, alert_type, severity, order_id, message, detail)
                VALUES ('ALERT-CARD-1', 'JD_OUTBOUND_FAILED', 'RED', ?, '京东出库连续失败',
                        '{"business_code":"JD_TIMEOUT"}'::jsonb)
                """,
                orderId);
        Long alertId = jdbc.queryForObject(
                "SELECT id FROM app.operational_alerts WHERE alert_no='ALERT-CARD-1'", Long.class);

        var card = registry.find(OperationalAlertCard.DOMAIN).orElseThrow().render(alertId, 0).orElseThrow();
        assertThat(card.path("task_id").asText()).isEqualTo("alert_" + alertId + "_v0");
        assertThat(card.path("main_title").path("desc").asText()).isEqualTo("ALERT-CARD-1");
        assertThat(card.path("sub_title_text").asText()).isEqualTo("京东出库连续失败");
        // 深链已配 base-url。aibot Button 无 url 字段，深链由 card_action 承载
        assertThat(card.path("card_action").path("url").asText()).contains("https://zimu.test");
    }

    // ------------------------------------------------------------------

    /**
     * operational_alerts 的 CHECK 要求至少一个业务主体，因此必须先有一张订单。
     * 编号带随机后缀：用例之间共享同一个容器，固定编号会撞唯一约束（测试隔离靠数据而非清库）。
     */
    private long seedOrder() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        Long customerId = jdbc.queryForObject(
                """
                INSERT INTO app.customers (customer_code, customer_name)
                VALUES (?, '卡片测试客户')
                RETURNING id
                """,
                Long.class,
                "CUST-CARD-" + suffix);
        return jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, source_channel, source_ref, source_ref_kind, customer_id,
                     settlement_method, settlement_time, receiver_name, receiver_phone,
                     receiver_address)
                VALUES (?, 'WECOM', ?, 'SYNTHETIC', ?,
                        'MONTHLY', CURRENT_TIMESTAMP, '张三', '13800138000', '上海市某区某路 1 号')
                RETURNING id
                """,
                Long.class,
                "SO-CARD-" + suffix,
                "ref-card-" + suffix,
                customerId);
    }

    // ---------- 扫描 seam：覆盖所有创建路径 ----------

    @Test
    void scanFindsOpenAlertsThatHaveNoCardYet() {
        long orderId = seedOrder();
        long alertId = seedAlert(orderId, "ALERT-SCAN-1");
        WecomBusinessCardSource source = registry.find(OperationalAlertCard.DOMAIN).orElseThrow();

        List<WecomTaskId> pending = source.pending(OffsetDateTime.now().minusHours(1), 20);
        assertThat(pending).extracting(WecomTaskId::entityId).contains(alertId);

        // 建卡后不再重复扫出——否则每轮扫描都会重排一次同一张卡
        enqueuer.enqueue(WecomTaskId.ofVersion(OperationalAlertCard.DOMAIN, alertId, 0));
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20))
                .extracting(WecomTaskId::entityId)
                .doesNotContain(alertId);
    }

    @Test
    void scanRespectsTheLookbackWindowSoEnablingDoesNotBlastTheBacklog() {
        long orderId = seedOrder();
        long alertId = seedAlert(orderId, "ALERT-SCAN-OLD");
        jdbc.update(
                "UPDATE app.operational_alerts SET created_at = CURRENT_TIMESTAMP - INTERVAL '10 days'"
                        + " WHERE id = ?",
                alertId);

        WecomBusinessCardSource source = registry.find(OperationalAlertCard.DOMAIN).orElseThrow();
        // 首次开启时，历史积压不该被一次性轰出去
        assertThat(source.pending(OffsetDateTime.now().minusHours(24), 20)).isEmpty();
        assertThat(source.pending(OffsetDateTime.now().minusDays(30), 20))
                .extracting(WecomTaskId::entityId)
                .contains(alertId);
    }

    @Test
    void scanHonoursTheBatchLimit() {
        // 每条告警各挂一张订单：uq_operational_alert_active_subject 规定同一
        // (alert_type, 主体) 只能有一条活动告警，同订单造 5 条会撞索引
        for (int i = 0; i < 5; i++) {
            seedAlert(seedOrder(), "ALERT-LIMIT-" + i);
        }
        WecomBusinessCardSource source = registry.find(OperationalAlertCard.DOMAIN).orElseThrow();
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 3)).hasSize(3);
    }

    @Test
    void acknowledgedAlertsAreNeverScannedAgain() {
        long orderId = seedOrder();
        long alertId = seedAlert(orderId, "ALERT-ACKED");
        jdbc.update(
                """
                UPDATE app.operational_alerts
                SET status = 'ACKNOWLEDGED', acknowledged_by = 'zimu-admin',
                    acknowledged_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                alertId);

        // 已被人处理的告警再推一张卡出去，就是在制造重复劳动
        assertThat(registry.find(OperationalAlertCard.DOMAIN).orElseThrow()
                        .pending(OffsetDateTime.now().minusHours(1), 20))
                .extracting(WecomTaskId::entityId)
                .doesNotContain(alertId);
    }

    private long seedAlert(long orderId, String alertNo) {
        alertNo = alertNo + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        return jdbc.queryForObject(
                """
                INSERT INTO app.operational_alerts
                    (alert_no, alert_type, severity, order_id, message, detail)
                VALUES (?, 'JD_OUTBOUND_FAILED', 'RED', ?, '京东出库连续失败',
                        '{"business_code":"JD_TIMEOUT"}'::jsonb)
                RETURNING id
                """,
                Long.class,
                alertNo,
                orderId);
    }

    private ObjectNodePair renderBothRoutes(
            WecomBusinessCardSource source, long followupId, long draftVersion) {
        return new ObjectNodePair(
                source.render(
                                followupId,
                                draftVersion,
                                new WecomBusinessCardSource.Route(
                                        WecomBusinessCardSource.RouteType.SINGLE, "reviewer"))
                        .orElseThrow(),
                source.render(
                                followupId,
                                draftVersion,
                                new WecomBusinessCardSource.Route(
                                        WecomBusinessCardSource.RouteType.GROUP, "wr-group"))
                        .orElseThrow());
    }

    private FollowupFixture seedReadyFollowup() {
        String suffix = java.util.UUID.randomUUID().toString();
        long submissionId = submissions.submit(new ChannelMessageCommand(
                "corp-card",
                "connection-card",
                "bot-card",
                "message-" + suffix,
                "chat-card",
                "single",
                "employee-card",
                "text",
                "客户跟进",
                null,
                null,
                mapper.createObjectNode().put("message_id", suffix)));
        String userid = "followup-card-" + suffix.substring(0, 8);
        Long reviewerId = jdbc.queryForObject(
                """
                INSERT INTO app.internal_operators
                    (display_name, responsible_team, wecom_userid, active)
                VALUES ('跟进审批人', 'CUSTOMER_OPS', ?, TRUE)
                RETURNING id
                """,
                Long.class,
                userid);
        Long followupId = jdbc.queryForObject(
                """
                INSERT INTO app.business_followups
                    (message_submission_id, employee_draft, stage, processing_status,
                     created_by, designated_reviewer, designated_reviewer_operator_id,
                     agent_slug, agent_version)
                VALUES (?, '原始材料', 'PENDING_APPROVAL', 'SUCCEEDED', '测试人',
                        '跟进审批人', ?, 'customer-followup-agent', 1)
                RETURNING id
                """,
                Long.class,
                submissionId,
                reviewerId);
        String content = """
                {"title":"张三跟进草稿","summary":"已核对，等待 +1",
                 "requires_human":false,"missing_fields":[],"facts":[
                  {"source":"KEHUZX","label":"备注","value":"不应上卡的自由文本"},
                  {"source":"KEHUZX","label":"客户编号","value":"KH-260826-001"},
                  {"source":"KEHUZX","label":"客户名称","value":"张三"},
                  {"source":"KEHUZX","label":"手机号","value":"13800138000"},
                  {"source":"KEHUZX","label":"收货地址","value":"上海市浦东新区某路 1 号"}]}
                """;
        jdbc.update(
                """
                INSERT INTO app.business_followup_draft_versions
                    (followup_id, version, source_revision, status, agent_run_id,
                     agent_slug, agent_version, content, zimu_source_summary,
                     kehuzx_source_summary, upstream_refs)
                VALUES (?, 1, 1, 'READY', ?, 'customer-followup-agent', 1,
                        CAST(? AS jsonb), '{}'::jsonb, '{}'::jsonb, '[]'::jsonb)
                """,
                followupId,
                "run-" + suffix,
                content);
        jdbc.update(
                "UPDATE app.business_followups SET current_draft_version=1 WHERE id=?",
                followupId);
        Long orderDraftId = jdbc.queryForObject(
                """
                INSERT INTO app.order_drafts
                    (draft_no, submission_id, source_order_no, receiver_name,
                     receiver_phone, receiver_address, settlement_method, missing_fields)
                VALUES (?, ?, ?, '张三', '13800138000', '上海市浦东新区某路 1 号',
                        '月结', '[]'::jsonb)
                RETURNING id
                """,
                Long.class,
                "OD-FOLLOWUP-" + suffix,
                submissionId,
                "SOURCE-FOLLOWUP-" + suffix);
        jdbc.update(
                """
                INSERT INTO app.order_draft_lines
                    (order_draft_id, line_no, product_name_raw, spec_raw, unit_raw, quantity)
                VALUES (?, 1, '原切牛排', '500g', '盒', 2)
                """,
                orderDraftId);
        jdbc.update(
                """
                UPDATE app.business_followup_draft_versions
                SET content = jsonb_set(content, '{order_snapshot}', jsonb_build_object(
                    'order_draft_id', CAST(? AS text),
                    'revision', 0,
                    'status', 'OPEN',
                    'receiver_name', '张三',
                    'receiver_phone', '13800138000',
                    'receiver_address', '上海市浦东新区某路 1 号',
                    'settlement_method', '月结',
                    'missing_fields', '[]'::jsonb,
                    'items', jsonb_build_array(jsonb_build_object(
                        'line_no', 1, 'product_name', '原切牛排', 'spec', '500g',
                        'quantity', 2, 'unit', '盒'))))
                WHERE followup_id=? AND version=1
                """,
                String.valueOf(orderDraftId),
                followupId);
        return new FollowupFixture(followupId, reviewerId, userid);
    }

    /** 一条 CONFIRM 审批（尚未 apply）；{@code idempotencyKey} 由调用方保证唯一。 */
    private long seedConfirmApproval(FollowupFixture fixture, String idempotencyKey) {
        Long eventId = jdbc.queryForObject(
                """
                INSERT INTO app.wecom_events (event_type, msgid, raw_payload)
                VALUES ('business_followup_card_event', ?, '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                "followup-result-" + java.util.UUID.randomUUID());
        return jdbc.queryForObject(
                """
                INSERT INTO app.business_followup_approvals
                    (followup_id, draft_version, designated_reviewer_operator_id,
                     decided_by_operator_id, decision, source_kind, source_event_id, request_id,
                     idempotency_key, request_fingerprint)
                VALUES (?, 1, ?, ?, 'CONFIRM', 'WECOM_CARD', ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                fixture.followupId(),
                fixture.reviewerOperatorId(),
                fixture.reviewerOperatorId(),
                eventId,
                "request-" + java.util.UUID.randomUUID(),
                idempotencyKey,
                "a".repeat(64));
    }

    private record FollowupFixture(long followupId, long reviewerOperatorId, String reviewerUserid) {}

    private record ObjectNodePair(
            com.fasterxml.jackson.databind.node.ObjectNode single,
            com.fasterxml.jackson.databind.node.ObjectNode group) {}
}
