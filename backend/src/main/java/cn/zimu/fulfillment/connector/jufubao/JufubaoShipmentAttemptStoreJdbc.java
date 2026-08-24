package cn.zimu.fulfillment.connector.jufubao;

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
 * 基于共享注册表 {@code app.idempotency_registry} 的聚福宝发货持久幂等 store（Issue #99）。
 *
 * <p>与既有 {@code IdempotencyService} 共用同一张表（scope = {@value #SCOPE}），不新增
 * migration 或表。所有写操作都在 REQUIRES_NEW 独立事务中提交，保证
 * {@link #markEffectStarted} 先于外部写落库、成功/未知结果带 fencing owner_token 持久化。
 * 跨重启行为完全由注册表行决定：新 store 实例重放 SUCCEEDED / RECONCILIATION_REQUIRED，
 * 绝不对未知结果给出 PROCEED。
 *
 * <p>此纵切不直接调用 {@code IdempotencyService.executeWithExternalWriteIntent}：该通用 seam
 * 的失败语义允许重跑，而聚福宝没有平台幂等键，已开始外部写后的未知结果必须单调保持
 * {@code RECONCILIATION_REQUIRED}。这里复用同一注册表和 fencing 约定，但保留更严格的外部写状态机。
 *
 * <p>状态机（同 key 同 payload 时）：
 * <ul>
 *   <li>INSERT 成功（首次）→ PROCEED；attempt_count = 1。</li>
 *   <li>SUCCEEDED → REPLAY（重放响应快照）。</li>
 *   <li>RECONCILIATION_REQUIRED → REPLAY（重放未知结果，永不回 PROCEED）。</li>
 *   <li>FAILED（release 释放：平台明确拒绝或写前失败，未产生外部效果）→ 接管为 PROCEED，
 *       attempt_count + 1，并清空 effect_started_at。</li>
 *   <li>IN_PROGRESS 且租约有效 → IN_PROGRESS（他人正在执行）。</li>
 *   <li>IN_PROGRESS 且租约过期、effect_started_at 为空 → 安全接管为 PROCEED。</li>
 *   <li>IN_PROGRESS 且租约过期、effect_started_at 非空 → 单调转 RECONCILIATION_REQUIRED
 *       （写入对账快照），禁止盲提。</li>
 *   <li>同 key 不同 payload → CONFLICT（任何状态）。</li>
 * </ul>
 *
 * <p>store 不写入 Cookie / Token / PII：快照仅承载 {@link SourceSyncResult} 契约字段，
 * 错误快照仅承载 business_code 与 message。
 */
@Component
public class JufubaoShipmentAttemptStoreJdbc implements JufubaoShipmentAttemptStore {

    private static final String RECONCILIATION_MESSAGE =
            "聚福宝发货效果可能已开始但结果未知（执行租约失效），禁止盲目重提，请到平台核对";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final Duration lease;

    public JufubaoShipmentAttemptStoreJdbc(
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
        String key = JufubaoShipmentAttemptStore.idempotencyKey(payload.subOrderId(), payload.trackingNo());
        String payloadHash = JufubaoShipmentAttemptStore.payloadHash(objectMapper, payload);
        String ownerToken = UUID.randomUUID().toString();
        try {
            // 首次占用：REQUIRES_NEW 事务内 INSERT，成功即抢到租约；租约从数据库时间起算。
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
            // 内层事务已被 TransactionTemplate 回滚；在全新 REQUIRES_NEW 事务中读取既有行并决策。
            return requiresNew.execute(status -> decideExisting(key, payloadHash, ownerToken));
        }
    }

    @Override
    public void markEffectStarted(String subOrderId, String trackingNo, String ownerToken) {
        int updated = requiresNew.execute(status -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET effect_started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS' AND effect_started_at IS NULL
                """,
                SCOPE, JufubaoShipmentAttemptStore.idempotencyKey(subOrderId, trackingNo), ownerToken));
        if (updated != 1) {
            throw claimLost("外部效果未标记，禁止执行外部写");
        }
    }

    @Override
    public void verifyWritePermit(String subOrderId, String trackingNo, String ownerToken) {
        int updated = requiresNew.execute(status -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """,
                lease.toSeconds(),
                SCOPE,
                JufubaoShipmentAttemptStore.idempotencyKey(subOrderId, trackingNo),
                ownerToken));
        if (updated != 1) {
            throw claimLost("外部写许可校验失败，禁止执行外部写");
        }
    }

    @Override
    public void completeSuccess(String subOrderId, String trackingNo, String ownerToken, SourceSyncResult result) {
        complete(subOrderId, trackingNo, ownerToken, "SUCCEEDED", result);
    }

    @Override
    public void completeUnknown(String subOrderId, String trackingNo, String ownerToken, SourceSyncResult result) {
        complete(subOrderId, trackingNo, ownerToken, "RECONCILIATION_REQUIRED", result);
    }

    @Override
    public void release(String subOrderId, String trackingNo, String ownerToken, String businessCode, String message) {
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
                writeJson(error), SCOPE, JufubaoShipmentAttemptStore.idempotencyKey(subOrderId, trackingNo), ownerToken));
        if (updated != 1) {
            throw claimLost("租约未释放，行状态未登记为可重试");
        }
    }

    @Override
    public boolean releaseReconciledNotAccepted(String intentKey) {
        if (intentKey == null || !intentKey.startsWith("JUFUBAO:") || intentKey.length() <= "JUFUBAO:".length()) {
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
                    SCOPE,
                    intentKey);
            if (updated == 1) {
                return true;
            }
            Integer alreadyReleased = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM app.idempotency_registry
                    WHERE scope = ? AND idempotency_key = ?
                      AND status = 'FAILED'
                    """,
                    Integer.class,
                    SCOPE,
                    intentKey);
            return alreadyReleased != null && alreadyReleased == 1;
        });
        return Boolean.TRUE.equals(released);
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
            return ClaimResult.conflict();
        }
        return switch (rowStatus == null ? "" : rowStatus) {
            case "SUCCEEDED" -> {
                SourceSyncResult replay = readSnapshot(row.get("response_snapshot"));
                // 快照缺失或不可解析的 SUCCEEDED 无法安全重放：失败关闭，不重提。
                yield replay == null ? ClaimResult.conflict() : ClaimResult.replay(replay);
            }
            case "RECONCILIATION_REQUIRED" -> {
                SourceSyncResult replay = readSnapshot(row.get("response_snapshot"));
                if (replay == null) {
                    // 未知结果必须保持 RECONCILIATION_REQUIRED：即使快照缺失也绝不给 PROCEED。
                    replay = SourceSyncResult.failed("RECONCILIATION_REQUIRED", RECONCILIATION_MESSAGE);
                }
                yield ClaimResult.replay(replay);
            }
            case "FAILED" -> {
                // FAILED 只由 release 产生（平台明确拒绝或写前失败），未产生外部效果，可安全重跑。
                int updated = jdbc.update(
                        """
                        UPDATE app.idempotency_registry
                        SET status = 'IN_PROGRESS', completed_at = NULL, error_snapshot = NULL,
                            effect_started_at = NULL,
                            owner_token = ?,
                            lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                            attempt_count = attempt_count + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE scope = ? AND idempotency_key = ?
                          AND status = 'FAILED'
                        """,
                        ownerToken, lease.toSeconds(), SCOPE, key);
                yield updated == 1 ? ClaimResult.proceed(ownerToken) : ClaimResult.inProgress();
            }
            case "IN_PROGRESS" -> decideInProgress(key, ownerToken);
            default -> ClaimResult.conflict();
        };
    }

    private ClaimResult decideInProgress(String key, String ownerToken) {
        // 租约过期且外部效果未开始：可安全接管。
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
        // 租约过期且外部效果已开始：无法安全重试，单调转 RECONCILIATION_REQUIRED 并写入对账快照。
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
        // CAS 可能输给另一个 claimant；重读终态，避免把已完成的对账状态误报为 IN_PROGRESS。
        return decisionAfterConcurrentChange(key);
    }

    private ClaimResult decisionAfterConcurrentChange(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, response_snapshot FROM app.idempotency_registry "
                        + "WHERE scope = ? AND idempotency_key = ?",
                SCOPE,
                key);
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
        // 租约仍有效，或另一个 owner 正在完成/释放；保守退避，绝不产生新的 PROCEED。
        return ClaimResult.inProgress();
    }

    private void complete(String subOrderId, String trackingNo, String ownerToken, String status, SourceSyncResult result) {
        Objects.requireNonNull(result, "result");
        int updated = requiresNew.execute(tx -> jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET status = ?, response_snapshot = ?::jsonb, completed_at = CURRENT_TIMESTAMP,
                    lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE scope = ? AND idempotency_key = ? AND owner_token = ?
                  AND status = 'IN_PROGRESS'
                """,
                status, writeJson(result), SCOPE, JufubaoShipmentAttemptStore.idempotencyKey(subOrderId, trackingNo), ownerToken));
        if (updated != 1) {
            // Fencing token：租约已被接管或过期时，旧 owner 无权登记结果。
            throw claimLost("结果未登记，执行租约已失效，请重试");
        }
    }

    private BusinessException claimLost(String detail) {
        return BusinessException.conflict("JUFUBAO_IDEMPOTENCY_CLAIM_LOST", "聚福宝幂等执行租约已失效：" + detail);
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
