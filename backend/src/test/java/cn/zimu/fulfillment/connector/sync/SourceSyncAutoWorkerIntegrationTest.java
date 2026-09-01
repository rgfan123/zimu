package cn.zimu.fulfillment.connector.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.ConnectionTestResult;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ConnectorRuntime;
import cn.zimu.fulfillment.connector.PlatformConnector;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
    "app.source-sync.auto.enabled=false",
    "app.source-sync.recovery.enabled=false",
    "app.message-worker.enabled=false"
})
class SourceSyncAutoWorkerIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceSyncAutoStateStore states;

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE app.source_sync_auto_states, app.trackings, app.shipments, app.orders,"
                        + " app.import_batches, app.customers, app.fulfillment_providers"
                        + " RESTART IDENTITY CASCADE");
    }

    @Test
    void fileOnlyChannelsArePersistedOnceAsNotApplicableAndNeverPolledAgain(CapturedOutput output) {
        long dazhe = seedCandidate(SourceChannel.DAZHE);
        long zhonghui = seedCandidate(SourceChannel.ZHONGHUI);
        AtomicInteger checks = new AtomicInteger();
        SourceSyncAutoWorker worker = worker(
                serviceReturning(checks, blockedCheck("SOURCE_PLATFORM_CHECK_UNAVAILABLE")),
                new PlatformConnectorRegistry(List.of()));

        worker.poll();
        SourceSyncAutoStateStore.State dazheState = states.find(dazhe, SourceChannel.DAZHE).orElseThrow();
        OffsetDateTime firstUpdatedAt = dazheState.updatedAt();
        assertThat(states.claimCandidates("second-owner", Duration.ofMinutes(10), 20))
                .as("结构性终态不得再次成为候选")
                .isEmpty();
        worker.poll();

        assertThat(checks).hasValue(0);
        assertThat(dazheState.disposition())
                .isEqualTo(SourceSyncAutoStateStore.Disposition.NOT_APPLICABLE);
        assertThat(dazheState.reasonCode()).isEqualTo(SourceSyncAutoStateStore.FILE_RETURN_ONLY);
        assertThat(dazheState.attemptCount()).isZero();
        assertThat(dazheState.nextAttemptAt()).isNull();
        assertThat(states.find(zhonghui, SourceChannel.ZHONGHUI)).get()
                .extracting(SourceSyncAutoStateStore.State::disposition)
                .isEqualTo(SourceSyncAutoStateStore.Disposition.NOT_APPLICABLE);
        assertThat(states.find(dazhe, SourceChannel.DAZHE)).get()
                .extracting(SourceSyncAutoStateStore.State::updatedAt)
                .isEqualTo(firstUpdatedAt);
        assertThat(output)
                .doesNotContain("shipment=" + dazhe)
                .doesNotContain("shipment=" + zhonghui);
    }

    @Test
    void onlinePushConnectorRetriesWithExponentialBackoffInsteadOfEveryPoll() {
        long shipmentId = seedCandidate(SourceChannel.JUFUBAO);
        AtomicInteger checks = new AtomicInteger();
        SourceSyncAutoWorker worker = worker(
                serviceReturning(checks, blockedCheck("SOURCE_PLATFORM_CHECK_UNAVAILABLE")),
                new PlatformConnectorRegistry(List.of(onlinePushConnector(SourceChannel.JUFUBAO))));

        worker.poll();
        SourceSyncAutoStateStore.State first = states.find(shipmentId, SourceChannel.JUFUBAO).orElseThrow();
        worker.poll();

        assertThat(checks).hasValue(1);
        assertThat(first.disposition()).isEqualTo(SourceSyncAutoStateStore.Disposition.RETRY_WAIT);
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isAfter(first.updatedAt().plusMinutes(1));

        jdbc.update(
                "UPDATE app.source_sync_auto_states SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                        + " WHERE shipment_id=? AND source_channel='JUFUBAO'",
                shipmentId);
        worker.poll();

        SourceSyncAutoStateStore.State second = states.find(shipmentId, SourceChannel.JUFUBAO).orElseThrow();
        assertThat(checks).hasValue(2);
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.nextAttemptAt()).isAfter(second.updatedAt().plusMinutes(3));
    }

    @Test
    void oneLeaseOwnerExcludesOtherInstancesUntilTheClaimExpires() {
        long shipmentId = seedCandidate(SourceChannel.JUFUBAO);

        List<SourceSyncAutoStateStore.Claim> first =
                states.claimCandidates("worker-a", Duration.ofMinutes(10), 20);
        List<SourceSyncAutoStateStore.Claim> second =
                states.claimCandidates("worker-b", Duration.ofMinutes(10), 20);

        assertThat(first).extracting(SourceSyncAutoStateStore.Claim::shipmentId).contains(shipmentId);
        assertThat(second).isEmpty();
        states.defer(first.getFirst(), "TEST_CLEANUP", Duration.ofMinutes(10));
    }

    @Test
    void deterministicBusinessExceptionIsDeferredWithoutEnteringFailureBackoff() {
        long shipmentId = seedCandidate(SourceChannel.JUFUBAO);
        AtomicInteger checks = new AtomicInteger();
        SourceSyncAutoWorker worker = worker(
                serviceThrowing(checks, "SOURCE_PLATFORM_CARRIER_UNMAPPED"),
                new PlatformConnectorRegistry(List.of(onlinePushConnector(SourceChannel.JUFUBAO))));

        worker.poll();
        worker.poll();

        assertThat(checks).hasValue(1);
        assertThat(states.find(shipmentId, SourceChannel.JUFUBAO)).get().satisfies(state -> {
            assertThat(state.disposition()).isEqualTo(SourceSyncAutoStateStore.Disposition.PENDING);
            assertThat(state.reasonCode()).isEqualTo("SOURCE_PLATFORM_CARRIER_UNMAPPED");
            assertThat(state.attemptCount()).isZero();
            assertThat(state.nextAttemptAt()).isAfter(state.updatedAt().plusMinutes(9));
        });
    }

    @Test
    void staleOwnerCannotCompleteAfterAnExpiredLeaseWasReclaimed() {
        long shipmentId = seedCandidate(SourceChannel.JUFUBAO);
        SourceSyncAutoStateStore.Claim stale =
                states.claimCandidates("worker-a", Duration.ofMinutes(10), 20).getFirst();
        jdbc.update(
                "UPDATE app.source_sync_auto_states SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                        + " WHERE shipment_id=? AND source_channel='JUFUBAO'",
                shipmentId);
        SourceSyncAutoStateStore.Claim current =
                states.claimCandidates("worker-b", Duration.ofMinutes(10), 20).getFirst();

        // 类型必须是专属的 LeaseLostException：@Repository 的异常翻译会把 IllegalStateException
        // 改写成 InvalidDataAccessApiUsageException（「持久化 API 用错了」），
        // 把一次正常的租约竞争伪装成代码缺陷，调用方也就无法按类型分流。
        assertThatThrownBy(() -> states.complete(stale))
                .isInstanceOf(SourceSyncAutoStateStore.LeaseLostException.class)
                .hasMessageContaining("租约已丢失");
        assertThat(states.find(shipmentId, SourceChannel.JUFUBAO)).get()
                .extracting(SourceSyncAutoStateStore.State::leaseOwner)
                .isEqualTo("worker-b");
        states.defer(current, "TEST_CLEANUP", Duration.ofMinutes(10));
    }

    private SourceSyncAutoWorker worker(
            SourceShipmentSyncService service, PlatformConnectorRegistry registry) {
        return new SourceSyncAutoWorker(
                service,
                registry,
                states,
                true,
                "source-sync-auto-test",
                20,
                Duration.ofMinutes(2),
                Duration.ofHours(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(10));
    }

    private SourceShipmentSyncService serviceReturning(
            AtomicInteger checks, SourceSyncCheck result) {
        return new SourceShipmentSyncService(
                null, null, null, null, null, null, null, null, Duration.ofMinutes(10)) {
            @Override
            public SourceSyncCheck check(
                    long shipmentId, CommandContext context, AuditActorType actorType) {
                checks.incrementAndGet();
                return result;
            }
        };
    }

    private SourceSyncCheck blockedCheck(String code) {
        return new SourceSyncCheck(
                -1,
                false,
                "a".repeat(64),
                "b".repeat(64),
                null,
                null,
                List.of(new SourceSyncBlocker(
                        code, "platform", "来源平台当前不可用")),
                new SourceSyncProjection(SourceSyncStatus.PENDING, 0, 0, null, null, null));
    }

    private SourceShipmentSyncService serviceThrowing(AtomicInteger checks, String code) {
        return new SourceShipmentSyncService(
                null, null, null, null, null, null, null, null, Duration.ofMinutes(10)) {
            @Override
            public SourceSyncCheck check(
                    long shipmentId, CommandContext context, AuditActorType actorType) {
                checks.incrementAndGet();
                throw BusinessException.unprocessable(code, "确定性业务阻断");
            }
        };
    }

    private PlatformConnector onlinePushConnector(SourceChannel channel) {
        return new PlatformConnector() {
            @Override
            public SourceChannel channel() {
                return channel;
            }

            @Override
            public ConnectorCapabilities capabilities() {
                return new ConnectorCapabilities(true, true, false, true, false);
            }

            @Override
            public ConnectionTestResult testConnection(ConnectorRuntime runtime) {
                throw new UnsupportedOperationException("测试不触发连接检查");
            }
        };
    }

    @Test
    void manualChannelShipmentsAreNeverSyncCandidatesAndDoNotPoisonTheBatch() {
        long manual = seedManualCandidate();
        long jufubao = seedCandidate(SourceChannel.JUFUBAO);

        List<SourceSyncAutoStateStore.Claim> claims =
                states.claimCandidates("manual-exclusion-owner", Duration.ofMinutes(10), 20);

        assertThat(claims)
                .as("MANUAL 无来源平台，绝不参与自动回传；JUFUBAO 候选不得被连坐")
                .extracting(SourceSyncAutoStateStore.Claim::shipmentId)
                .containsExactly(jufubao);
        assertThat(states.find(manual, SourceChannel.MANUAL)).isEmpty();
    }

    /** 手工单形态：无导入批次（V100 反向禁挂），渠道 MANUAL，已发货已回单。 */
    private long seedManualCandidate() {
        String suffix = Long.toUnsignedString(System.nanoTime());
        long customerId = jdbc.queryForObject(
                "INSERT INTO app.customers(customer_code, customer_name) VALUES (?, '手工平台客户') RETURNING id",
                Long.class,
                "CUSTMAN" + suffix);
        long providerId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers(provider_code, provider_name, provider_type)
                VALUES (?, '手工单履约方', 'THIRD_PARTY') RETURNING id
                """,
                Long.class,
                "MANP" + suffix);
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind,
                     customer_id, order_status, settlement_method,
                     settlement_time, receiver_name, receiver_phone, receiver_address)
                VALUES (?, 'BUSINESS', 'MANUAL', ?, 'PROVIDED', ?, 'SHIPPED', 'OTHER',
                        CURRENT_TIMESTAMP, '李四', '13900000000', '河南省郑州市测试路2号')
                RETURNING id
                """,
                Long.class,
                "ORDER-MAN-" + suffix,
                "MAN-" + suffix,
                customerId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES (?, ?, ?, 1, '李四', '13900000000', '河南省郑州市测试路2号',
                        'SHIPPED', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "SHIP-MAN-" + suffix,
                orderId,
                providerId);
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                VALUES (?, 'JD', '京东物流', ?)
                """,
                shipmentId,
                "TRACK-MAN-" + suffix);
        return shipmentId;
    }

    private long seedCandidate(SourceChannel channel) {
        String suffix = Long.toUnsignedString(System.nanoTime());
        long customerId = jdbc.queryForObject(
                "INSERT INTO app.customers(customer_code, customer_name) VALUES (?, '自动回传客户') RETURNING id",
                Long.class,
                "CUSTAUTO" + suffix);
        long providerId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers(provider_code, provider_name, provider_type)
                VALUES (?, '自动回传履约方', 'THIRD_PARTY') RETURNING id
                """,
                Long.class,
                "AUTOP" + suffix);
        long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, source_channel, template_family, template_version,
                     template_fingerprint, original_file_name, content_sha256, file_ref,
                     status, uploaded_by, processed_at)
                VALUES (?, 'SOURCE_ORDER', ?, 'AUTO_TEST', '1', ?, 'auto.xlsx',
                        repeat('c', 64), ?, 'COMPLETED', 'tester', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "BATCH-AUTO-" + suffix,
                channel.name(),
                "fingerprint-" + suffix,
                "test://auto/" + suffix);
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind,
                     source_import_batch_id, customer_id, order_status, settlement_method,
                     settlement_time, receiver_name, receiver_phone, receiver_address)
                VALUES (?, 'BUSINESS', ?, ?, 'PROVIDED', ?, ?, 'SHIPPED', 'OTHER',
                        CURRENT_TIMESTAMP, '张三', '13800000000', '河南省郑州市测试路1号')
                RETURNING id
                """,
                Long.class,
                "ORDER-AUTO-" + suffix,
                channel.name(),
                "SOURCE-AUTO-" + suffix,
                batchId,
                customerId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES (?, ?, ?, 1, '张三', '13800000000', '河南省郑州市测试路1号',
                        'SHIPPED', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "SHIP-AUTO-" + suffix,
                orderId,
                providerId);
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                VALUES (?, 'JD', '京东物流', ?)
                """,
                shipmentId,
                "TRACK-AUTO-" + suffix);
        return shipmentId;
    }
}
