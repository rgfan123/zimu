package cn.zimu.fulfillment.connector.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
    "app.source-sync.recovery.enabled=false",
    "app.message-worker.enabled=false"
})
class SourceSyncStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceSyncStore store;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.shipment_syncs, app.idempotency_registry RESTART IDENTITY CASCADE");
    }

    @Test
    void acceptedReconciliationUsesThePersistedIntentAndClosesWithoutAnotherWrite() {
        seedReconciliation(7001L, "outer-key-7001", "JUFUBAO:sub-1:JDVA1", 4);
        SourceSyncReconcileCommand command = command(SourceSyncReconciliationDecision.ACCEPTED, 4);

        SourceSyncOutcome outcome = tx().execute(status -> {
            SourceSyncStore.ReconciliationIntent intent = store.lockReconciliation(7001L, command);
            return store.applyReconciliation(intent, command.decision(), command.note());
        });

        assertThat(outcome.status()).isEqualTo(SourceSyncStatus.SYNCED);
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM app.shipment_syncs WHERE shipment_id=7001", String.class))
                .isEqualTo("SYNCED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class, SourceSyncStore.EXECUTE_SCOPE, "outer-key-7001"))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void changedReconciliationEchoIsRejectedByProjectionCas() {
        seedReconciliation(7002L, "outer-key-7002", "JUFUBAO:sub-1:JDVA1", 2);
        SourceSyncReconcileCommand stale = new SourceSyncReconcileCommand(
                SourceSyncReconciliationDecision.ACCEPTED, "人工核对", "b".repeat(64),
                "sub-1", "JD", "JDVA1", 2);

        assertThatThrownBy(() -> tx().execute(status -> store.lockReconciliation(7002L, stale)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getBusinessCode())
                                .isEqualTo("SOURCE_SYNC_RECONCILIATION_INTENT_CHANGED"));
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM app.shipment_syncs WHERE shipment_id=7002", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void notAcceptedClearsTheOriginalIntentButKeepsAttemptCount() {
        seedReconciliation(7003L, "outer-key-7003", "JUFUBAO:sub-1:JDVA1", 6);
        SourceSyncReconcileCommand command = command(SourceSyncReconciliationDecision.NOT_ACCEPTED, 6);

        tx().execute(status -> {
            SourceSyncStore.ReconciliationIntent intent = store.lockReconciliation(7003L, command);
            return store.applyReconciliation(intent, command.decision(), command.note());
        });

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT sync_status, attempt_count, intent_key, platform_intent_key, check_hash "
                        + "FROM app.shipment_syncs WHERE shipment_id=7003");
        assertThat(row).containsEntry("sync_status", "PENDING").containsEntry("attempt_count", 1);
        assertThat(row.get("intent_key")).isNull();
        assertThat(row.get("platform_intent_key")).isNull();
        assertThat(row.get("check_hash")).isNull();
    }

    @Test
    void recoveryDistinguishesExpiredPreWriteAndPostWriteAttempts() {
        seedSyncing(7004L, "outer-key-7004", false);
        seedSyncing(7005L, "outer-key-7005", true);

        assertThat(store.recoverExpiredSyncing()).isEqualTo(2);

        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM app.shipment_syncs WHERE shipment_id=7004", String.class))
                .isEqualTo("SYNC_FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM app.shipment_syncs WHERE shipment_id=7005", String.class))
                .isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void uncertainNoteIsPersistedOnlyAsHashAndLength() {
        seedReconciliation(7006L, "outer-key-7006", "JUFUBAO:sub-1:JDVA1", 3);
        SourceSyncReconcileCommand command = new SourceSyncReconcileCommand(
                SourceSyncReconciliationDecision.UNCERTAIN, "不要把这段原文落库", "a".repeat(64),
                "sub-1", "JD", "JDVA1", 3);

        tx().execute(status -> {
            SourceSyncStore.ReconciliationIntent intent = store.lockReconciliation(7006L, command);
            return store.applyReconciliation(intent, command.decision(), command.note());
        });

        String stored = jdbc.queryForObject(
                "SELECT last_error_message FROM app.shipment_syncs WHERE shipment_id=7006", String.class);
        assertThat(stored).contains("note_sha256=", "note_length=").doesNotContain("不要把这段原文落库");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private SourceSyncReconcileCommand command(SourceSyncReconciliationDecision decision, long version) {
        return new SourceSyncReconcileCommand(
                decision, "人工核对", "a".repeat(64), "sub-1", "JD", "JDVA1", version);
    }

    private void seedReconciliation(long shipmentId, String key, String platformKey, long version) {
        insertWithoutForeignKeys(
                """
                INSERT INTO app.shipment_syncs
                    (shipment_id, source_channel, sync_status, attempt_count,
                     intent_key, platform_intent_key, check_hash, artifact_hash,
                     source_line_ref, carrier_code, tracking_number,
                     intent_started_at, effect_started_at, lock_version,
                     last_error_code, last_error_message)
                VALUES (?, 'JUFUBAO', 'RECONCILIATION_REQUIRED', 1,
                        ?, ?, ?, ?, 'sub-1', 'JD', 'JDVA1',
                        CURRENT_TIMESTAMP-INTERVAL '2 minutes',
                        CURRENT_TIMESTAMP-INTERVAL '1 minute', ?,
                        'RECONCILIATION_REQUIRED', 'unknown')
                """,
                shipmentId, key, platformKey, "a".repeat(64), "c".repeat(64), version);
        jdbc.update(
                """
                INSERT INTO app.idempotency_registry
                    (scope,idempotency_key,payload_hash,status,completed_at,response_snapshot)
                VALUES (?, ?, ?, 'RECONCILIATION_REQUIRED', CURRENT_TIMESTAMP, '{}'::jsonb)
                """,
                SourceSyncStore.EXECUTE_SCOPE, key, "d".repeat(64));
    }

    private void seedSyncing(long shipmentId, String key, boolean effectStarted) {
        insertWithoutForeignKeys(
                """
                INSERT INTO app.shipment_syncs
                    (shipment_id, source_channel, sync_status, attempt_count,
                     intent_key, platform_intent_key, check_hash, artifact_hash,
                     source_line_ref, carrier_code, tracking_number,
                     intent_started_at, effect_started_at, lock_version)
                VALUES (?, 'JUFUBAO', 'SYNCING', 1, ?, ?, ?, ?, 'sub-1', 'JD', 'JDVA1',
                        CURRENT_TIMESTAMP-INTERVAL '2 minutes',
                        CASE WHEN ? THEN CURRENT_TIMESTAMP-INTERVAL '1 minute' ELSE NULL END, 1)
                """,
                shipmentId, key, "JUFUBAO:sub-1:JDVA1-" + shipmentId,
                "a".repeat(64), "c".repeat(64), effectStarted);
        jdbc.update(
                """
                INSERT INTO app.idempotency_registry
                    (scope,idempotency_key,payload_hash,status,owner_token,lease_expires_at)
                VALUES (?, ?, ?, 'IN_PROGRESS', 'owner', CURRENT_TIMESTAMP-INTERVAL '1 minute')
                """,
                SourceSyncStore.EXECUTE_SCOPE, key, "d".repeat(64));
    }

    private void insertWithoutForeignKeys(String sql, Object... args) {
        tx().executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role=replica");
            jdbc.update(sql, args);
        });
    }
}
