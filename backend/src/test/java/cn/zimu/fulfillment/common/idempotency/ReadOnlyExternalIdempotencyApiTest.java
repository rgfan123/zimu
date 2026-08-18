package cn.zimu.fulfillment.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.basicinfo.JDBasicInfoService;
import cn.zimu.fulfillment.message.InterpretationWorker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 从可复用幂等 seam 验证只读外调发生在 claim 之后、本地事务之前。 */
@Testcontainers
@SpringBootTest(properties = {
    "app.jd.client-mode=REAL",
    "app.jd.server-url=",
    "app.jd.app-key=",
    "app.jd.app-secret=",
    "app.jd.access-token=",
    "spring.data.redis.repositories.enabled=false"
})
class ReadOnlyExternalIdempotencyApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired IdempotencyService idempotency;
    @Autowired JDBasicInfoService jdBasicInfo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean InterpretationWorker interpretationWorker;

    @Test
    void replayAndDifferentPayloadShortCircuitBeforeRealJdQueryAudit() {
        String scope = "test.readonly-external-replay";
        String key = "readonly-external-replay-001";
        Map<String, Object> payload = Map.of("goods_no", "JD-SKU-000001");
        AtomicInteger completions = new AtomicInteger();

        IdempotentResult<Map<String, Object>> first = idempotency.executeWithReadOnlyExternalWork(
                scope,
                key,
                payload,
                200,
                () -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return jdBasicInfo.queryGoodsInfo(Map.of(
                            "goodsNo", "JD-SKU-000001",
                            "queryType", "1")); // 官方枚举：1-查询全部信息
                },
                result -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    completions.incrementAndGet();
                    return Map.of("business_code", result.businessCode());
                });
        IdempotentResult<Map<String, Object>> replay = idempotency.executeWithReadOnlyExternalWork(
                scope,
                key,
                payload,
                200,
                () -> {
                    throw new AssertionError("replay must not query JD");
                },
                ignored -> {
                    throw new AssertionError("replay must not run completion");
                });

        assertThat(first.replayed()).isFalse();
        assertThat(first.result()).containsEntry("business_code", "CREDENTIALS_REQUIRED");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replayedBody().get("business_code").asText()).isEqualTo("CREDENTIALS_REQUIRED");
        assertThat(completions).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE service='jd.isc' AND operation='queryGoodsInfo'",
                Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.idempotency_registry WHERE scope=? AND idempotency_key=?",
                String.class,
                scope,
                key))
                .isEqualTo("SUCCEEDED");

        assertThatThrownBy(() -> idempotency.executeWithReadOnlyExternalWork(
                        scope,
                        key,
                        Map.of("goods_no", "JD-SKU-DIFFERENT"),
                        200,
                        () -> {
                            throw new AssertionError("payload conflict must not query JD");
                        },
                        ignored -> Map.of()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getBusinessCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE service='jd.isc' AND operation='queryGoodsInfo'",
                Long.class))
                .isEqualTo(1L);
    }

    @Test
    void activeClaimRejectsConcurrentReplayWithoutStartingSecondExternalRead() throws Exception {
        String scope = "test.readonly-external-in-progress";
        String key = "readonly-external-in-progress-001";
        Map<String, Object> payload = Map.of("shipment_id", "42");
        CountDownLatch externalStarted = new CountDownLatch(1);
        CountDownLatch releaseExternal = new CountDownLatch(1);
        AtomicInteger externalCalls = new AtomicInteger();

        CompletableFuture<IdempotentResult<Map<String, Object>>> first = CompletableFuture.supplyAsync(() ->
                idempotency.executeWithReadOnlyExternalWork(
                        scope,
                        key,
                        payload,
                        200,
                        () -> {
                            externalCalls.incrementAndGet();
                            externalStarted.countDown();
                            try {
                                if (!releaseExternal.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("timed out waiting to release external read");
                                }
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(ex);
                            }
                            return "read-only-result";
                        },
                        result -> Map.of("result", result)));

        assertThat(externalStarted.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> idempotency.executeWithReadOnlyExternalWork(
                            scope,
                            key,
                            payload,
                            200,
                            () -> {
                                externalCalls.incrementAndGet();
                                return "must-not-run";
                            },
                            result -> Map.of("result", result)))
                    .isInstanceOfSatisfying(BusinessException.class, ex ->
                            assertThat(ex.getBusinessCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
            assertThat(externalCalls).hasValue(1);
        } finally {
            releaseExternal.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).result()).containsEntry("result", "read-only-result");
    }

    @Test
    void preparedReadOnlyReplayUsesCanonicalPayloadAcrossMapInsertionOrder() {
        String scope = "test.prepared-readonly-canonical-payload";
        String key = "prepared-readonly-canonical-payload-001";
        Map<String, Object> firstCargo = new LinkedHashMap<>();
        firstCargo.put("merchant_sku", "EMG-001");
        firstCargo.put("quantity", 2);
        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("shipment_id", "42");
        firstPayload.put("cargo", firstCargo);

        Map<String, Object> reorderedCargo = new LinkedHashMap<>();
        reorderedCargo.put("quantity", 2);
        reorderedCargo.put("merchant_sku", "EMG-001");
        Map<String, Object> reorderedPayload = new LinkedHashMap<>();
        reorderedPayload.put("cargo", reorderedCargo);
        reorderedPayload.put("shipment_id", "42");

        AtomicInteger externalCalls = new AtomicInteger();
        AtomicInteger completionCalls = new AtomicInteger();
        IdempotentResult<Map<String, Object>> first = idempotency.executeWithPreparedReadOnlyExternalWork(
                scope,
                key,
                200,
                () -> "stable-snapshot",
                ignored -> firstPayload,
                ignored -> {
                    externalCalls.incrementAndGet();
                    return "remote-result";
                },
                (prepared, external) -> {
                    completionCalls.incrementAndGet();
                    return Map.of("prepared", prepared, "external", external);
                });

        IdempotentResult<Map<String, Object>> replay = idempotency.executeWithPreparedReadOnlyExternalWork(
                scope,
                key,
                200,
                () -> "stable-snapshot",
                ignored -> reorderedPayload,
                ignored -> {
                    externalCalls.incrementAndGet();
                    return "must-not-run";
                },
                (prepared, external) -> {
                    completionCalls.incrementAndGet();
                    return Map.of("prepared", prepared, "external", external);
                });

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.replayedBody().get("external").asText()).isEqualTo("remote-result");
        assertThat(externalCalls).hasValue(1);
        assertThat(completionCalls).hasValue(1);
    }
}
