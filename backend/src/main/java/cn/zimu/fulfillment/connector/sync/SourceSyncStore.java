package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.connector.jufubao.JufubaoShipmentAttemptStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * shipment_syncs 权威投影与外层幂等 registry 元数据存储。平台 Adapter 自有 intent 由
 * platform_intent_key 关联，NOT_ACCEPTED 必须使用该原始键释放，禁止按当前事实重算。
 */
@Component
public class SourceSyncStore {

    public static final String EXECUTE_SCOPE = "shipment.source_sync.execute";
    public static final String RECONCILE_SCOPE = "shipment.source_sync.reconcile";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SourceSyncHash hashes;

    public SourceSyncStore(JdbcTemplate jdbc, ObjectMapper mapper, SourceSyncHash hashes) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hashes = hashes;
    }

    /** 在 IdempotencyService 的 intent REQUIRES_NEW 事务内持久化外部写意图。 */
    public SourceSyncIntent begin(
            SourceSyncCheck check,
            String idempotencyKey,
            SourceShipmentResult result) {
        requireTransaction("source-sync intent");
        SourceSyncFacts artifact = check.internal();
        jdbc.update(
                "INSERT INTO app.shipment_syncs (shipment_id, source_channel) VALUES (?, ?) ON CONFLICT DO NOTHING",
                artifact.shipmentId(), artifact.sourceChannel().name());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT sync_status, attempt_count, lock_version FROM app.shipment_syncs "
                        + "WHERE shipment_id=? AND source_channel=? FOR UPDATE",
                artifact.shipmentId(), artifact.sourceChannel().name());
        SourceSyncStatus current = SourceSyncStatus.valueOf((String) row.get("sync_status"));
        if (!(current == SourceSyncStatus.PENDING || current == SourceSyncStatus.SYNC_FAILED)) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_STATE_CONFLICT", "当前来源同步状态不允许创建新意图: " + current);
        }
        int attempt = ((Number) row.get("attempt_count")).intValue() + 1;
        long oldVersion = ((Number) row.get("lock_version")).longValue();
        long version = oldVersion + 1;
        String platformIntentKey = platformIntentKey(artifact);
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET sync_status='SYNCING', attempt_count=?, intent_key=?, platform_intent_key=?,
                    check_hash=?, artifact_hash=?, source_line_ref=?, carrier_code=?, tracking_number=?,
                    intent_started_at=CURRENT_TIMESTAMP, effect_started_at=NULL,
                    last_error_code=NULL, last_error_message=NULL, synced_at=NULL,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND lock_version=?
                  AND sync_status IN ('PENDING','SYNC_FAILED')
                """,
                attempt,
                idempotencyKey,
                platformIntentKey,
                check.checkHash(),
                check.artifactHash(),
                artifact.sourceLineRef(),
                artifact.carrierCode(),
                artifact.trackingNumber(),
                artifact.shipmentId(),
                artifact.sourceChannel().name(),
                oldVersion);
        if (updated != 1) {
            throw BusinessException.conflict("SOURCE_SYNC_VERSION_CONFLICT", "来源同步投影已变化，请重新检查");
        }
        IntentSnapshot snapshot = new IntentSnapshot(
                artifact.shipmentId(), artifact.sourceChannel().name(), idempotencyKey, platformIntentKey,
                check.checkHash(), check.artifactHash(), artifact.sourceLineRef(), artifact.carrierCode(),
                artifact.trackingNumber(), version);
        int registryUpdated = jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET target_type='SHIPMENT_SOURCE_SYNC', target_id=?,
                    error_snapshot=?::jsonb, updated_at=CURRENT_TIMESTAMP
                WHERE scope=? AND idempotency_key=? AND status='IN_PROGRESS'
                """,
                String.valueOf(artifact.shipmentId()), json(Map.of("intent", snapshot)),
                EXECUTE_SCOPE, idempotencyKey);
        if (registryUpdated != 1) {
            throw BusinessException.conflict("SOURCE_SYNC_IDEMPOTENCY_CLAIM_LOST", "执行幂等租约已失效");
        }
        return new SourceSyncIntent(
                artifact.shipmentId(), artifact.orderId(), idempotencyKey, platformIntentKey,
                check.checkHash(), check.artifactHash(), artifact.sourceLineRef(), artifact.carrierCode(),
                artifact.trackingNumber(), version, result);
    }

    /** 紧贴每一次远端不可逆调用，在 generic claim 校验后提交 effect_started 标记。 */
    @Transactional
    public void markEffectStarted(SourceSyncIntent intent) {
        requireTransaction("source-sync effect marker");
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET effect_started_at=COALESCE(effect_started_at, CURRENT_TIMESTAMP),
                    updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='SYNCING'
                  AND intent_key=? AND check_hash=? AND artifact_hash=?
                """,
                intent.shipmentId(), intent.result().channel().name(), intent.intentKey(),
                intent.checkHash(), intent.artifactHash());
        if (updated != 1) {
            throw BusinessException.conflict("SOURCE_SYNC_INTENT_LOST", "来源同步意图已变化，禁止继续外部写");
        }
        int registryUpdated = jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET effect_started_at=COALESCE(effect_started_at, CURRENT_TIMESTAMP),
                    updated_at=CURRENT_TIMESTAMP
                WHERE scope=? AND idempotency_key=? AND status='IN_PROGRESS'
                  AND lease_expires_at > statement_timestamp()
                """,
                EXECUTE_SCOPE, intent.intentKey());
        if (registryUpdated != 1) {
            throw BusinessException.conflict("SOURCE_SYNC_IDEMPOTENCY_CLAIM_LOST", "执行租约已失效，禁止继续外部写");
        }
    }

    public SourceSyncOutcome completeSuccess(SourceSyncIntent intent, SourceSyncResult result) {
        requireTransaction("source-sync completion");
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET sync_status='SYNCED', last_error_code=NULL, last_error_message=NULL,
                    synced_at=CURRENT_TIMESTAMP, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='SYNCING'
                  AND intent_key=? AND check_hash=? AND artifact_hash=? AND lock_version=?
                """,
                intent.shipmentId(), intent.result().channel().name(), intent.intentKey(),
                intent.checkHash(), intent.artifactHash(), intent.version());
        requireProjectionUpdate(updated);
        String platformRef = safePlatformRef(result == null ? null : result.platformRef());
        return new SourceSyncOutcome(
                intent.shipmentId(), SourceSyncStatus.SYNCED, "SOURCE_SYNC_VERIFIED", "来源平台已验证完成回传",
                intent.checkHash(), intent.version() + 1,
                platformRef.isBlank() ? null : platformRef,
                OffsetDateTime.now());
    }

    public void completeSafeFailure(SourceSyncIntent intent, SourceSyncResult result) {
        requireTransaction("source-sync safe failure");
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET sync_status='SYNC_FAILED', last_error_code=?, last_error_message=?,
                    synced_at=NULL, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='SYNCING'
                  AND intent_key=? AND check_hash=? AND artifact_hash=? AND lock_version=?
                """,
                safeCode(result), safeMessage(result), intent.shipmentId(), intent.result().channel().name(),
                intent.intentKey(), intent.checkHash(), intent.artifactHash(), intent.version());
        requireProjectionUpdate(updated);
    }

    public void completeReconciliationRequired(SourceSyncIntent intent, SourceSyncResult result, String reasonCode) {
        requireTransaction("source-sync reconciliation transition");
        String code = reasonCode == null || reasonCode.isBlank() ? "RECONCILIATION_REQUIRED" : reasonCode;
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET sync_status='RECONCILIATION_REQUIRED', last_error_code=?, last_error_message=?,
                    synced_at=NULL, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='SYNCING'
                  AND intent_key=? AND check_hash=? AND artifact_hash=? AND lock_version=?
                """,
                code, safeMessage(result), intent.shipmentId(), intent.result().channel().name(),
                intent.intentKey(), intent.checkHash(), intent.artifactHash(), intent.version());
        requireProjectionUpdate(updated);
        IntentSnapshot snapshot = snapshot(intent);
        int registryUpdated = jdbc.update(
                """
                UPDATE app.idempotency_registry
                SET status='RECONCILIATION_REQUIRED', response_snapshot=?::jsonb,
                    error_snapshot=NULL, completed_at=CURRENT_TIMESTAMP, lease_expires_at=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE scope=? AND idempotency_key=? AND status='IN_PROGRESS'
                """,
                json(Map.of(
                        "intent", snapshot,
                        "business_code", code,
                        "platform_ref", safePlatformRef(result == null ? null : result.platformRef()))),
                EXECUTE_SCOPE, intent.intentKey());
        if (registryUpdated != 1) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_IDEMPOTENCY_CLAIM_LOST", "无法把未知外部结果登记为待对账");
        }
    }

    /** 锁定并核对对账命令与持久原意图。 */
    public ReconciliationIntent lockReconciliation(long shipmentId, SourceSyncReconcileCommand command) {
        requireTransaction("source-sync reconciliation");
        List<ReconciliationIntent> rows = jdbc.query(
                """
                SELECT source_channel, intent_key, platform_intent_key, check_hash, artifact_hash,
                       source_line_ref, carrier_code, tracking_number, lock_version
                FROM app.shipment_syncs
                WHERE shipment_id=? AND sync_status='RECONCILIATION_REQUIRED'
                FOR UPDATE
                """,
                (rs, rowNum) -> new ReconciliationIntent(
                        shipmentId,
                        SourceChannel.valueOf(rs.getString("source_channel")),
                        rs.getString("intent_key"),
                        rs.getString("platform_intent_key"),
                        rs.getString("check_hash"),
                        rs.getString("artifact_hash"),
                        rs.getString("source_line_ref"),
                        rs.getString("carrier_code"),
                        rs.getString("tracking_number"),
                        rs.getLong("lock_version")),
                shipmentId);
        if (rows.isEmpty()) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_RECONCILIATION_NOT_REQUIRED", "该 Shipment 当前不处于待对账状态");
        }
        ReconciliationIntent intent = rows.getFirst();
        if (intent.version() != command.expectedVersion()
                || !intent.checkHash().equals(command.expectedCheckHash())
                || !intent.sourceLineRef().equals(command.expectedSourceLineRef())
                || !intent.carrierCode().equals(command.expectedCarrierCode())
                || !intent.trackingNumber().equals(command.expectedTrackingNumber())) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_RECONCILIATION_INTENT_CHANGED", "对账命令与原始来源回传意图不一致");
        }
        return intent;
    }

    public SourceSyncOutcome applyReconciliation(
            ReconciliationIntent intent,
            SourceSyncReconciliationDecision decision,
            String note) {
        requireTransaction("source-sync reconciliation completion");
        return switch (decision) {
            case ACCEPTED -> accepted(intent);
            case NOT_ACCEPTED -> notAccepted(intent);
            case UNCERTAIN -> uncertain(intent, note);
        };
    }

    private SourceSyncOutcome accepted(ReconciliationIntent intent) {
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET sync_status='SYNCED', last_error_code=NULL, last_error_message=NULL,
                    synced_at=CURRENT_TIMESTAMP, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='RECONCILIATION_REQUIRED'
                  AND lock_version=? AND intent_key=?
                """,
                intent.shipmentId(), intent.channel().name(), intent.version(), intent.intentKey());
        requireProjectionUpdate(updated);
        SourceSyncOutcome outcome = new SourceSyncOutcome(
                intent.shipmentId(), SourceSyncStatus.SYNCED, "SOURCE_SYNC_RECONCILED_ACCEPTED",
                "人工证据确认平台已受理，未再次写入平台", intent.checkHash(), intent.version() + 1,
                OffsetDateTime.now());
        resolveOuterAttempt(intent, "SUCCEEDED", "SOURCE_SYNC_RECONCILED_ACCEPTED", outcome);
        return outcome;
    }

    private SourceSyncOutcome notAccepted(ReconciliationIntent intent) {
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET sync_status='PENDING', intent_key=NULL, platform_intent_key=NULL,
                    check_hash=NULL, artifact_hash=NULL, source_line_ref=NULL,
                    carrier_code=NULL, tracking_number=NULL,
                    intent_started_at=NULL, effect_started_at=NULL,
                    last_error_code=NULL, last_error_message=NULL, synced_at=NULL,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='RECONCILIATION_REQUIRED'
                  AND lock_version=? AND intent_key=?
                """,
                intent.shipmentId(), intent.channel().name(), intent.version(), intent.intentKey());
        requireProjectionUpdate(updated);
        SourceSyncOutcome outcome = new SourceSyncOutcome(
                intent.shipmentId(), SourceSyncStatus.PENDING, "SOURCE_SYNC_RECONCILED_NOT_ACCEPTED",
                "人工证据确认平台未受理；已释放原意图，必须重新检查并确认", intent.checkHash(),
                intent.version() + 1, OffsetDateTime.now());
        resolveOuterAttempt(intent, "FAILED", "SOURCE_SYNC_RECONCILED_NOT_ACCEPTED", outcome);
        return outcome;
    }

    private SourceSyncOutcome uncertain(ReconciliationIntent intent, String note) {
        int updated = jdbc.update(
                """
                UPDATE app.shipment_syncs
                SET last_error_code='RECONCILIATION_REQUIRED', last_error_message=?,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND source_channel=? AND sync_status='RECONCILIATION_REQUIRED'
                  AND lock_version=? AND intent_key=?
                """,
                noteSummary(note), intent.shipmentId(), intent.channel().name(),
                intent.version(), intent.intentKey());
        requireProjectionUpdate(updated);
        return new SourceSyncOutcome(
                intent.shipmentId(), SourceSyncStatus.RECONCILIATION_REQUIRED,
                "SOURCE_SYNC_RECONCILIATION_STILL_UNCERTAIN", "平台结果仍不确定，继续禁止自动重试",
                intent.checkHash(), intent.version() + 1, OffsetDateTime.now());
    }

    private void resolveOuterAttempt(
            ReconciliationIntent intent,
            String status,
            String code,
            SourceSyncOutcome outcome) {
        String snapshotColumn = "SUCCEEDED".equals(status) ? "response_snapshot" : "error_snapshot";
        String sql = "UPDATE app.idempotency_registry SET status=?, " + snapshotColumn
                + "=?::jsonb, completed_at=CURRENT_TIMESTAMP, lease_expires_at=NULL, updated_at=CURRENT_TIMESTAMP "
                + "WHERE scope=? AND idempotency_key=? AND status='RECONCILIATION_REQUIRED'";
        Object snapshot = "SUCCEEDED".equals(status)
                ? Map.of("http_status", 201, "body", outcome)
                : Map.of("business_code", code, "shipment_id", intent.shipmentId());
        int updated = jdbc.update(
                sql, status, json(snapshot),
                EXECUTE_SCOPE, intent.intentKey());
        if (updated != 1) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_RECONCILIATION_CAS_CONFLICT", "原执行意图状态已变化，未完成对账");
        }
    }

    @Transactional
    public void reconcileReviewCase(SourceSyncCheck check, String operator) {
        if (check.ready() && check.projection().status() == SourceSyncStatus.SYNCED) {
            jdbc.update(
                    """
                    UPDATE app.review_cases
                    SET status='RESOLVED', resolution=jsonb_build_object(
                            'resolution_type','SOURCE_SYNC_VERIFIED',
                            'status','SYNCED',
                            'business_code','SOURCE_SYNC_VERIFIED',
                            'blocker_codes',jsonb_build_array(),
                            'next_action','无需操作；来源平台已验证同步成功'),
                        resolution_version=resolution_version+1,
                        resolved_by=?, resolved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                    WHERE shipment_id=? AND reason_code='SOURCE_SYNC_BLOCKED' AND status='OPEN'
                    """,
                    operator, check.shipmentId());
            return;
        }
        if (check.ready()) {
            return;
        }
        String detail = json(Map.of(
                "message", "Shipment 来源回传检查存在阻断项",
                "status", check.projection().status().name(),
                "business_code", "SOURCE_SYNC_CHECK_BLOCKED",
                "check_hash", check.checkHash(),
                "blocker_codes", check.blockers().stream().map(SourceSyncBlocker::code).toList()));
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, shipment_id, detail)
                VALUES (?, 'SOURCE_SYNC', 'OPEN', 'FULFILLMENT_OPS', 'SOURCE_SYNC_BLOCKED', ?, ?, ?::jsonb)
                ON CONFLICT (case_no) DO UPDATE SET
                    detail=EXCLUDED.detail, updated_at=CURRENT_TIMESTAMP
                WHERE app.review_cases.status='OPEN'
                """,
                "RC-SOURCE-SYNC-" + check.shipmentId() + "-" + check.checkHash().substring(0, 12),
                check.internal().orderId(), check.shipmentId(), detail);
    }

    public void openExecutionReview(SourceSyncIntent intent, SourceSyncStatus status, String businessCode) {
        requireTransaction("source-sync review projection");
        String safeCode = businessCode != null && businessCode.matches("[A-Z0-9._-]{1,64}")
                ? businessCode : "SOURCE_SYNC_EXECUTION_FAILED";
        String detail = json(Map.of(
                "message", status == SourceSyncStatus.RECONCILIATION_REQUIRED
                        ? "来源平台写结果未知，必须人工对账"
                        : "来源平台回传安全失败，修正后需重新检查",
                "status", status.name(),
                "business_code", safeCode,
                "check_hash", intent.checkHash()));
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, shipment_id, detail)
                VALUES (?, 'SOURCE_SYNC', 'OPEN', 'FULFILLMENT_OPS', 'SOURCE_SYNC_BLOCKED', ?, ?, ?::jsonb)
                ON CONFLICT (case_no) DO NOTHING
                """,
                "RC-SOURCE-SYNC-EXEC-" + intent.shipmentId() + "-" + intent.version(),
                intent.orderId(), intent.shipmentId(), detail);
    }

    @Transactional
    public int recoverExpiredSyncing() {
        return recoverExpiredSyncing(50);
    }

    @Transactional
    public int recoverExpiredSyncing(int batchSize) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 500));
        List<RecoveryCandidate> candidates = jdbc.query(
                """
                SELECT ss.shipment_id, ss.source_channel, ss.intent_key, ss.check_hash,
                       ss.artifact_hash, ss.source_line_ref, ss.carrier_code, ss.tracking_number,
                       ss.lock_version, ss.effect_started_at IS NOT NULL effect_started
                FROM app.shipment_syncs ss
                LEFT JOIN app.idempotency_registry ir
                  ON ir.scope=? AND ir.idempotency_key=ss.intent_key
                WHERE ss.sync_status='SYNCING'
                  AND (ir.idempotency_key IS NULL OR ir.status<>'IN_PROGRESS'
                       OR ir.lease_expires_at IS NULL OR ir.lease_expires_at<=statement_timestamp())
                ORDER BY ss.shipment_id
                LIMIT ?
                FOR UPDATE OF ss SKIP LOCKED
                """,
                (rs, rowNum) -> new RecoveryCandidate(
                        rs.getLong("shipment_id"), SourceChannel.valueOf(rs.getString("source_channel")),
                        rs.getString("intent_key"), rs.getString("check_hash"), rs.getString("artifact_hash"),
                        rs.getString("source_line_ref"), rs.getString("carrier_code"),
                        rs.getString("tracking_number"), rs.getLong("lock_version"),
                        rs.getBoolean("effect_started")),
                EXECUTE_SCOPE, safeBatchSize);
        int recovered = 0;
        for (RecoveryCandidate candidate : candidates) {
            String next = candidate.effectStarted() ? "RECONCILIATION_REQUIRED" : "SYNC_FAILED";
            String code = candidate.effectStarted()
                    ? "SOURCE_SYNC_EXPIRED_AFTER_EFFECT"
                    : "SOURCE_SYNC_EXPIRED_BEFORE_EFFECT";
            String recoverySnapshot = json(Map.of(
                    "business_code", code,
                    "intent", candidate));
            int registryUpdated;
            if (candidate.effectStarted()) {
                registryUpdated = jdbc.update(
                        """
                        INSERT INTO app.idempotency_registry
                            (scope, idempotency_key, payload_hash, status, target_type, target_id,
                             response_snapshot, completed_at)
                        VALUES (?, ?, ?, 'RECONCILIATION_REQUIRED', 'SHIPMENT_SOURCE_SYNC', ?,
                                ?::jsonb, CURRENT_TIMESTAMP)
                        ON CONFLICT (scope, idempotency_key) DO UPDATE SET
                            status='RECONCILIATION_REQUIRED', owner_token=NULL,
                            lease_expires_at=NULL, response_snapshot=EXCLUDED.response_snapshot,
                            error_snapshot=NULL, completed_at=CURRENT_TIMESTAMP,
                            updated_at=CURRENT_TIMESTAMP
                        WHERE app.idempotency_registry.status IN
                            ('IN_PROGRESS','FAILED','RECONCILIATION_REQUIRED')
                        """,
                        EXECUTE_SCOPE,
                        candidate.intentKey(),
                        recoveryPayloadHash(candidate),
                        String.valueOf(candidate.shipmentId()),
                        recoverySnapshot);
            } else {
                registryUpdated = jdbc.update(
                        """
                        INSERT INTO app.idempotency_registry
                            (scope, idempotency_key, payload_hash, status, target_type, target_id,
                             error_snapshot, completed_at)
                        VALUES (?, ?, ?, 'FAILED', 'SHIPMENT_SOURCE_SYNC', ?,
                                ?::jsonb, CURRENT_TIMESTAMP)
                        ON CONFLICT (scope, idempotency_key) DO UPDATE SET
                            status='FAILED', owner_token=NULL, lease_expires_at=NULL,
                            effect_started_at=NULL, error_snapshot=EXCLUDED.error_snapshot,
                            completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                        WHERE app.idempotency_registry.status IN
                            ('IN_PROGRESS','FAILED','RECONCILIATION_REQUIRED')
                        """,
                        EXECUTE_SCOPE,
                        candidate.intentKey(),
                        recoveryPayloadHash(candidate),
                        String.valueOf(candidate.shipmentId()),
                        recoverySnapshot);
            }
            if (registryUpdated != 1) {
                continue;
            }
            int updated = jdbc.update(
                    """
                    UPDATE app.shipment_syncs
                    SET sync_status=?, last_error_code=?, last_error_message=?,
                        lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE shipment_id=? AND source_channel=? AND sync_status='SYNCING'
                      AND lock_version=? AND intent_key=?
                    """,
                    next, code,
                    candidate.effectStarted()
                            ? "执行租约在外部效果开始后失效，必须人工对账"
                            : "执行租约在外部效果开始前失效，可经新检查重试",
                    candidate.shipmentId(), candidate.channel().name(), candidate.version(), candidate.intentKey());
            if (updated != 1) {
                continue;
            }
            jdbc.update(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, status, responsible_team, reason_code,
                         order_id, shipment_id, detail)
                    SELECT ?, 'SOURCE_SYNC', 'OPEN', 'FULFILLMENT_OPS', 'SOURCE_SYNC_BLOCKED',
                           s.order_id, s.id,
                           jsonb_build_object('message', ?, 'status', ?, 'business_code', ?)
                    FROM app.shipments s WHERE s.id=?
                    ON CONFLICT (case_no) DO NOTHING
                    """,
                    "RC-SOURCE-SYNC-RECOVERY-" + candidate.shipmentId() + "-" + (candidate.version() + 1),
                    candidate.effectStarted()
                            ? "过期来源回传可能已产生平台效果，必须人工对账"
                            : "过期来源回传未开始平台效果，可重新检查",
                    next,
                    code,
                    candidate.shipmentId());
            recovered++;
        }
        return recovered;
    }

    private static String platformIntentKey(SourceSyncFacts artifact) {
        if (artifact.sourceChannel() != SourceChannel.JUFUBAO) {
            return null;
        }
        return JufubaoShipmentAttemptStore.idempotencyKey(
                artifact.sourceLineRef(), artifact.trackingNumber());
    }

    private String recoveryPayloadHash(RecoveryCandidate candidate) {
        return hashes.hash(Map.of(
                "shipment_id", candidate.shipmentId(),
                "expected_check_hash", candidate.checkHash()));
    }

    private static String safeCode(SourceSyncResult result) {
        String code = result == null ? null : result.businessCode();
        return code != null && code.matches("[A-Za-z0-9._-]{1,64}")
                ? code : "SOURCE_PLATFORM_WRITE_FAILED";
    }

    private static String safeMessage(SourceSyncResult result) {
        return result != null && "RECONCILIATION_REQUIRED".equals(result.businessCode())
                ? "来源平台写结果未知，必须人工对账"
                : "来源平台明确拒绝或在外部效果开始前安全失败";
    }

    private static String safePlatformRef(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : "";
    }

    private String noteSummary(String note) {
        String value = note == null ? "" : note;
        return "人工对账仍不确定；note_sha256=" + hashes.hash(value) + ";note_length=" + value.length();
    }

    private IntentSnapshot snapshot(SourceSyncIntent intent) {
        return new IntentSnapshot(
                intent.shipmentId(), intent.result().channel().name(), intent.intentKey(), intent.platformIntentKey(),
                intent.checkHash(), intent.artifactHash(), intent.sourceLineRef(), intent.carrierCode(),
                intent.trackingNumber(), intent.version());
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " requires an active transaction");
        }
    }

    private static void requireProjectionUpdate(int updated) {
        if (updated != 1) {
            throw BusinessException.conflict("SOURCE_SYNC_VERSION_CONFLICT", "来源同步投影已并发变化");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("来源同步 JSON 序列化失败", exception);
        }
    }

    private record IntentSnapshot(
            long shipmentId,
            String channel,
            String intentKey,
            String platformIntentKey,
            String checkHash,
            String artifactHash,
            String sourceLineRef,
            String carrierCode,
            String trackingNumber,
            long version) {}

    public record ReconciliationIntent(
            long shipmentId,
            SourceChannel channel,
            String intentKey,
            String platformIntentKey,
            String checkHash,
            String artifactHash,
            String sourceLineRef,
            String carrierCode,
            String trackingNumber,
            long version) {}

    private record RecoveryCandidate(
            long shipmentId,
            SourceChannel channel,
            String intentKey,
            String checkHash,
            String artifactHash,
            String sourceLineRef,
            String carrierCode,
            String trackingNumber,
            long version,
            boolean effectStarted) {}
}
