package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentAttemptStore.ClaimResult;
import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentAttemptStore.Decision;
import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentAttemptStore.ShipmentAttemptPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link JufubaoShipmentAttemptStore} 持久幂等定向集成测试（Issue #99）。
 *
 * <p>复用共享注册表 app.idempotency_registry；所有跨重启断言都通过「新 store 实例」
 * （{@link #freshStore()}，无进程内状态，模拟应用重启）完成，证明重放行为完全由注册表行决定。
 * 每个用例使用独立的 sub_order_id + tracking_no，避免用例之间互相污染注册表行。
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.idempotency.lease-seconds=60",
    "app.jd.client-mode=MOCK",
    "spring.data.redis.repositories.enabled=false"
})
class JufubaoShipmentAttemptStoreIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JufubaoShipmentAttemptStore store;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** 模拟应用重启：新实例没有任何进程内状态，行为只由注册表行决定。 */
    private JufubaoShipmentAttemptStore freshStore() {
        return new JufubaoShipmentAttemptStoreJdbc(
                new JdbcTemplate(dataSource), objectMapper, transactionManager, 60L);
    }

    @Test
    void firstClaimProceedsAndActiveLeaseSeesInProgress() {
        ShipmentAttemptPayload payload = payload("sub-1", "JDVA001");

        ClaimResult first = store.claim(payload);

        assertThat(first.decision()).isEqualTo(Decision.PROCEED);
        assertThat(first.ownerToken()).isNotBlank();
        assertThat(first.replay()).isNull();
        Map<String, Object> row = jdbc().queryForMap(
                "SELECT scope, status, effect_started_at, attempt_count, owner_token, lease_expires_at "
                        + "FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-1", "JDVA001"));
        assertThat(row)
                .containsEntry("scope", "jufubao.shipment")
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("effect_started_at", null)
                .containsEntry("attempt_count", 1)
                .containsEntry("owner_token", first.ownerToken());

        // 租约仍有效：同 key 同 payload 的并发 claim 必须退避，绝不重复提交。
        ClaimResult concurrent = store.claim(payload);
        assertThat(concurrent.decision()).isEqualTo(Decision.IN_PROGRESS);
        assertThat(concurrent.ownerToken()).isNull();
    }

    @Test
    void simultaneousClaimsProduceExactlyOneOwner() throws Exception {
        ShipmentAttemptPayload payload = payload("sub-concurrent", "JDVA-CONCURRENT");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<ClaimResult> first = concurrentClaim(freshStore(), payload, ready, start, executor);
            CompletableFuture<ClaimResult> second = concurrentClaim(freshStore(), payload, ready, start, executor);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ClaimResult one = first.get(10, TimeUnit.SECONDS);
            ClaimResult two = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one.decision(), two.decision()))
                    .containsExactlyInAnyOrder(Decision.PROCEED, Decision.IN_PROGRESS);
            assertThat(List.of(one, two).stream().filter(result -> result.ownerToken() != null).toList())
                    .singleElement()
                    .satisfies(result -> assertThat(result.ownerToken()).isNotBlank());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulOutcomeIsReplayedByAFreshStoreInstance() {
        ShipmentAttemptPayload payload = payload("sub-2", "JDVA002");
        ClaimResult claim = store.claim(payload);
        SourceSyncResult outcome = SourceSyncResult.ok("req-1");
        store.markEffectStarted("sub-2", "JDVA002", claim.ownerToken());
        store.completeSuccess("sub-2", "JDVA002", claim.ownerToken(), outcome);

        ClaimResult replay = freshStore().claim(payload);

        assertThat(replay.decision()).isEqualTo(Decision.REPLAY);
        assertSameResult(replay.replay(), outcome);
        assertThat(replay.ownerToken()).isNull();
        assertThat(jdbc().queryForMap(
                "SELECT status FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-2", "JDVA002")))
                .containsEntry("status", "SUCCEEDED");
    }

    @Test
    void unknownOutcomeSurvivesRestartAndNeverProceeds() {
        ShipmentAttemptPayload payload = payload("sub-3", "JDVA003");
        ClaimResult claim = store.claim(payload);
        SourceSyncResult unknown = SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED", "聚福宝发货结果未知；禁止盲目重提，请到平台核对", "req-u");
        store.markEffectStarted("sub-3", "JDVA003", claim.ownerToken());
        store.completeUnknown("sub-3", "JDVA003", claim.ownerToken(), unknown);

        // 模拟重启：新实例第一次 claim 必须重放 UNKNOWN，而不是 PROCEED。
        JufubaoShipmentAttemptStore restarted = freshStore();
        ClaimResult replay = restarted.claim(payload);
        assertThat(replay.decision()).isEqualTo(Decision.REPLAY);
        assertSameResult(replay.replay(), unknown);
        assertThat(replay.ownerToken()).isNull();

        // 之后任何 claim 仍然重放同一未知结果，绝不回退到 PROCEED。
        ClaimResult again = restarted.claim(payload);
        assertThat(again.decision()).isEqualTo(Decision.REPLAY);
        assertSameResult(again.replay(), unknown);
        assertThat(jdbc().queryForMap(
                "SELECT status FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-3", "JDVA003")))
                .containsEntry("status", "RECONCILIATION_REQUIRED");
    }

    @Test
    void reconciledNotAcceptedReleasesOnlyTheOriginalPersistedIntentAndPreservesEvidence() {
        ShipmentAttemptPayload payload = payload("sub-reconciled", "JDVA-RECONCILED");
        ClaimResult claim = store.claim(payload);
        SourceSyncResult unknown = SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED", "聚福宝发货结果未知", "req-reconciled");
        store.markEffectStarted("sub-reconciled", "JDVA-RECONCILED", claim.ownerToken());
        store.completeUnknown("sub-reconciled", "JDVA-RECONCILED", claim.ownerToken(), unknown);
        String originalIntent = JufubaoShipmentAttemptStore.idempotencyKey(
                "sub-reconciled", "JDVA-RECONCILED");

        assertThat(store.releaseReconciledNotAccepted("JUFUBAO:other:tracking")).isFalse();
        assertThat(store.releaseReconciledNotAccepted(originalIntent)).isTrue();
        assertThat(store.releaseReconciledNotAccepted(originalIntent)).isTrue();

        Map<String, Object> released = jdbc().queryForMap(
                "SELECT status, effect_started_at, response_snapshot FROM app.idempotency_registry "
                        + "WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE,
                originalIntent);
        assertThat(released)
                .containsEntry("status", "FAILED")
                .containsEntry("effect_started_at", null);
        assertThat(released.get("response_snapshot")).isNotNull();
        assertThat(freshStore().claim(payload).decision()).isEqualTo(Decision.PROCEED);
    }

    @Test
    void reconciledNotAcceptedCanReleaseAnExpiredInProgressInnerIntentButNotAnActiveOne() {
        ShipmentAttemptPayload payload = payload("sub-expired-reconcile", "JDVA-EXPIRED-RECONCILE");
        ClaimResult claim = store.claim(payload);
        store.markEffectStarted(
                "sub-expired-reconcile", "JDVA-EXPIRED-RECONCILE", claim.ownerToken());
        String intent = JufubaoShipmentAttemptStore.idempotencyKey(
                "sub-expired-reconcile", "JDVA-EXPIRED-RECONCILE");

        assertThat(store.releaseReconciledNotAccepted(intent)).isFalse();
        jdbc().update(
                "UPDATE app.idempotency_registry SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' "
                        + "WHERE scope=? AND idempotency_key=?",
                JufubaoShipmentAttemptStore.SCOPE,
                intent);

        assertThat(store.releaseReconciledNotAccepted(intent)).isTrue();
        assertThat(jdbc().queryForMap(
                        "SELECT status, effect_started_at, owner_token FROM app.idempotency_registry "
                                + "WHERE scope=? AND idempotency_key=?",
                        JufubaoShipmentAttemptStore.SCOPE,
                        intent))
                .containsEntry("status", "FAILED")
                .containsEntry("effect_started_at", null)
                .containsEntry("owner_token", null);
    }

    @Test
    void differentPayloadForSameKeyIsConflict() {
        ShipmentAttemptPayload original = payload("sub-4", "JDVA004");
        ClaimResult claim = store.claim(original);

        // 未完成时同 key 不同 payload：CONFLICT。
        ClaimResult whileRunning = store.claim(payload("sub-4", "JDVA004", 2L, "顺丰速运"));
        assertThat(whileRunning.decision()).isEqualTo(Decision.CONFLICT);

        store.markEffectStarted("sub-4", "JDVA004", claim.ownerToken());
        store.completeSuccess("sub-4", "JDVA004", claim.ownerToken(), SourceSyncResult.ok("req-1"));

        // 完成后同 key 不同 payload：仍然 CONFLICT，且行状态不被污染。
        ClaimResult afterCompletion = freshStore().claim(payload("sub-4", "JDVA004", 2L, "顺丰速运"));
        assertThat(afterCompletion.decision()).isEqualTo(Decision.CONFLICT);
        assertThat(jdbc().queryForMap(
                "SELECT status FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-4", "JDVA004")))
                .containsEntry("status", "SUCCEEDED");
    }

    @Test
    void expiredLeaseWithoutEffectCanBeTakenOverAndFencesTheOldOwner() {
        ShipmentAttemptPayload payload = payload("sub-5", "JDVA005");
        ClaimResult first = store.claim(payload);
        expireLease("sub-5", "JDVA005");

        // 租约过期且 effect_started_at 为空：新实例可以安全接管。
        ClaimResult takeover = freshStore().claim(payload);
        assertThat(takeover.decision()).isEqualTo(Decision.PROCEED);
        assertThat(takeover.ownerToken()).isNotBlank();

        // fencing：旧 owner 的写前标记与结果登记都必须被拒绝，行保持新 owner 的 IN_PROGRESS。
        assertThatThrownBy(() -> store.markEffectStarted("sub-5", "JDVA005", first.ownerToken()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("JUFUBAO_IDEMPOTENCY_CLAIM_LOST"));
        assertThatThrownBy(() -> store.completeSuccess(
                        "sub-5", "JDVA005", first.ownerToken(), SourceSyncResult.ok("stale")))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("JUFUBAO_IDEMPOTENCY_CLAIM_LOST"));
        assertThat(jdbc().queryForMap(
                "SELECT status, owner_token, effect_started_at FROM app.idempotency_registry "
                        + "WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-5", "JDVA005")))
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("owner_token", takeover.ownerToken())
                .containsEntry("effect_started_at", null);

        // 新 owner 正常完成，随后重放新结果。
        store.markEffectStarted("sub-5", "JDVA005", takeover.ownerToken());
        SourceSyncResult outcome = SourceSyncResult.ok("req-2");
        store.completeSuccess("sub-5", "JDVA005", takeover.ownerToken(), outcome);
        assertSameResult(freshStore().claim(payload).replay(), outcome);
    }

    @Test
    void expiredLeaseCannotAcquireAWritePermitEvenWhenTheOwnerWasNotTakenOver() {
        ShipmentAttemptPayload payload = payload("sub-owner", "JDVA-OWNER");
        ClaimResult claim = store.claim(payload);
        store.markEffectStarted("sub-owner", "JDVA-OWNER", claim.ownerToken());
        expireLease("sub-owner", "JDVA-OWNER");

        assertThatThrownBy(() -> store.verifyWritePermit(
                        "sub-owner", "JDVA-OWNER", claim.ownerToken()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("JUFUBAO_IDEMPOTENCY_CLAIM_LOST"));
    }

    @Test
    void expiredPreWriteOwnerCanReleaseForSafeRetryWhenNobodyTookOver() {
        ShipmentAttemptPayload payload = payload("sub-release", "JDVA-RELEASE");
        ClaimResult claim = store.claim(payload);
        expireLease("sub-release", "JDVA-RELEASE");

        store.release(
                "sub-release",
                "JDVA-RELEASE",
                claim.ownerToken(),
                "JUFUBAO_PLATFORM_UNAVAILABLE",
                "尚未发出外部写");

        assertThat(freshStore().claim(payload).decision()).isEqualTo(Decision.PROCEED);
    }

    @Test
    void expiredLeaseWithEffectStartedTransitionsToReconciliationRequiredMonotonically() {
        ShipmentAttemptPayload payload = payload("sub-6", "JDVA006");
        ClaimResult claim = store.claim(payload);
        store.markEffectStarted("sub-6", "JDVA006", claim.ownerToken());
        expireLease("sub-6", "JDVA006");

        // 租约过期且效果已开始：不允许接管，单调转 RECONCILIATION_REQUIRED。
        ClaimResult first = freshStore().claim(payload);
        assertThat(first.decision()).isEqualTo(Decision.RECONCILIATION_REQUIRED);
        assertThat(first.replay()).isNotNull();
        assertThat(first.replay().businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(first.ownerToken()).isNull();

        // 单调性：行已是 RECONCILIATION_REQUIRED，任何后续 claim 都只重放，绝不给 PROCEED。
        ClaimResult second = freshStore().claim(payload);
        assertThat(second.decision()).isEqualTo(Decision.REPLAY);
        assertThat(second.replay().businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc().queryForMap(
                "SELECT status FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-6", "JDVA006")))
                .containsEntry("status", "RECONCILIATION_REQUIRED");

        // 同 key 不同 payload 也只会 CONFLICT，同样不会得到 PROCEED。
        assertThat(freshStore().claim(payload("sub-6", "JDVA006", 2L, "顺丰速运")).decision())
                .isEqualTo(Decision.CONFLICT);
    }

    @Test
    void simultaneousClaimsAfterEffectStartedExpiryNeverProceed() throws Exception {
        ShipmentAttemptPayload payload = payload("sub-expired-concurrent", "JDVA-EXPIRED-CONCURRENT");
        ClaimResult owner = store.claim(payload);
        store.markEffectStarted(
                "sub-expired-concurrent", "JDVA-EXPIRED-CONCURRENT", owner.ownerToken());
        expireLease("sub-expired-concurrent", "JDVA-EXPIRED-CONCURRENT");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<ClaimResult> first = concurrentClaim(freshStore(), payload, ready, start, executor);
            CompletableFuture<ClaimResult> second = concurrentClaim(freshStore(), payload, ready, start, executor);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Decision> decisions = List.of(
                    first.get(10, TimeUnit.SECONDS).decision(),
                    second.get(10, TimeUnit.SECONDS).decision());
            assertThat(decisions).doesNotContain(Decision.PROCEED);
            assertThat(decisions).allMatch(
                    decision -> decision == Decision.RECONCILIATION_REQUIRED || decision == Decision.REPLAY);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releaseAllowsSafeRetryAfterPreWriteFailureOrPlatformRejection() {
        // 写前失败（如承运商未映射）：release 后同 key 同 payload 可安全重试。
        ShipmentAttemptPayload payload = payload("sub-7", "JDVA007");
        ClaimResult preWrite = store.claim(payload);
        store.release("sub-7", "JDVA007", preWrite.ownerToken(), "JUFUBAO_CARRIER_UNMAPPED", "物流公司未映射");

        ClaimResult retry = freshStore().claim(payload);
        assertThat(retry.decision()).isEqualTo(Decision.PROCEED);
        assertThat(jdbc().queryForMap(
                "SELECT attempt_count FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-7", "JDVA007")))
                .containsEntry("attempt_count", 2);

        // 平台明确拒绝（effect 标记之后）：release 后同样允许安全重试。
        ShipmentAttemptPayload rejectedPayload = payload("sub-8", "JDVA008");
        ClaimResult rejected = store.claim(rejectedPayload);
        store.markEffectStarted("sub-8", "JDVA008", rejected.ownerToken());
        store.release("sub-8", "JDVA008", rejected.ownerToken(), "InvalidArgument", "快递单号非法");
        assertThat(store.releaseReconciledNotAccepted(
                JufubaoShipmentAttemptStore.idempotencyKey("sub-8", "JDVA008"))).isTrue();

        ClaimResult retryRejected = freshStore().claim(rejectedPayload);
        assertThat(retryRejected.decision()).isEqualTo(Decision.PROCEED);
        assertThat(retryRejected.ownerToken()).isNotBlank();
    }

    @Test
    void markEffectStartedIsCommittedBeforeExternalWriteAndBlocksTakeover() {
        ShipmentAttemptPayload payload = payload("sub-9", "JDVA009");
        ClaimResult claim = store.claim(payload);

        store.markEffectStarted("sub-9", "JDVA009", claim.ownerToken());
        store.verifyWritePermit("sub-9", "JDVA009", claim.ownerToken());

        // REQUIRES_NEW 已提交：独立连接立即可见 effect_started_at，且行仍为 IN_PROGRESS。
        assertThat(jdbc().queryForMap(
                "SELECT status, effect_started_at FROM app.idempotency_registry "
                        + "WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE, JufubaoShipmentAttemptStore.idempotencyKey("sub-9", "JDVA009")))
                .containsEntry("status", "IN_PROGRESS")
                .doesNotContainEntry("effect_started_at", null);

        // 效果标记已持久化：租约有效期内任何实例的 claim 都是 IN_PROGRESS，不是 PROCEED。
        assertThat(freshStore().claim(payload).decision()).isEqualTo(Decision.IN_PROGRESS);
    }

    @Test
    void storedSnapshotsCarryOnlySourceSyncResultContractFields() throws Exception {
        ShipmentAttemptPayload payload = payload("sub-10", "JDVA010");
        ClaimResult claim = store.claim(payload);
        store.markEffectStarted("sub-10", "JDVA010", claim.ownerToken());
        store.completeSuccess("sub-10", "JDVA010", claim.ownerToken(), SourceSyncResult.ok("req-1"));

        JsonNode snapshot = objectMapper.readTree(jdbc().queryForObject(
                "SELECT response_snapshot::text FROM app.idempotency_registry WHERE scope = ? AND idempotency_key = ?",
                String.class,
                JufubaoShipmentAttemptStore.SCOPE,
                JufubaoShipmentAttemptStore.idempotencyKey("sub-10", "JDVA010")));
        // 应用 ObjectMapper 为全契约 snake_case：快照只承载 SourceSyncResult 契约字段。
        assertThat(snapshot.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(
                Set.of("success", "business_code", "message", "platform_ref", "synced_at"));
        // 存储中绝不允许出现 Cookie/Token/PII 类字段。
        assertThat(snapshot.toString().toLowerCase())
                .doesNotContain("cookie", "token", "password", "phone", "address");
    }

    private ShipmentAttemptPayload payload(String subOrderId, String trackingNo) {
        return new ShipmentAttemptPayload("main-1", subOrderId, 1L, "京东物流", trackingNo);
    }

    private ShipmentAttemptPayload payload(String subOrderId, String trackingNo, long quantity, String carrier) {
        return new ShipmentAttemptPayload("main-1", subOrderId, quantity, carrier, trackingNo);
    }

    /** 结果内容相等：四个业务字段完全一致，syncedAt 只要求同一时刻（时区偏移规范化后一致）。 */
    private void assertSameResult(SourceSyncResult actual, SourceSyncResult expected) {
        assertThat(actual).isNotNull();
        assertThat(actual.success()).isEqualTo(expected.success());
        assertThat(actual.businessCode()).isEqualTo(expected.businessCode());
        assertThat(actual.message()).isEqualTo(expected.message());
        assertThat(actual.platformRef()).isEqualTo(expected.platformRef());
        assertThat(actual.syncedAt().toInstant()).isEqualTo(expected.syncedAt().toInstant());
    }

    private void expireLease(String subOrderId, String trackingNo) {
        jdbc().update(
                "UPDATE app.idempotency_registry SET lease_expires_at = statement_timestamp() - INTERVAL '1 second' "
                        + "WHERE scope = ? AND idempotency_key = ?",
                JufubaoShipmentAttemptStore.SCOPE,
                JufubaoShipmentAttemptStore.idempotencyKey(subOrderId, trackingNo));
    }

    private CompletableFuture<ClaimResult> concurrentClaim(
            JufubaoShipmentAttemptStore claimant,
            ShipmentAttemptPayload payload,
            CountDownLatch ready,
            CountDownLatch start,
            ExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发 claim 栅栏等待超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发 claim 被中断", exception);
            }
            return claimant.claim(payload);
        }, executor);
    }
}
