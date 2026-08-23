package cn.zimu.fulfillment.common.idempotency;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL 权威幂等注册表。
 *
 * <p>流程：REQUIRES_NEW 抢占 IN_PROGRESS 行 → 业务工作与 SUCCEEDED 响应快照在调用方事务内原子提交；
 * 业务失败时 REQUIRES_NEW 标记 FAILED。同 key 同 payload 重放
 * 首次结果；同 key 不同 payload 返回 409；FAILED 允许重跑；普通本地/只读工作的过期租约允许接管，
 * 启用按 scope 严格策略的外部写则在租约过期时进入 RECONCILIATION_REQUIRED，禁止第二次产生外部效果。
 * 本服务不产生外部副作用，注册表与业务事实在同一事务内提交，无崩溃恢复窗口。
 */
@Service
public class IdempotencyService {

    @FunctionalInterface
    public interface Work<T> {
        T execute();
    }

    /** 只能承载可安全重试的只读外部调用；不得用于创建、修改或删除外部事实。 */
    @FunctionalInterface
    public interface ReadOnlyExternalWork<T> {
        T execute();
    }

    /** 消费只读外部结果并在本地事务内持久化业务事实。 */
    @FunctionalInterface
    public interface TransactionalCompletion<E, T> {
        T execute(E externalResult);
    }

    /** 在一个新的本地事务中读取并锁定稳定快照；不得执行外部调用。 */
    @FunctionalInterface
    public interface TransactionalPreparation<P> {
        P prepare();
    }

    /** 在独立事务中先持久化可恢复的外部写意图。 */
    @FunctionalInterface
    public interface ExternalWriteIntent<I> {
        I persist();
    }

    /** 消费已提交的写意图；调用时不允许存在数据库事务。 */
    @FunctionalInterface
    public interface ExternalWrite<I, E> {
        E execute(I intent);
    }

    /** 外部写执行期间的租约围栏；每次不可逆外调前必须验证。 */
    @FunctionalInterface
    public interface ExternalWriteClaim {
        void verifyActive();
    }

    /** 消费已提交的写意图，并在每次外部写前使用 claim 围栏。 */
    @FunctionalInterface
    public interface GuardedExternalWrite<I, E> {
        E execute(I intent, ExternalWriteClaim claim);
    }

    /** 在一个本地事务中归档外部写结果与业务事实。 */
    @FunctionalInterface
    public interface ExternalWriteCompletion<I, E, T> {
        ExternalCompletion<T> execute(I intent, E externalResult);
    }

    /** 外部结果归档后的结论；失败事实也会先提交，再把业务异常返回给调用者。 */
    public record ExternalCompletion<T>(T result, RuntimeException failure) {
        public static <T> ExternalCompletion<T> succeeded(T result) {
            return new ExternalCompletion<>(result, null);
        }

        public static <T> ExternalCompletion<T> failed(RuntimeException failure) {
            return new ExternalCompletion<>(null, Objects.requireNonNull(failure));
        }

        public boolean succeeded() {
            return failure == null;
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate required;

    public IdempotencyService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${app.idempotency.lease-seconds:60}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.defaultLease = Duration.ofSeconds(leaseSeconds);
    }

    private final Duration defaultLease;

    @Transactional
    public <T> IdempotentResult<T> execute(
            String scope, String idempotencyKey, Object requestPayload, int successStatus, Work<T> work) {
        String payloadHash = sha256Hex(serialize(requestPayload));
        String ownerToken = UUID.randomUUID().toString();
        ClaimResult claim = claim(
                scope, idempotencyKey, payloadHash, ownerToken,
                defaultLease, ExpiredClaimPolicy.TAKE_OVER);
        if (claim.replay()) {
            ObjectNode snapshot = (ObjectNode) claim.snapshot();
            return IdempotentResult.replayed(snapshot.get("http_status").asInt(), snapshot.get("body"));
        }
        if (claim.conflict()) {
            if (claim.reconciliationRequired()) {
                throw BusinessException.conflict(
                        "RECONCILIATION_REQUIRED", "该幂等键处于对账状态，禁止盲目重试，请联系运维");
            }
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键已被不同请求使用");
        }
        try {
            T result = work.execute();
            markSucceeded(scope, idempotencyKey, successStatus, result, ownerToken);
            return IdempotentResult.executed(result, successStatus);
        } catch (RuntimeException ex) {
            markFailed(scope, idempotencyKey, ex, ownerToken);
            throw ex;
        }
    }

    /**
     * 先抢占幂等键，再在数据库事务外执行只读外部调用，最后把本地业务事实与 SUCCEEDED
     * 响应快照原子提交。same-key replay 在外调前直接返回；不同 payload 或仍在执行的 claim
     * 直接冲突。
     *
     * <p>该 seam 只允许调用只读外部接口。若外调成功而本地 completion 失败，claim 会标记为
     * FAILED，重试会再次访问外部系统；因此禁止用于任何会创建、修改或删除外部事实的调用。
     */
    public <E, T> IdempotentResult<T> executeWithReadOnlyExternalWork(
            String scope,
            String idempotencyKey,
            Object requestPayload,
            int successStatus,
            ReadOnlyExternalWork<E> externalWork,
            TransactionalCompletion<E, T> completion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "read-only external idempotency work must start outside a database transaction");
        }
        String payloadHash = sha256Hex(serialize(requestPayload));
        String ownerToken = UUID.randomUUID().toString();
        ClaimResult claim = claim(
                scope, idempotencyKey, payloadHash, ownerToken,
                defaultLease, ExpiredClaimPolicy.TAKE_OVER);
        if (claim.replay()) {
            ObjectNode snapshot = (ObjectNode) claim.snapshot();
            return IdempotentResult.replayed(snapshot.get("http_status").asInt(), snapshot.get("body"));
        }
        if (claim.conflict()) {
            if (claim.reconciliationRequired()) {
                throw BusinessException.conflict(
                        "RECONCILIATION_REQUIRED", "该幂等键处于对账状态，禁止盲目重试，请联系运维");
            }
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键已被不同请求使用或正在执行");
        }
        try {
            E externalResult = externalWork.execute();
            return required.execute(status -> {
                T result = completion.execute(externalResult);
                markSucceeded(scope, idempotencyKey, successStatus, result, ownerToken);
                return IdempotentResult.executed(result, successStatus);
            });
        } catch (RuntimeException ex) {
            markFailed(scope, idempotencyKey, ex, ownerToken);
            throw ex;
        }
    }

    /**
     * 与 {@link #executeWithReadOnlyExternalWork} 相同，但在抢占幂等键后先用独立事务构造
     * 一致的本地快照。用于需要多表行锁才能可靠读取决策输入的只读外部调用。
     */
    public <P, E, T> IdempotentResult<T> executeWithPreparedReadOnlyExternalWork(
            String scope,
            String idempotencyKey,
            int successStatus,
            TransactionalPreparation<P> preparation,
            Function<P, Object> requestPayload,
            ReadOnlyExternalWorkWithPreparation<P, E> externalWork,
            TransactionalCompletionWithPreparation<P, E, T> completion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "prepared read-only external idempotency work must start outside a database transaction");
        }
        P prepared = requiresNew.execute(status -> preparation.prepare());
        if (prepared == null) {
            throw new IllegalStateException("prepared read-only external work snapshot must not be null");
        }
        String payloadHash = sha256Hex(serialize(requestPayload.apply(prepared)));
        String ownerToken = UUID.randomUUID().toString();
        ClaimResult claim = claim(
                scope, idempotencyKey, payloadHash, ownerToken,
                defaultLease, ExpiredClaimPolicy.TAKE_OVER);
        if (claim.replay()) {
            ObjectNode snapshot = (ObjectNode) claim.snapshot();
            return IdempotentResult.replayed(snapshot.get("http_status").asInt(), snapshot.get("body"));
        }
        if (claim.conflict()) {
            if (claim.reconciliationRequired()) {
                throw BusinessException.conflict(
                        "RECONCILIATION_REQUIRED", "该幂等键处于对账状态，禁止盲目重试，请联系运维");
            }
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键已被不同请求使用或正在执行");
        }
        try {
            E externalResult = externalWork.execute(prepared);
            return required.execute(status -> {
                T result = completion.execute(prepared, externalResult);
                markSucceeded(scope, idempotencyKey, successStatus, result, ownerToken);
                return IdempotentResult.executed(result, successStatus);
            });
        } catch (RuntimeException ex) {
            markFailed(scope, idempotencyKey, ex, ownerToken);
            throw ex;
        }
    }

    @FunctionalInterface
    public interface ReadOnlyExternalWorkWithPreparation<P, E> {
        E execute(P prepared);
    }

    @FunctionalInterface
    public interface TransactionalCompletionWithPreparation<P, E, T> {
        T execute(P prepared, E externalResult);
    }

    /**
     * 外部写专用三阶段幂等 seam：先提交本地 intent，再在事务外执行外部写，最后原子提交
     * 本地结果与 SUCCEEDED 快照。业务拒绝通过 {@link ExternalCompletion#failed(RuntimeException)}
     * 返回，使失败事实先提交、幂等 claim 再标记 FAILED。same-key replay 在 intent 前返回。
     *
     * <p>这不能消除「外部成功、本地结果未归档」的分布式故障窗口；调用方必须让 intent 携带稳定
     * 外部幂等引用，并在重试时先按该引用对账，禁止生成新的外部引用盲写。
     */
    public <I, E, T> IdempotentResult<T> executeWithExternalWriteIntent(
            String scope,
            String idempotencyKey,
            Object requestPayload,
            int successStatus,
            ExternalWriteIntent<I> intentWork,
            ExternalWrite<I, E> externalWork,
            ExternalWriteCompletion<I, E, T> completion) {
        return executeExternalWrite(
                scope, idempotencyKey, requestPayload, successStatus, defaultLease,
                ExpiredClaimPolicy.TAKE_OVER,
                intentWork, (intent, claim) -> externalWork.execute(intent), completion);
    }

    /**
     * 外部写三阶段 seam 的按 scope 租约版本。租约过期代表外部效果已未知：注册表会持久化为
     * RECONCILIATION_REQUIRED，且不会把执行权交给第二个调用者。
     */
    public <I, E, T> IdempotentResult<T> executeWithExternalWriteIntent(
            String scope,
            String idempotencyKey,
            Object requestPayload,
            int successStatus,
            Duration scopeLease,
            ExternalWriteIntent<I> intentWork,
            GuardedExternalWrite<I, E> externalWork,
            ExternalWriteCompletion<I, E, T> completion) {
        return executeExternalWrite(
                scope, idempotencyKey, requestPayload, successStatus, scopeLease,
                ExpiredClaimPolicy.REQUIRE_RECONCILIATION,
                intentWork, externalWork, completion);
    }

    private <I, E, T> IdempotentResult<T> executeExternalWrite(
            String scope,
            String idempotencyKey,
            Object requestPayload,
            int successStatus,
            Duration scopeLease,
            ExpiredClaimPolicy expiredClaimPolicy,
            ExternalWriteIntent<I> intentWork,
            GuardedExternalWrite<I, E> externalWork,
            ExternalWriteCompletion<I, E, T> completion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("external write idempotency work must start outside a database transaction");
        }
        String payloadHash = sha256Hex(serialize(requestPayload));
        String ownerToken = UUID.randomUUID().toString();
        ClaimResult claim = claim(
                scope, idempotencyKey, payloadHash, ownerToken,
                Objects.requireNonNull(scopeLease, "scopeLease"),
                expiredClaimPolicy);
        if (claim.replay()) {
            ObjectNode snapshot = (ObjectNode) claim.snapshot();
            return IdempotentResult.replayed(snapshot.get("http_status").asInt(), snapshot.get("body"));
        }
        if (claim.conflict()) {
            if (claim.reconciliationRequired()) {
                throw BusinessException.conflict(
                        "RECONCILIATION_REQUIRED", "该幂等键处于对账状态，禁止盲目重试，请联系运维");
            }
            throw BusinessException.conflict("IDEMPOTENCY_CONFLICT", "相同幂等键已被不同请求使用或正在执行");
        }
        try {
            I intent = requiresNew.execute(status -> intentWork.persist());
            if (intent == null) {
                throw new IllegalStateException("external write intent must not be null");
            }
            ExternalWriteClaim activeClaim = () -> verifyActiveClaim(scope, idempotencyKey, ownerToken);
            activeClaim.verifyActive();
            E externalResult = externalWork.execute(intent, activeClaim);
            ExternalCompletion<T> outcome = required.execute(status -> {
                ExternalCompletion<T> value = completion.execute(intent, externalResult);
                if (value == null) {
                    throw new IllegalStateException("external write completion must not be null");
                }
                if (value.succeeded()) {
                    markSucceeded(scope, idempotencyKey, successStatus, value.result(), ownerToken);
                }
                return value;
            });
            if (!outcome.succeeded()) {
                throw outcome.failure();
            }
            return IdempotentResult.executed(outcome.result(), successStatus);
        } catch (RuntimeException ex) {
            // 执行期对账未决仍标记 FAILED，使 same-key 可重新进入稳定 intent 做 query-only 对账；
            // 只有 claim 租约过期才由 claim() 原子写入不可接管的 RECONCILIATION_REQUIRED 终态。
            markFailed(scope, idempotencyKey, ex, ownerToken);
            throw ex;
        }
    }

    private void verifyActiveClaim(String scope, String key, String ownerToken) {
        Boolean active = requiresNew.execute(status -> jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.idempotency_registry
                    WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                      AND status = 'IN_PROGRESS' AND lease_expires_at > statement_timestamp()
                )
                """,
                Boolean.class, scope, key, ownerToken));
        if (!Boolean.TRUE.equals(active)) {
            throw BusinessException.conflict(
                    "IDEMPOTENCY_CLAIM_LOST", "幂等执行租约已失效，禁止继续产生外部写入");
        }
    }

    private ClaimResult claim(
            String scope,
            String key,
            String payloadHash,
            String ownerToken,
            Duration claimLease,
            ExpiredClaimPolicy expiredClaimPolicy) {
        // 首次占用：REQUIRES_NEW 事务内 INSERT，成功即抢到租约
        try {
            requiresNew.executeWithoutResult(status -> jdbc.update(
                    """
                    INSERT INTO app.idempotency_registry
                        (scope, idempotency_key, payload_hash, status, owner_token, lease_expires_at, attempt_count)
                    VALUES (?, ?, ?, 'IN_PROGRESS', ?,
                            CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), 1)
                    """,
                    scope, key, payloadHash, ownerToken, claimLease.toSeconds()));
            return ClaimResult.proceed();
        } catch (DuplicateKeyException ex) {
            // 内层事务已被 TransactionTemplate 回滚（PG 中失败语句会中止当前事务，不能在同事务内继续读取）；
            // 在全新 REQUIRES_NEW 事务中读取既有行并决定重放/冲突/接管
            return requiresNew.execute(status -> {
                Map<String, Object> row = jdbc.queryForMap(
                        """
                        SELECT status, payload_hash, response_snapshot
                        FROM app.idempotency_registry
                        WHERE scope = ? AND idempotency_key = ?
                        """,
                        scope, key);
                String rowStatus = (String) row.get("status");
                String rowHash = (String) row.get("payload_hash");
                boolean samePayload = Objects.equals(rowHash, payloadHash);
                if (!samePayload) {
                    return ClaimResult.conflict(false);
                }
                switch (rowStatus == null ? "" : rowStatus) {
                    case "SUCCEEDED" -> {
                        Object snapshotValue = row.get("response_snapshot");
                        String snapshotJson = snapshotValue == null ? null : snapshotValue.toString();
                        return ClaimResult.replay(readSnapshot(snapshotJson));
                    }
                    case "FAILED" -> {
                        int updated = jdbc.update(
                                """
                                UPDATE app.idempotency_registry
                                SET status = 'IN_PROGRESS', completed_at = NULL, error_snapshot = NULL,
                                    owner_token = ?,
                                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                                    attempt_count = attempt_count + 1,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE scope = ? AND idempotency_key = ?
                                  AND status = 'FAILED'
                                """,
                                ownerToken, claimLease.toSeconds(), scope, key);
                        if (updated != 1) {
                            return ClaimResult.conflict(false);
                        }
                        return ClaimResult.proceed();
                    }
                    case "IN_PROGRESS" -> {
                        if (expiredClaimPolicy == ExpiredClaimPolicy.REQUIRE_RECONCILIATION) {
                            int updated = jdbc.update(
                                    """
                                    UPDATE app.idempotency_registry
                                    SET status = 'RECONCILIATION_REQUIRED',
                                        completed_at = CURRENT_TIMESTAMP,
                                        lease_expires_at = NULL,
                                        error_snapshot = jsonb_build_object(
                                            'business_code', 'RECONCILIATION_REQUIRED',
                                            'message', 'external write lease expired before durable completion'),
                                        updated_at = CURRENT_TIMESTAMP
                                    WHERE scope = ? AND idempotency_key = ?
                                      AND status = 'IN_PROGRESS' AND lease_expires_at < CURRENT_TIMESTAMP
                                    """,
                                    scope, key);
                            if (updated == 1) {
                                return ClaimResult.conflict(true);
                            }
                            String currentStatus = jdbc.queryForObject(
                                    "SELECT status FROM app.idempotency_registry "
                                            + "WHERE scope = ? AND idempotency_key = ?",
                                    String.class, scope, key);
                            return ClaimResult.conflict("RECONCILIATION_REQUIRED".equals(currentStatus));
                        }
                        int updated = jdbc.update(
                                """
                                UPDATE app.idempotency_registry
                                SET owner_token = ?,
                                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                                    attempt_count = attempt_count + 1,
                                    updated_at = CURRENT_TIMESTAMP
                                WHERE scope = ? AND idempotency_key = ?
                                  AND status = 'IN_PROGRESS' AND lease_expires_at < CURRENT_TIMESTAMP
                                """,
                                ownerToken, claimLease.toSeconds(), scope, key);
                        if (updated != 1) {
                            return ClaimResult.conflict(false);
                        }
                        return ClaimResult.proceed();
                    }
                    default -> {
                        return ClaimResult.conflict(true);
                    }
                }
            });
        }
    }

    private <T> void markSucceeded(String scope, String key, int httpStatus, T result, String ownerToken) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("http_status", httpStatus);
        snapshot.set("body", objectMapper.valueToTree(result));
        int updated = jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET status = 'SUCCEEDED', response_snapshot = ?::jsonb, completed_at = CURRENT_TIMESTAMP,
                    lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS' AND lease_expires_at > statement_timestamp()
                """,
                writeJson(snapshot), scope, key, ownerToken);
        if (updated != 1) {
            // Fencing token：租约已被接管时必须让当前业务事务回滚，不能提交无权登记的事实。
            throw BusinessException.conflict(
                    "IDEMPOTENCY_CLAIM_LOST", "幂等执行租约已失效，当前业务事务已回滚，请重试");
        }
    }

    private void markFailed(String scope, String key, RuntimeException ex, String ownerToken) {
        try {
            requiresNew.executeWithoutResult(status -> {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("exception", ex.getClass().getSimpleName());
                error.put("message", ex.getMessage() == null ? "" : ex.getMessage());
                jdbc.update(
                        """
                        UPDATE app.idempotency_registry
                        SET status = 'FAILED', error_snapshot = ?::jsonb, completed_at = CURRENT_TIMESTAMP,
                            lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                          AND status = 'IN_PROGRESS' AND lease_expires_at > statement_timestamp()
                        """,
                        writeJson(error), scope, key, ownerToken);
            });
        } catch (RuntimeException ignored) {
            // 失败标记写入失败不掩盖原始业务异常
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private byte[] serialize(Object requestPayload) {
        try {
            return objectMapper.writeValueAsBytes(canonicalPayload(objectMapper.valueToTree(requestPayload)));
        } catch (Exception ex) {
            throw new IllegalStateException("幂等 payload 序列化失败", ex);
        }
    }

    private JsonNode canonicalPayload(JsonNode value) {
        if (value.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            value.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(String::compareTo);
            ObjectNode sorted = objectMapper.createObjectNode();
            for (String fieldName : fieldNames) {
                sorted.set(fieldName, canonicalPayload(value.get(fieldName)));
            }
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            value.forEach(element -> ordered.add(canonicalPayload(element)));
            return ordered;
        }
        return value;
    }

    private JsonNode readSnapshot(String snapshot) {
        try {
            return objectMapper.readTree(snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等响应快照解析失败", ex);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class ClaimResult {

        private final boolean replay;
        private final boolean conflict;
        private final boolean reconciliationRequired;
        private final JsonNode snapshot;

        private ClaimResult(boolean replay, boolean conflict, boolean reconciliationRequired, JsonNode snapshot) {
            this.replay = replay;
            this.conflict = conflict;
            this.reconciliationRequired = reconciliationRequired;
            this.snapshot = snapshot;
        }

        static ClaimResult proceed() {
            return new ClaimResult(false, false, false, null);
        }

        static ClaimResult replay(JsonNode snapshot) {
            return new ClaimResult(true, false, false, snapshot);
        }

        static ClaimResult conflict(boolean reconciliationRequired) {
            return new ClaimResult(false, true, reconciliationRequired, null);
        }

        boolean replay() {
            return replay;
        }

        boolean conflict() {
            return conflict;
        }

        boolean reconciliationRequired() {
            return reconciliationRequired;
        }

        JsonNode snapshot() {
            return snapshot;
        }
    }

    private enum ExpiredClaimPolicy {
        TAKE_OVER,
        REQUIRE_RECONCILIATION
    }

}
