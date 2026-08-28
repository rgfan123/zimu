package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 基于共享注册表 {@code app.idempotency_registry} 的飞象发货持久幂等 store。
 *
 * <p>不新增表、不新增迁移；所有写操作在 REQUIRES_NEW 独立事务里提交，保证
 * {@link #markEffectStarted} 先于外部写落库。跨重启行为完全由注册表行决定：新实例
 * 重放 SUCCEEDED / RECONCILIATION_REQUIRED，绝不对未知结果给出 PROCEED。
 *
 * <p>状态机（同键同 payload）：
 * <ul>
 *   <li>INSERT 成功（首次）到 PROCEED；</li>
 *   <li>SUCCEEDED / RECONCILIATION_REQUIRED 到 REPLAY（后者永不回 PROCEED）；</li>
 *   <li>FAILED（release 产生，未产生外部效果）到 PROCEED，attempt_count + 1；</li>
 *   <li>IN_PROGRESS 且租约有效 到 IN_PROGRESS；</li>
 *   <li>IN_PROGRESS 且租约过期、effect_started_at 为空 到 安全接管 PROCEED；</li>
 *   <li>IN_PROGRESS 且租约过期、effect_started_at 非空 到 单调转 RECONCILIATION_REQUIRED；</li>
 *   <li>同键不同 payload 到 CONFLICT（任何状态）。</li>
 * </ul>
 *
 * <p>store 不持久化 Cookie / Token / 收货人任何字段：快照仅承载 {@link SourceSyncResult}
 * 的契约字段，错误快照仅承载 business_code 与 message。
 */
@Component
public class FeixiangShipmentAttemptStoreJdbc implements FeixiangShipmentAttemptStore {

    private static final String RECONCILIATION_MESSAGE =
            "飞象发货效果可能已开始但结果未知（执行租约失效），禁止盲目重提，请到平台核对";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final Duration lease;

    public FeixiangShipmentAttemptStoreJdbc(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${app.idempotency.lease-seconds:60}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lease = Duration.ofSeconds(leaseSeconds);
    }

    @Override
    public ClaimResult claim(ShipmentAttemptPayload payload) {
        String key = FeixiangShipmentAttemptStore.idempotencyKey(payload.subOrderRef(), payload.trackingNo());
        String payloadHash = FeixiangShipmentAttemptStore.payloadHash(objectMapper, payload);
        String ownerToken = UUID.randomUUID().toString();
        try {
            requiresNew.executeWithoutResult(status -> jdbc.update(
                    """
                    INSERT INTO app.idempotency_registry
                        (scope, idempotency_key, payload_hash, status, owner_token, lease_expires_at, attempt_count)
                    VALUES (?, ?, ?, 'IN_PROGRESS', ?,
                            CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), 1)
                    """,
                    SCOPE, key, payloadHash, ownerToken, lease.toSeconds()));
            return ClaimResult.proceed(ownerToken);
        } catch (DuplicateKeyException ex) {
            return requiresNew.execute(status -> decideExisting(key, payloadHash, ownerToken));
        }
    }

    @Override
    public void markEffectStarted(String subOrderRef, String trackingNo, String ownerToken) {
        int updated = requiresNew.execute(status -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET effect_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS' AND effect_started_at IS NULL
                """,
                SCOPE, key(subOrderRef, trackingNo), ownerToken));
        if (updated != 1) {
            throw claimLost("外部效果未标记，禁止执行外部写");
        }
    }

    @Override
    public void verifyWritePermit(String subOrderRef, String trackingNo, String ownerToken) {
        int updated = requiresNew.execute(status -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                lease.toSeconds(), SCOPE, key(subOrderRef, trackingNo), ownerToken));
        if (updated != 1) {
            throw claimLost("外部写许可校验失败，禁止执行外部写");
        }
    }

    @Override
    public void completeSuccess(
            String subOrderRef, String trackingNo, String ownerToken, SourceSyncResult result) {
        complete(subOrderRef, trackingNo, ownerToken, "SUCCEEDED", result);
    }

    @Override
    public void completeUnknown(
            String subOrderRef, String trackingNo, String ownerToken, SourceSyncResult result) {
        complete(subOrderRef, trackingNo, ownerToken, "RECONCILIATION_REQUIRED", result);
    }

    @Override
    public void release(
            String subOrderRef, String trackingNo, String ownerToken, String businessCode, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("business_code", businessCode == null ? "" : businessCode);
        error.put("message", message == null ? "" : message);
        int updated = requiresNew.execute(status -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET status = 'FAILED', error_snapshot = ?::jsonb, completed_at = CURRENT_TIMESTAMP,
                    lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS'
                """,
                writeJson(error), SCOPE, key(subOrderRef, trackingNo), ownerToken));
        if (updated != 1) {
            throw claimLost("租约未释放，行状态未登记为可重试");
        }
    }

    @Override
    public boolean releaseReconciledNotAccepted(String intentKey) {
        if (intentKey == null || !intentKey.startsWith(KEY_PREFIX) || intentKey.length() <= KEY_PREFIX.length()) {
            return false;
        }
        Boolean released = requiresNew.execute(status -> {
            int updated = jdbc.update(
                    """
                    UPDATE app.idempotency_registry
                    SET status = 'FAILED', effect_started_at = NULL, owner_token = NULL,
                        lease_expires_at = NULL,
                        completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE scope = ? AND idempotency_key = ?
                      AND (
                          status = 'RECONCILIATION_REQUIRED'
                          OR (status = 'IN_PROGRESS'
                              AND (lease_expires_at IS NULL
                                   OR lease_expires_at <= statement_timestamp()))
                      )
                    """,
                    SCOPE, intentKey);
            if (updated == 1) {
                return true;
            }
            Integer alreadyReleased = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM app.idempotency_registry
                    WHERE scope = ? AND idempotency_key = ? AND status = 'FAILED'
                    """,
                    Integer.class, SCOPE, intentKey);
            return alreadyReleased != null && alreadyReleased == 1;
        });
        return Boolean.TRUE.equals(released);
    }

    @Override
    public long externalEffectCount() {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM app.idempotency_registry
                WHERE scope = ? AND effect_started_at IS NOT NULL
                  AND status <> 'IN_PROGRESS'
                """,
                Long.class, SCOPE);
        return count == null ? 0L : count;
    }

    private ClaimResult decideExisting(String key, String payloadHash, String ownerToken) {
        Map<String, Object> row = jdbc.queryForMap(
                """
                SELECT status, payload_hash, response_snapshot
                FROM app.idempotency_registry
                WHERE scope = ? AND idempotency_key = ?
                """,
                SCOPE, key);
        String rowStatus = (String) row.get("status");
        String rowHash = (String) row.get("payload_hash");
        if (!Objects.equals(rowHash, payloadHash)) {
            // 同一子单同一运单号但 payload 变了（最典型的是换了承运商）：绝不给新键放行。
            return ClaimResult.conflict();
        }
        return switch (rowStatus == null ? "" : rowStatus) {
            case "SUCCEEDED" -> {
                SourceSyncResult replay = readSnapshot(row.get("response_snapshot"));
                yield replay == null ? ClaimResult.conflict() : ClaimResult.replay(replay);
            }
            case "RECONCILIATION_REQUIRED" -> {
                SourceSyncResult replay = readSnapshot(row.get("response_snapshot"));
                yield ClaimResult.replay(replay == null
                        ? SourceSyncResult.failed("RECONCILIATION_REQUIRED", RECONCILIATION_MESSAGE)
                        : replay);
            }
            case "FAILED" -> {
                int updated = jdbc.update(
                        """
                        UPDATE app.idempotency_registry
                        SET status = 'IN_PROGRESS', completed_at = NULL, error_snapshot = NULL,
                            effect_started_at = NULL,
                            owner_token = ?,
                            lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                            attempt_count = attempt_count + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE scope = ? AND idempotency_key = ? AND status = 'FAILED'
                        """,
                        ownerToken, lease.toSeconds(), SCOPE, key);
                yield updated == 1 ? ClaimResult.proceed(ownerToken) : ClaimResult.inProgress();
            }
            case "IN_PROGRESS" -> decideInProgress(key, ownerToken);
            default -> ClaimResult.conflict();
        };
    }

    private ClaimResult decideInProgress(String key, String ownerToken) {
        int taken = jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET owner_token = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ?
                  AND status = 'IN_PROGRESS' AND lease_expires_at < CURRENT_TIMESTAMP
                  AND effect_started_at IS NULL
                """,
                ownerToken, lease.toSeconds(), SCOPE, key);
        if (taken == 1) {
            return ClaimResult.proceed(ownerToken);
        }
        int reconciled = jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET status = 'RECONCILIATION_REQUIRED',
                    response_snapshot = ?::jsonb, completed_at = CURRENT_TIMESTAMP,
                    lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ?
                  AND status = 'IN_PROGRESS' AND lease_expires_at < CURRENT_TIMESTAMP
                  AND effect_started_at IS NOT NULL
                """,
                writeJson(SourceSyncResult.failed("RECONCILIATION_REQUIRED", RECONCILIATION_MESSAGE)),
                SCOPE, key);
        if (reconciled == 1) {
            return ClaimResult.reconciliationRequired(
                    SourceSyncResult.failed("RECONCILIATION_REQUIRED", RECONCILIATION_MESSAGE));
        }
        return decisionAfterConcurrentChange(key);
    }

    private ClaimResult decisionAfterConcurrentChange(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, response_snapshot FROM app.idempotency_registry "
                        + "WHERE scope = ? AND idempotency_key = ?",
                SCOPE, key);
        if (rows.isEmpty()) {
            return ClaimResult.conflict();
        }
        Map<String, Object> row = rows.getFirst();
        String status = (String) row.get("status");
        if ("RECONCILIATION_REQUIRED".equals(status)) {
            SourceSyncResult replay = readSnapshot(row.get("response_snapshot"));
            return ClaimResult.replay(replay == null
                    ? SourceSyncResult.failed("RECONCILIATION_REQUIRED", RECONCILIATION_MESSAGE)
                    : replay);
        }
        if ("SUCCEEDED".equals(status)) {
            SourceSyncResult replay = readSnapshot(row.get("response_snapshot"));
            return replay == null ? ClaimResult.conflict() : ClaimResult.replay(replay);
        }
        return ClaimResult.inProgress();
    }

    private void complete(
            String subOrderRef, String trackingNo, String ownerToken, String status, SourceSyncResult result) {
        Objects.requireNonNull(result, "result");
        int updated = requiresNew.execute(tx -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET status = ?, response_snapshot = ?::jsonb, completed_at = CURRENT_TIMESTAMP,
                    lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS'
                """,
                status, writeJson(result), SCOPE, key(subOrderRef, trackingNo), ownerToken));
        if (updated != 1) {
            throw claimLost("结果未登记，执行租约已失效，请重试");
        }
    }

    private static String key(String subOrderRef, String trackingNo) {
        return FeixiangShipmentAttemptStore.idempotencyKey(subOrderRef, trackingNo);
    }

    private BusinessException claimLost(String detail) {
        return BusinessException.conflict(
                "FEIXIANG_IDEMPOTENCY_CLAIM_LOST", "飞象幂等执行租约已失效：" + detail);
    }

    private SourceSyncResult readSnapshot(Object snapshotValue) {
        if (snapshotValue == null) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotValue.toString(), SourceSyncResult.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }
}
