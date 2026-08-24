package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V53 persists Shipment-scoped source-sync intent and fences online/file fallback writes. */
@Testcontainers
class ShipmentSourceSyncMigrationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    @Test
    void syncingRequiresDurableIntentAndPendingRequiresFreshConfirmation() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            seedShipment(statement, 910001);
            statement.executeUpdate(
                    """
                    INSERT INTO app.shipment_syncs (id, shipment_id, source_channel)
                    VALUES (910001, 910001, 'JUFUBAO')
                    """);

            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE app.shipment_syncs SET sync_status='SYNCING' WHERE id=910001"))
                    .as("SYNCING cannot exist without a durable, replay-safe intent")
                    .hasMessageContaining("shipment_syncs_syncing_intent_check");

            assertThat(statement.executeUpdate(syncingUpdate(910001, "intent-910001", "platform-910001")))
                    .isEqualTo(1);
            assertThat(statement.executeUpdate(
                    """
                    UPDATE app.shipment_syncs
                    SET sync_status='RECONCILIATION_REQUIRED',
                        effect_started_at=CURRENT_TIMESTAMP,
                        lock_version=lock_version+1
                    WHERE id=910001
                    """))
                    .isEqualTo(1);

            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE app.shipment_syncs SET sync_status='PENDING' WHERE id=910001"))
                    .as("NOT_ACCEPTED reconciliation must not reuse the previous confirmation")
                    .hasMessageContaining("shipment_syncs_pending_fresh_check");

            assertThat(statement.executeUpdate(
                    """
                    UPDATE app.shipment_syncs
                    SET sync_status='PENDING',
                        intent_key=NULL,
                        platform_intent_key=NULL,
                        check_hash=NULL,
                        artifact_hash=NULL,
                        source_line_ref=NULL,
                        carrier_code=NULL,
                        tracking_number=NULL,
                        intent_started_at=NULL,
                        effect_started_at=NULL,
                        lock_version=lock_version+1
                    WHERE id=910001
                    """))
                    .isEqualTo(1);
            assertThat(singleLong(statement,
                    "SELECT attempt_count FROM app.shipment_syncs WHERE id=910001"))
                    .as("attempt_count is cumulative even when a fresh check is required")
                    .isEqualTo(1L);
        }
    }

    @Test
    void fileFallbackAndOnlineSyncFenceEachOtherThroughShipmentItems() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            seedShipment(statement, 920001);
            seedFileFallback(statement, 920001);
            statement.executeUpdate(
                    """
                    INSERT INTO app.shipment_syncs (id, shipment_id, source_channel)
                    VALUES (920001, 920001, 'JUFUBAO')
                    """);

            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE app.source_return_exports SET push_status='SUCCESS' WHERE id=920001"))
                    .as("file fallback must follow the persisted push state machine")
                    .hasMessageContaining("invalid source return push transition");
            assertThatThrownBy(() -> statement.executeUpdate(
                    """
                    UPDATE app.source_return_exports
                    SET file_ref='/mutated.xlsx', push_status='PUSHING'
                    WHERE id=920001
                    """))
                    .as("push projection updates cannot rewrite the generated artifact")
                    .hasMessageContaining("source return export generation facts are immutable");

            assertThat(statement.executeUpdate(
                    """
                    UPDATE app.source_return_exports
                    SET push_status='PUSHING', push_started_at=CURRENT_TIMESTAMP, pushed_by='operator-a'
                    WHERE id=920001
                    """))
                    .isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate(
                    syncingUpdate(920001, "intent-file-first", "platform-file-first")))
                    .as("an active file fallback must block online sync for the same Shipment")
                    .hasMessageContaining("active source return fallback");

            assertThat(statement.executeUpdate(
                    """
                    UPDATE app.source_return_exports
                    SET push_status='FAILED', push_error='{"code":"SAFE_FAILURE"}'::jsonb
                    WHERE id=920001
                    """))
                    .isEqualTo(1);
            assertThat(statement.executeUpdate(
                    syncingUpdate(920001, "intent-online-first", "platform-online-first")))
                    .isEqualTo(1);
            assertThatThrownBy(() -> statement.executeUpdate(
                    """
                    UPDATE app.source_return_exports
                    SET push_status='PUSHING', push_started_at=CURRENT_TIMESTAMP,
                        pushed_by='operator-b', push_error=NULL
                    WHERE id=920001
                    """))
                    .as("an active online sync must block file fallback for the same Shipment")
                    .hasMessageContaining("active shipment source sync");

            String mutexFunction = singleString(statement,
                    "SELECT pg_get_functiondef('app.guard_source_return_fallback_mutex()'::regprocedure)");
            assertThat(mutexFunction)
                    .as("the parent export claim must lock every Shipment referenced by its immutable items")
                    .contains("source_return_export_items", "ORDER BY", "FOR UPDATE");
        }
    }

    @Test
    void invalidatedSourceReturnArtifactCannotClaimFallbackPush() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            seedShipment(statement, 925001);
            seedFileFallback(statement, 925001);
            statement.execute("SET session_replication_role = replica");
            try {
                statement.executeUpdate(
                        """
                        INSERT INTO app.source_return_export_invalidations
                            (id, source_return_export_id, source_attribution_correction_id,
                             reason_code, invalidated_by)
                        VALUES (925001, 925001, 925001, 'SOURCE_ATTRIBUTION_CORRECTED',
                                'migration-test')
                        """);
            } finally {
                statement.execute("SET session_replication_role = origin");
            }

            assertThatThrownBy(() -> statement.executeUpdate(
                    """
                    UPDATE app.source_return_exports
                    SET push_status='PUSHING', push_started_at=CURRENT_TIMESTAMP,
                        pushed_by='operator-invalidated'
                    WHERE id=925001
                    """))
                    .as("an invalidated historical artifact must never regain external-write authority")
                    .hasMessageContaining("invalidated source return export cannot be pushed");
        }
    }

    @Test
    void committedInvalidationDefeatsConcurrentPushClaimWhenInvalidationLocksFirst() throws Exception {
        seedConcurrentInvalidationFixture(930001);

        try (Connection invalidation = connection();
                Connection pushing = connection();
                Connection observer = connection();
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            beginBoundedTransaction(invalidation);
            beginBoundedTransaction(pushing);
            long pushingPid = backendPid(pushing);

            try (Statement statement = invalidation.createStatement()) {
                assertThat(statement.executeUpdate(invalidationInsert(930001))).isEqualTo(1);
            }

            CountDownLatch pushIssued = new CountDownLatch(1);
            Future<SQLException> pushOutcome = worker.submit(() -> executeAndCommit(
                    pushing,
                    pushClaimUpdate(930001, "operator-invalidation-first"),
                    pushIssued));
            awaitIssued(pushIssued);
            awaitLockWait(observer, pushingPid);

            invalidation.commit();
            SQLException failure = pushOutcome.get(10, TimeUnit.SECONDS);

            assertThat((Throwable) failure)
                    .as("the PUSHING claim must re-check the committed invalidation after its lock wait")
                    .isNotNull()
                    .hasMessageContaining("invalidated source return export cannot be pushed");
            try (Statement statement = observer.createStatement()) {
                assertThat(singleString(statement,
                                "SELECT push_status FROM app.source_return_exports WHERE id=930001"))
                        .isEqualTo("NOT_PUSHED");
                assertThat(singleLong(statement,
                                "SELECT count(*) FROM app.source_return_export_invalidations "
                                        + "WHERE source_return_export_id=930001"))
                        .isEqualTo(1L);
            }
        }
    }

    @Test
    void committedPushClaimDefeatsConcurrentInvalidationWhenPushLocksFirst() throws Exception {
        seedConcurrentInvalidationFixture(940001);

        try (Connection pushing = connection();
                Connection invalidation = connection();
                Connection observer = connection();
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            beginBoundedTransaction(pushing);
            beginBoundedTransaction(invalidation);
            long invalidationPid = backendPid(invalidation);

            try (Statement statement = pushing.createStatement()) {
                assertThat(statement.executeUpdate(pushClaimUpdate(940001, "operator-push-first")))
                        .isEqualTo(1);
            }

            CountDownLatch invalidationIssued = new CountDownLatch(1);
            Future<SQLException> invalidationOutcome = worker.submit(() -> executeAndCommit(
                    invalidation,
                    invalidationInsert(940001),
                    invalidationIssued));
            awaitIssued(invalidationIssued);
            awaitLockWait(observer, invalidationPid);

            pushing.commit();
            SQLException failure = invalidationOutcome.get(10, TimeUnit.SECONDS);

            assertThat((Throwable) failure)
                    .as("the invalidation must observe the committed PUSHING state after its lock wait")
                    .isNotNull()
                    .hasMessageContaining("a pushing or pushed source return export cannot be invalidated");
            try (Statement statement = observer.createStatement()) {
                assertThat(singleString(statement,
                                "SELECT push_status FROM app.source_return_exports WHERE id=940001"))
                        .isEqualTo("PUSHING");
                assertThat(singleLong(statement,
                                "SELECT count(*) FROM app.source_return_export_invalidations "
                                        + "WHERE source_return_export_id=940001"))
                        .isZero();
            }
        }
    }

    @Test
    void committedOnlineSyncDefeatsConcurrentFileFallbackWhenOnlineSyncLocksFirst() throws Exception {
        seedConcurrentSourceSyncMutexFixture(950001);

        try (Connection onlineSync = connection();
                Connection fileFallback = connection();
                Connection observer = connection();
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            beginBoundedTransaction(onlineSync);
            beginBoundedTransaction(fileFallback);
            long fileFallbackPid = backendPid(fileFallback);

            try (Statement statement = onlineSync.createStatement()) {
                assertThat(statement.executeUpdate(
                                syncingUpdate(950001, "intent-online-first-950001", "platform-online-first-950001")))
                        .isEqualTo(1);
            }

            CountDownLatch fallbackIssued = new CountDownLatch(1);
            Future<SQLException> fallbackOutcome = worker.submit(() -> executeAndCommit(
                    fileFallback,
                    pushClaimUpdate(950001, "operator-online-first"),
                    fallbackIssued));
            awaitIssued(fallbackIssued);
            awaitLockWait(observer, fileFallbackPid);

            onlineSync.commit();
            SQLException failure = fallbackOutcome.get(10, TimeUnit.SECONDS);

            assertThat((Throwable) failure)
                    .as("the fallback claim must observe the committed online sync after its lock wait")
                    .isNotNull()
                    .hasMessageContaining("active shipment source sync");
            try (Statement statement = observer.createStatement()) {
                assertThat(singleString(statement,
                                "SELECT sync_status FROM app.shipment_syncs WHERE id=950001"))
                        .isEqualTo("SYNCING");
                assertThat(singleString(statement,
                                "SELECT push_status FROM app.source_return_exports WHERE id=950001"))
                        .isEqualTo("NOT_PUSHED");
            }
        }
    }

    @Test
    void committedFileFallbackDefeatsConcurrentOnlineSyncWhenFileFallbackLocksFirst() throws Exception {
        seedConcurrentSourceSyncMutexFixture(960001);

        try (Connection fileFallback = connection();
                Connection onlineSync = connection();
                Connection observer = connection();
                ExecutorService worker = Executors.newSingleThreadExecutor()) {
            beginBoundedTransaction(fileFallback);
            beginBoundedTransaction(onlineSync);
            long onlineSyncPid = backendPid(onlineSync);

            try (Statement statement = fileFallback.createStatement()) {
                assertThat(statement.executeUpdate(
                                pushClaimUpdate(960001, "operator-fallback-first")))
                        .isEqualTo(1);
            }

            CountDownLatch onlineSyncIssued = new CountDownLatch(1);
            Future<SQLException> onlineSyncOutcome = worker.submit(() -> executeAndCommit(
                    onlineSync,
                    syncingUpdate(960001, "intent-fallback-first-960001", "platform-fallback-first-960001"),
                    onlineSyncIssued));
            awaitIssued(onlineSyncIssued);
            awaitLockWait(observer, onlineSyncPid);

            fileFallback.commit();
            SQLException failure = onlineSyncOutcome.get(10, TimeUnit.SECONDS);

            assertThat((Throwable) failure)
                    .as("the online sync must observe the committed fallback claim after its lock wait")
                    .isNotNull()
                    .hasMessageContaining("active source return fallback");
            try (Statement statement = observer.createStatement()) {
                assertThat(singleString(statement,
                                "SELECT push_status FROM app.source_return_exports WHERE id=960001"))
                        .isEqualTo("PUSHING");
                assertThat(singleString(statement,
                                "SELECT sync_status FROM app.shipment_syncs WHERE id=960001"))
                        .isEqualTo("PENDING");
            }
        }
    }

    private static String syncingUpdate(long id, String intentKey, String platformIntentKey) {
        return """
                UPDATE app.shipment_syncs
                SET sync_status='SYNCING',
                    intent_key='%s',
                    platform_intent_key='%s',
                    check_hash='%s',
                    artifact_hash='%s',
                    source_line_ref='SUB-%d',
                    carrier_code='JD',
                    tracking_number='JDVA%d',
                    intent_started_at=CURRENT_TIMESTAMP,
                    effect_started_at=NULL,
                    attempt_count=attempt_count+1,
                    lock_version=lock_version+1
                WHERE id=%d
                """.formatted(intentKey, platformIntentKey, HASH_A, HASH_B, id, id, id);
    }

    private static String pushClaimUpdate(long id, String operator) {
        return """
                UPDATE app.source_return_exports
                SET push_status='PUSHING', push_started_at=CURRENT_TIMESTAMP, pushed_by='%s'
                WHERE id=%d
                """.formatted(operator, id);
    }

    private static String invalidationInsert(long id) {
        return """
                INSERT INTO app.source_return_export_invalidations
                    (id, source_return_export_id, source_attribution_correction_id,
                     reason_code, invalidated_by)
                VALUES (%d, %d, %d, 'SOURCE_ATTRIBUTION_CORRECTED', 'migration-race-test')
                """.formatted(id, id, id);
    }

    private static void seedConcurrentInvalidationFixture(long id) throws Exception {
        try (Connection setup = connection(); Statement statement = setup.createStatement()) {
            seedShipment(statement, id);
            seedFileFallback(statement, id);
            statement.executeUpdate(
                    """
                    INSERT INTO app.source_attribution_corrections
                        (id, import_batch_id, correction_no, attributed_source_channel,
                         attributed_template_family, attributed_template_fingerprint,
                         reason, corrected_by)
                    VALUES (%d, %d, 1, 'DAZHE', 'dazhe', 'fixture-correction',
                            'concurrency regression fixture', 'migration-race-test')
                    """.formatted(id, id));
        }
    }

    private static void seedConcurrentSourceSyncMutexFixture(long id) throws Exception {
        try (Connection setup = connection(); Statement statement = setup.createStatement()) {
            seedShipment(statement, id);
            seedFileFallback(statement, id);
            statement.executeUpdate(
                    """
                    INSERT INTO app.shipment_syncs (id, shipment_id, source_channel)
                    VALUES (%d, %d, 'JUFUBAO')
                    """.formatted(id, id));
        }
    }

    private static void beginBoundedTransaction(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '10s'");
        }
    }

    private static long backendPid(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            return singleLong(statement, "SELECT pg_backend_pid()");
        }
    }

    private static SQLException executeAndCommit(
            Connection connection,
            String sql,
            CountDownLatch issued) {
        issued.countDown();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            connection.commit();
            return null;
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            return failure;
        }
    }

    private static void awaitIssued(CountDownLatch issued) throws Exception {
        assertThat(issued.await(5, TimeUnit.SECONDS))
                .as("the competing SQL command must be issued before observing PostgreSQL")
                .isTrue();
    }

    private static void awaitLockWait(Connection observer, long backendPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (var statement = observer.prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE pid=? AND state='active' AND wait_event_type='Lock'
                    )
                    """)) {
                statement.setLong(1, backendPid);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while waiting for PostgreSQL row lock");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("backend " + backendPid + " did not enter a PostgreSQL lock wait");
    }

    private static void seedShipment(Statement statement, long id) throws Exception {
        statement.execute("SET session_replication_role = replica");
        try {
            statement.executeUpdate(
                    """
                    INSERT INTO app.shipments
                        (id, shipment_no, order_id, fulfillment_provider_id, outbound_order_no,
                         shipment_sequence, receiver_name_snapshot, receiver_phone_snapshot,
                         receiver_address_snapshot, shipment_status)
                    VALUES (%d, 'SHIP-%d', %d, %d, '%012d', 1, 'Receiver', '13800000000',
                            'Address', 'CREATED')
                    """.formatted(id, id, id, id, id % 1_000_000_000_000L));
        } finally {
            statement.execute("SET session_replication_role = origin");
        }
    }

    private static void seedFileFallback(Statement statement, long id) throws Exception {
        statement.execute("SET session_replication_role = replica");
        try {
            statement.executeUpdate(
                    """
                    INSERT INTO app.import_batches
                        (id, batch_no, batch_type, import_mode, revision_no, source_channel,
                         template_family, template_version, template_fingerprint, original_file_name,
                         content_sha256, file_ref, status, uploaded_by)
                    VALUES (%d, 'BATCH-%d', 'SOURCE_ORDER', 'NEW', 1, 'JUFUBAO',
                            'jufubao', 'v1', 'fixture', 'source.xlsx', '%s', '/source.xlsx',
                            'COMPLETED', 'migration-test')
                    """.formatted(id, id, "%064x".formatted(id)));
            statement.executeUpdate(
                    """
                    INSERT INTO app.raw_import_rows
                        (id, import_batch_id, sheet_name, sheet_index, row_index, raw_cells, status)
                    VALUES (%d, %d, 'orders', 0, 1, '{}'::jsonb, 'ACCEPTED')
                    """.formatted(id, id));
            statement.executeUpdate(
                    """
                    INSERT INTO app.source_return_exports
                        (id, import_batch_id, version_no, is_final, template_version,
                         tracking_cutoff_at, file_ref, file_sha256, generated_by)
                    VALUES (%d, %d, 1, FALSE, 'v1', CURRENT_TIMESTAMP, '/return.xlsx', '%s',
                            'migration-test')
                    """.formatted(id, id, HASH_B));
            statement.executeUpdate(
                    """
                    INSERT INTO app.source_return_export_items
                        (id, source_return_export_id, raw_import_row_id, shipment_id, item_result,
                         output_sheet_name, output_row_index, exception_reason, output_cells)
                    VALUES (%d, %d, %d, %d, 'EXCEPTION', 'orders', 1, 'fixture', '{}'::jsonb)
                    """.formatted(id, id, id, id));
        } finally {
            statement.execute("SET session_replication_role = origin");
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static long singleLong(Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            long value = result.getLong(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }

    private static String singleString(Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            String value = result.getString(1);
            assertThat(result.next()).isFalse();
            return value;
        }
    }
}
