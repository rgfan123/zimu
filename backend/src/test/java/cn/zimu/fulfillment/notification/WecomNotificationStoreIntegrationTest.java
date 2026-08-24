package cn.zimu.fulfillment.notification;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.operator.OperatorResolver;
import cn.zimu.fulfillment.operator.OperatorTeamResolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL acceptance for Issue #90 capture, batching, retry and unknown-delivery fencing. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.wecom-notification.enabled=false",
            "app.message-worker.enabled=false",
            "app.wecom-export-worker.enabled=false",
            "app.agent-worker.enabled=false",
            "app.quality-eval.enabled=false"
        })
@Transactional
class WecomNotificationStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private WecomNotificationStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WecomNotificationQueryService queries;

    @Autowired
    private OperatorResolver operators;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void transactionalTriggersCaptureOnlyBusinessFactsAndCreateOneFiveMinuteDigest() {
        long businessOrder = insertOrder("ORD-NOTIFY-1", "BUSINESS", "real-source");
        long demoOrder = insertOrder("ORD-DEMO-NOTIFY-1", "DEMO", "demo-source");
        insertReview(businessOrder, "RC-NOTIFY-1", "ORDER_OPS");
        insertEvent(businessOrder, 1, "ORDER_RECEIVED", "BUSINESS", null);
        insertEvent(demoOrder, 1, "ORDER_RECEIVED", "DEMO", null);

        Optional<NotificationBatch> claimed = store.claim("worker-a", Duration.ofMinutes(2), 20);

        assertThat(claimed).isPresent();
        NotificationBatch batch = claimed.orElseThrow();
        assertThat(batch.responsibleTeam()).isEqualTo("ORDER_OPS");
        assertThat(batch.items()).hasSize(2);
        assertThat(batch.items())
                .extracting(NotificationItem::notificationKind)
                .containsExactlyInAnyOrder("REVIEW_CASE", "ORDER_CREATED");
        assertThat(batch.items().toString())
                .contains("RC-NOTIFY-1", "ORD-NOTIFY-1")
                .doesNotContain(
                        "测试收货人",
                        "13800000000",
                        "测试地址",
                        "ORD-DEMO-NOTIFY-1");

        DeliveryPermit send = store.beginDelivery(
                batch.id(), "userid:zhangsan", "张三", "zhangsan", "a".repeat(64));
        assertThat(send).isEqualTo(new DeliveryPermit(DeliveryAction.SEND, 1));
        store.recordSent(batch.id(), "userid:zhangsan", "req-notify-1");
        store.recordBlocked(
                batch.id(), "unbound:1", "李四", "WECOM_USERID_UNBOUND", "运营人员未绑定企微 userid");
        store.finishBatch(batch.id(), "worker-a");

        assertThat(batchStatus(batch.id())).isEqualTo("PARTIAL");
        assertThat(itemStatuses(batch.id())).containsOnly("PARTIAL");
        assertThat(deliveryStatuses(batch.id())).containsExactly("SENT", "BLOCKED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_notification_alerts WHERE batch_id=?",
                        Long.class,
                        batch.id()))
                .as("one blocked delivery creates one deduplicated alert projection per source fact")
                .isEqualTo(2L);
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT delivery_status FROM app.wecom_notification_alerts WHERE batch_id=?",
                        String.class,
                        batch.id()))
                .containsExactly("BLOCKED");
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT order_id FROM app.wecom_notification_alerts WHERE batch_id=?",
                        Long.class,
                        batch.id()))
                .containsExactly(businessOrder);
        long reviewSourceId = batch.items().stream()
                .filter(item -> "REVIEW_CASE".equals(item.sourceType()))
                .findFirst()
                .orElseThrow()
                .sourceId();
        var trace = queries.deliveries("review_case", reviewSourceId, null, 0, 20);
        assertThat(trace.items()).hasSize(2);
        assertThat(trace.items())
                .extracting(WecomNotificationDeliveryDto::status)
                .containsExactly("BLOCKED", "SENT");
        assertThat(trace.items().stream()
                        .filter(row -> "BLOCKED".equals(row.status()))
                        .findFirst()
                        .orElseThrow().reasonCode())
                .isEqualTo("WECOM_USERID_UNBOUND");
        assertThat(trace.items().stream()
                        .filter(row -> "BLOCKED".equals(row.status()))
                        .findFirst()
                        .orElseThrow().alertSeverity())
                .isEqualTo("YELLOW");
    }

    @Test
    void retryReusesTheSameBatchWhileAnInFlightRestartBecomesUnknownWithoutResubmitPermit() {
        long order = insertOrder("ORD-NOTIFY-2", "BUSINESS", "real-source-2");
        insertEvent(order, 1, "TRACKING_RECEIVED", "BUSINESS", null);

        NotificationBatch batch = store.claim("worker-a", Duration.ofMinutes(2), 20).orElseThrow();
        DeliveryPermit first = store.beginDelivery(
                batch.id(), "userid:zhangsan", "张三", "zhangsan", "b".repeat(64));
        store.recordRetryableFailure(
                batch.id(), "userid:zhangsan", "NOT_CONNECTED", "NOT_CONNECTED", first.attempt());
        store.finishBatch(batch.id(), "worker-a");
        jdbc.update(
                "UPDATE app.wecom_notification_batches SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",
                batch.id());

        NotificationBatch retry = store.claim("worker-b", Duration.ofMinutes(2), 20).orElseThrow();
        assertThat(retry.id()).isEqualTo(batch.id());
        DeliveryPermit second = store.beginDelivery(
                retry.id(), "userid:zhangsan", "张三", "zhangsan", "b".repeat(64));
        assertThat(second).isEqualTo(new DeliveryPermit(DeliveryAction.SEND, 2));

        // Simulate a restart after external submission but before the ack result was persisted.
        DeliveryPermit afterRestart = store.beginDelivery(
                retry.id(), "userid:zhangsan", "张三", "zhangsan", "b".repeat(64));
        assertThat(afterRestart.action()).isEqualTo(DeliveryAction.SKIP_UNKNOWN);
        store.finishBatch(retry.id(), "worker-b");

        assertThat(batchStatus(batch.id())).isEqualTo("UNKNOWN");
        assertThat(deliveryStatuses(batch.id())).containsExactly("UNKNOWN");
        assertThat(jdbc.queryForObject(
                        "SELECT reason_code FROM app.wecom_notification_deliveries WHERE batch_id=?",
                        String.class,
                        batch.id()))
                .isEqualTo("IN_FLIGHT_DELIVERY_UNKNOWN");
    }

    @Test
    void plannedShutdownReleaseMakesClaimedBatchImmediatelyRecoverable() {
        long order = insertOrder("ORD-NOTIFY-SHUTDOWN", "BUSINESS", "notify-shutdown");
        insertEvent(order, 1, "TRACKING_RECEIVED", "BUSINESS", null);

        NotificationBatch first = store.claim("shutdown-worker-a", Duration.ofMinutes(2), 20).orElseThrow();

        assertThat(store.releaseOwnedForShutdown(first.id(), "shutdown-worker-a")).isTrue();
        NotificationBatch reclaimed =
                store.claim("shutdown-worker-b", Duration.ofMinutes(2), 20).orElseThrow();
        assertThat(reclaimed.id()).isEqualTo(first.id());
        assertThat(store.releaseOwnedForShutdown(reclaimed.id(), "wrong-owner")).isFalse();
        assertThat(store.releaseOwnedForShutdown(reclaimed.id(), "shutdown-worker-b")).isTrue();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentWorkersDoNotSplitOneTeamWindowIntoTwoDigests() throws Exception {
        long firstOrder = insertOrder("ORD-NOTIFY-CONCURRENT-1", "BUSINESS", "notify-concurrent-1");
        long secondOrder = insertOrder("ORD-NOTIFY-CONCURRENT-2", "BUSINESS", "notify-concurrent-2");
        insertReview(firstOrder, "RC-NOTIFY-CONCURRENT-1", "ORDER_OPS");
        insertReview(secondOrder, "RC-NOTIFY-CONCURRENT-2", "ORDER_OPS");
        jdbc.update(
                """
                UPDATE app.wecom_notification_items
                SET available_at=CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE source_type='REVIEW_CASE'
                  AND summary->>'case_no' IN ('RC-NOTIFY-CONCURRENT-1', 'RC-NOTIFY-CONCURRENT-2')
                """);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Optional<NotificationBatch>> first = workers.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.claim("concurrent-a", Duration.ofMinutes(2), 20);
            });
            Future<Optional<NotificationBatch>> second = workers.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.claim("concurrent-b", Duration.ofMinutes(2), 20);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<NotificationBatch> claimed = java.util.stream.Stream.of(
                            first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .flatMap(Optional::stream)
                    .toList();
            assertThat(claimed).singleElement().satisfies(batch -> assertThat(batch.items()).hasSize(2));
        }

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.wecom_notification_batches b
                        JOIN app.wecom_notification_items i ON i.batch_id=b.id
                        WHERE i.summary->>'case_no' IN (
                            'RC-NOTIFY-CONCURRENT-1', 'RC-NOTIFY-CONCURRENT-2')
                        """,
                        Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(DISTINCT b.id) FROM app.wecom_notification_batches b
                        JOIN app.wecom_notification_items i ON i.batch_id=b.id
                        WHERE i.summary->>'case_no' IN (
                            'RC-NOTIFY-CONCURRENT-1', 'RC-NOTIFY-CONCURRENT-2')
                        """,
                        Long.class))
                .isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredShortLeaseIsReclaimedAndOnlyTheNewWorkerCanRenewIt() throws Exception {
        long order = insertOrder("ORD-NOTIFY-LEASE-1", "BUSINESS", "notify-lease-1");
        insertEvent(order, 1, "TRACKING_RECEIVED", "BUSINESS", null);

        NotificationBatch original = store.claim("short-lease-worker-a", Duration.ofSeconds(1), 20)
                .orElseThrow();
        Thread.sleep(1250);
        assertThat(store.renewLease(original.id(), "short-lease-worker-a", Duration.ofSeconds(1)))
                .as("an already expired lease cannot be renewed")
                .isFalse();

        NotificationBatch reclaimed = store.claim("short-lease-worker-b", Duration.ofSeconds(1), 20)
                .orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(original.id());
        assertThat(store.renewLease(original.id(), "short-lease-worker-a", Duration.ofSeconds(1)))
                .as("stale owner must not revive a lease after another worker reclaimed it")
                .isFalse();
        assertThat(store.renewLease(reclaimed.id(), "short-lease-worker-b", Duration.ofSeconds(2)))
                .isTrue();
        store.recordBlocked(
                reclaimed.id(),
                "team:FULFILLMENT_OPS",
                null,
                "OPERATOR_TEAM_NO_MEMBERS",
                "test cleanup");
        store.finishBatch(reclaimed.id(), "short-lease-worker-b");
    }

    @Test
    void retryReconcilesDeactivatedMovedAndUseridChangedOperatorGenerations() {
        long order = insertOrder("ORD-NOTIFY-ROUTE-1", "BUSINESS", "notify-route-1");
        insertReview(order, "RC-NOTIFY-ROUTE-1", "ORDER_OPS");
        NotificationBatch batch = store.claim("route-worker", Duration.ofMinutes(2), 20)
                .orElseThrow();

        long useridChanged = insertOperator("Userid changed", "ORDER_OPS", "old-userid", true);
        long deactivated = insertOperator("Deactivated", "ORDER_OPS", "disabled-userid", true);
        long moved = insertOperator("Moved", "ORDER_OPS", "moved-userid", true);
        OperatorTeamResolution initial = operators.resolve("ORDER_OPS");
        assertThat(initial.members())
                .extracting(OperatorTeamResolution.OperatorResolutionMember::operatorId)
                .containsExactly(useridChanged, deactivated, moved);

        String changedOldKey = "operator:" + useridChanged + ":userid:old-userid";
        DeliveryPermit changedPermit = store.beginDelivery(
                batch.id(), changedOldKey, "Userid changed", "old-userid", "c".repeat(64));
        store.recordRetryableFailure(
                batch.id(), changedOldKey, "NOT_CONNECTED", "not submitted", changedPermit.attempt());
        String deactivatedKey = "operator:" + deactivated + ":userid:disabled-userid";
        store.beginDelivery(
                batch.id(), deactivatedKey, "Deactivated", "disabled-userid", "c".repeat(64));
        String movedKey = "operator:" + moved + ":userid:moved-userid";
        DeliveryPermit movedPermit = store.beginDelivery(
                batch.id(), movedKey, "Moved", "moved-userid", "c".repeat(64));
        store.recordRetryableFailure(
                batch.id(), movedKey, "NOT_CONNECTED", "not submitted", movedPermit.attempt());

        jdbc.update("UPDATE app.internal_operators SET wecom_userid='new-userid' WHERE id=?", useridChanged);
        jdbc.update("UPDATE app.internal_operators SET active=false WHERE id=?", deactivated);
        jdbc.update(
                "UPDATE app.internal_operators SET responsible_team='FULFILLMENT_OPS' WHERE id=?",
                moved);
        entityManager.clear();
        OperatorTeamResolution current = operators.resolve("ORDER_OPS");
        assertThat(current.members()).singleElement().satisfies(member -> {
            assertThat(member.operatorId()).isEqualTo(useridChanged);
            assertThat(member.wecomUserid()).isEqualTo("new-userid");
        });
        String changedNewKey = "operator:" + useridChanged + ":userid:new-userid";

        store.reconcileRecipients(batch.id(), Set.of(changedNewKey));
        DeliveryPermit replacement = store.beginDelivery(
                batch.id(), changedNewKey, "Userid changed", "new-userid", "c".repeat(64));
        store.recordSent(batch.id(), changedNewKey, "req-route-new-generation");
        store.finishBatch(batch.id(), "route-worker");

        assertThat(replacement).isEqualTo(new DeliveryPermit(DeliveryAction.SEND, 1));
        assertThat(deliveryStatus(batch.id(), changedOldKey)).isEqualTo("BLOCKED");
        assertThat(deliveryStatus(batch.id(), deactivatedKey)).isEqualTo("UNKNOWN");
        assertThat(deliveryStatus(batch.id(), movedKey)).isEqualTo("BLOCKED");
        assertThat(deliveryStatus(batch.id(), changedNewKey)).isEqualTo("SENT");
        assertThat(deliveryStatuses(batch.id())).doesNotContain("RETRY_PENDING", "SENDING");
        assertThat(batchStatus(batch.id())).isEqualTo("UNKNOWN");
    }

    @Test
    void alertProjectionSurvivesStoreRestartDeduplicatesAndDoesNotRequireAnOrderSubject() {
        jdbc.update(
                """
                INSERT INTO app.wecom_notification_items (
                    source_type, source_id, notification_kind, responsible_team,
                    summary, window_start, available_at
                ) VALUES (
                    'REVIEW_CASE', 990090, 'REVIEW_CASE', 'ORDER_OPS',
                    '{"order_draft_id":990090}'::jsonb,
                    app.wecom_notification_window_start(CURRENT_TIMESTAMP - INTERVAL '6 minutes'),
                    CURRENT_TIMESTAMP - INTERVAL '1 second'
                )
                """);
        NotificationBatch batch = store.claim("alert-worker", Duration.ofMinutes(2), 20)
                .orElseThrow();
        store.recordBlocked(
                batch.id(),
                "team:ORDER_OPS",
                null,
                "OPERATOR_TEAM_NO_MEMBERS",
                "责任团队暂无 active 运营人员");

        JdbcWecomNotificationStore restarted = new JdbcWecomNotificationStore(jdbc, objectMapper);
        restarted.recordBlocked(
                batch.id(),
                "team:ORDER_OPS",
                null,
                "OPERATOR_TEAM_NO_MEMBERS",
                "责任团队暂无 active 运营人员");
        restarted.finishBatch(batch.id(), "alert-worker");

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_notification_alerts WHERE batch_id=?",
                        Long.class,
                        batch.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT order_id FROM app.wecom_notification_alerts WHERE batch_id=?",
                        Long.class,
                        batch.id()))
                .isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT alert_key FROM app.wecom_notification_alerts WHERE batch_id=?",
                        String.class,
                        batch.id()))
                .matches("^WECOM-NOTIFICATION-[0-9]+-[0-9]+$");
    }

    @Test
    void acknowledgedDeliveryFailureCreatesOneRedDurableAlert() {
        long order = insertOrder("ORD-NOTIFY-FAILED-1", "BUSINESS", "notify-failed-1");
        insertEvent(order, 1, "TRACKING_RECEIVED", "BUSINESS", null);
        NotificationBatch batch = store.claim("failed-worker", Duration.ofMinutes(2), 20)
                .orElseThrow();
        String recipientKey = "operator:990091:userid:rejected-user";
        store.beginDelivery(
                batch.id(), recipientKey, "Rejected operator", "rejected-user", "d".repeat(64));

        store.recordFailed(
                batch.id(), recipientKey, "req-rejected", "WECOM_93000", "conversation missing");
        store.finishBatch(batch.id(), "failed-worker");

        assertThat(batchStatus(batch.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_notification_alerts WHERE batch_id=?",
                        Long.class,
                        batch.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT severity FROM app.wecom_notification_alerts WHERE batch_id=?",
                        String.class,
                        batch.id()))
                .isEqualTo("RED");
    }

    private long insertOrder(String orderNo, String scope, String sourceRef) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO app.orders (
                    order_no, data_scope, source_channel, source_ref, source_ref_kind,
                    order_status, settlement_method, settlement_time,
                    receiver_name, receiver_phone, receiver_address
                ) VALUES (?, ?, 'WECOM', ?, 'PROVIDED', 'RECEIVED', 'MONTHLY',
                          '2026-08-23T08:00:00Z', '测试收货人', '13800000000', '测试地址')
                RETURNING id
                """,
                Long.class,
                orderNo,
                scope,
                sourceRef);
        return id == null ? -1 : id;
    }

    private void insertReview(long orderId, String caseNo, String team) {
        jdbc.update(
                """
                INSERT INTO app.review_cases (
                    case_no, case_type, status, responsible_team, reason_code,
                    order_id, detail, created_at, updated_at
                ) VALUES (?, 'ORDER', 'OPEN', ?, 'SKU_MAPPING_REQUIRED', ?, '{}'::jsonb,
                          '2026-08-23T09:56:00Z', '2026-08-23T09:56:00Z')
                """,
                caseNo,
                team,
                orderId);
    }

    private void insertEvent(long orderId, long sequence, String type, String scope, Long shipmentId) {
        jdbc.update(
                """
                INSERT INTO app.order_events (
                    order_id, sequence_no, event_type_code, shipment_id,
                    data_scope, payload, operator, created_at
                ) VALUES (?, ?, ?, ?, ?, '{}'::jsonb, 'integration-test', '2026-08-23T09:57:00Z')
                """,
                orderId,
                sequence,
                type,
                shipmentId,
                scope);
    }

    private long insertOperator(String displayName, String team, String userid, boolean active) {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO app.internal_operators (
                    display_name, responsible_team, wecom_userid, active, lock_version
                ) VALUES (?, ?, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                displayName,
                team,
                userid,
                active);
        return id == null ? -1 : id;
    }

    private String batchStatus(long batchId) {
        return jdbc.queryForObject(
                "SELECT status FROM app.wecom_notification_batches WHERE id=?", String.class, batchId);
    }

    private java.util.List<String> itemStatuses(long batchId) {
        return jdbc.queryForList(
                "SELECT status FROM app.wecom_notification_items WHERE batch_id=? ORDER BY id",
                String.class,
                batchId);
    }

    private java.util.List<String> deliveryStatuses(long batchId) {
        return jdbc.queryForList(
                "SELECT status FROM app.wecom_notification_deliveries WHERE batch_id=? ORDER BY id",
                String.class,
                batchId);
    }

    private String deliveryStatus(long batchId, String recipientKey) {
        return jdbc.queryForObject(
                "SELECT status FROM app.wecom_notification_deliveries WHERE batch_id=? AND recipient_key=?",
                String.class,
                batchId,
                recipientKey);
    }
}
