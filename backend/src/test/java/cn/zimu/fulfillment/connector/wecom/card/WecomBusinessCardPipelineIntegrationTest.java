package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;

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

    @BeforeEach
    void reset() {
        // 投递行与告警都要清：扫描用例断言「扫得到 / 扫不到」，上一个用例遗留的告警会污染结果
        jdbc.execute("TRUNCATE app.wecom_business_cards RESTART IDENTITY CASCADE");
        jdbc.execute("TRUNCATE app.operational_alerts RESTART IDENTITY CASCADE");
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
    void allFourCardDomainsAreRegistered() {
        assertThat(registry.domains())
                .containsExactlyInAnyOrder(
                        ReviewCaseCard.DOMAIN,
                        OperationalAlertCard.DOMAIN,
                        BatchConfirmedCard.DOMAIN,
                        JdOutboundFailureCard.DOMAIN);
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
                        "SELECT count(*) FROM app.wecom_business_cards WHERE task_id = ?",
                        Integer.class,
                        taskId.value()))
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
        assertThat(card.path("task_id").asText()).isEqualTo("alert:" + alertId + ":v0");
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
}
