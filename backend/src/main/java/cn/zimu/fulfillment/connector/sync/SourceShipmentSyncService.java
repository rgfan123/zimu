package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService.ExternalCompletion;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.PlatformConnector;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shipment 级来源回传深模块：调用方只使用 check / execute / reconcile。 */
@Service
public class SourceShipmentSyncService {

    private final SourceSyncFactsReader facts;
    private final SourceShipmentArtifactRegistry artifacts;
    private final PlatformConnectorRegistry connectors;
    private final SourceSyncPolicy policy;
    private final SourceSyncStore store;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final OrderEventService events;
    private final Duration executeLease;

    public SourceShipmentSyncService(
            SourceSyncFactsReader facts,
            SourceShipmentArtifactRegistry artifacts,
            PlatformConnectorRegistry connectors,
            SourceSyncPolicy policy,
            SourceSyncStore store,
            IdempotencyService idempotency,
            AuditLogService audits,
            OrderEventService events,
            @Value("${app.source-sync.idempotency-lease:PT10M}") Duration executeLease) {
        this.facts = facts;
        this.artifacts = artifacts;
        this.connectors = connectors;
        this.policy = policy;
        this.store = store;
        this.idempotency = idempotency;
        this.audits = audits;
        this.events = events;
        this.executeLease = executeLease;
    }

    /** 无审计、无状态写的只读 seam，供内部组合与测试使用。 */
    public SourceSyncCheck check(long shipmentId) {
        return prepare(shipmentId).check();
    }

    /** HTTP/MCP 检查 seam；审计只保存哈希与 blocker code，不复制 receiver。 */
    public SourceSyncCheck check(long shipmentId, CommandContext context, AuditActorType actorType) {
        if (actorType == AuditActorType.HUMAN) {
            requireAuthenticatedOperator(context);
        }
        SourceSyncCheck check = check(shipmentId);
        auditCheck(check, context, actorType);
        return check;
    }

    public IdempotentResult<SourceSyncOutcome> execute(
            long shipmentId,
            SourceSyncExecuteCommand command,
            String idempotencyKey,
            CommandContext context) {
        requireAuthenticatedOperator(context);
        if (command == null || command.expectedCheckHash() == null) {
            throw BusinessException.badRequest("SOURCE_SYNC_CHECK_HASH_REQUIRED", "执行来源回传必须携带检查哈希");
        }

        AtomicReference<SourceSyncCheck> blockedCheck = new AtomicReference<>();
        try {
            return idempotency.executeWithPreparedExternalWriteIntent(
                    SourceSyncStore.EXECUTE_SCOPE,
                    idempotencyKey,
                    Map.of("shipment_id", shipmentId, "expected_check_hash", command.expectedCheckHash()),
                    201,
                    executeLease,
                    () -> {
                        // 首次 claim 后立即重跑完整检查；same-key terminal replay 不再被新 projection hash 阻断。
                        Prepared prepared = prepare(shipmentId);
                        if (!Objects.equals(command.expectedCheckHash(), prepared.check().checkHash())) {
                            throw BusinessException.conflict(
                                    "SOURCE_SYNC_CHECK_STALE", "平台或 Shipment 事实已变化，请重新检查");
                        }
                        if (!prepared.check().ready()) {
                            blockedCheck.set(prepared.check());
                            throw BusinessException.unprocessable(
                                    "SOURCE_SYNC_CHECK_BLOCKED", "来源回传检查仍有阻断项");
                        }
                        return prepared;
                    },
                    prepared -> persistIntent(prepared, command, idempotencyKey, context),
                    (intent, claim) -> executeExternal(
                            intent, claim, connectors.require(intent.result().channel())),
                    (intent, result) -> complete(intent, result, context));
        } catch (BusinessException exception) {
            if ("SOURCE_SYNC_CHECK_BLOCKED".equals(exception.getBusinessCode())
                    && blockedCheck.get() != null) {
                // GET remains read-only; an explicit blocked POST projects actionable review facts.
                store.reconcileReviewCase(blockedCheck.get(), context.operator());
            }
            throw exception;
        }
    }

    /**
     * 批量人工执行 seam：逐 Shipment 调用既有幂等用例，每行单独收敛结果。
     * 本方法不建立整批事务，因此一个失败行不会回滚已经验证成功的兄弟 Shipment。
     */
    public SourceSyncBatchOutcome executeBatch(
            SourceSyncBatchExecuteCommand command,
            CommandContext context) {
        requireAuthenticatedOperator(context);
        if (command == null || command.items().isEmpty()) {
            throw BusinessException.badRequest("SOURCE_SYNC_BATCH_EMPTY", "批量来源回传至少需要一个 Shipment");
        }
        List<SourceSyncBatchOutcome.Item> items = new ArrayList<>();
        for (SourceSyncBatchExecuteCommand.Item item : command.items()) {
            try {
                IdempotentResult<SourceSyncOutcome> result = execute(
                        item.shipmentId(),
                        new SourceSyncExecuteCommand(item.expectedCheckHash()),
                        item.idempotencyKey(),
                        context);
                if (result.replayed()) {
                    String code = jsonText(result.replayedBody(), "business_code", "OK");
                    String message = jsonText(result.replayedBody(), "message", "来源回传幂等重放完成");
                    items.add(new SourceSyncBatchOutcome.Item(
                            item.shipmentId(),
                            result.httpStatus() >= 200 && result.httpStatus() < 300,
                            true,
                            result.httpStatus(),
                            code,
                            message,
                            null,
                            result.replayedBody()));
                } else {
                    SourceSyncOutcome outcome = result.result();
                    items.add(new SourceSyncBatchOutcome.Item(
                            item.shipmentId(),
                            true,
                            false,
                            result.httpStatus(),
                            outcome.businessCode(),
                            outcome.message(),
                            outcome,
                            null));
                }
            } catch (BusinessException exception) {
                items.add(new SourceSyncBatchOutcome.Item(
                        item.shipmentId(),
                        false,
                        false,
                        exception.getHttpStatus(),
                        exception.getBusinessCode(),
                        exception.getMessage(),
                        null,
                        null));
            } catch (RuntimeException exception) {
                items.add(new SourceSyncBatchOutcome.Item(
                        item.shipmentId(),
                        false,
                        false,
                        500,
                        "SOURCE_SYNC_INTERNAL_ERROR",
                        "该 Shipment 来源回传出现内部错误，请稍后重试或联系管理员",
                        null,
                        null));
            }
        }
        return new SourceSyncBatchOutcome(items);
    }

    public IdempotentResult<SourceSyncOutcome> reconcile(
            long shipmentId,
            SourceSyncReconcileCommand command,
            String idempotencyKey,
            CommandContext context) {
        requireAuthenticatedOperator(context);
        return idempotency.execute(
                SourceSyncStore.RECONCILE_SCOPE,
                idempotencyKey,
                Map.of("shipment_id", shipmentId, "command", command),
                200,
                () -> doReconcile(shipmentId, command, context));
    }

    private static String jsonText(
            com.fasterxml.jackson.databind.JsonNode body,
            String field,
            String fallback) {
        if (body == null || !body.path(field).isTextual() || body.path(field).asText().isBlank()) {
            return fallback;
        }
        return body.path(field).asText();
    }

    private Prepared prepare(long shipmentId) {
        SourceSyncFactsReader.Loaded loaded = facts.load(shipmentId);
        SourceShipmentArtifact artifact = SourceShipmentArtifact.empty();
        SourceSyncFactsReader.Loaded effective = loaded;
        if (loaded.blockers().isEmpty()) {
            try {
                artifact = artifacts.prepare(loaded.facts());
            } catch (BusinessException exception) {
                effective = withBlocker(loaded, exception.getBusinessCode(), "artifact", exception.getMessage());
            } catch (RuntimeException exception) {
                effective = withBlocker(
                        loaded, "SOURCE_SYNC_ARTIFACT_UNAVAILABLE", "artifact", "来源平台写入产物无法安全构造");
            }
        }
        PlatformConnector connector = connectors.require(loaded.facts().sourceChannel());
        SourceShipmentResult result = result(effective.facts(), artifact);
        SourcePlatformCheckResult platform;
        if (!effective.blockers().isEmpty() || !connector.capabilities().onlinePush()) {
            platform = SourcePlatformCheckResult.unavailable(
                    effective.facts().sourceChannel(),
                    !connector.capabilities().onlinePush()
                            ? "SOURCE_SYNC_CONNECTOR_CAPABILITY_UNAVAILABLE" : "SOURCE_SYNC_LOCAL_BLOCKED",
                    !connector.capabilities().onlinePush()
                            ? "来源 Connector 未声明在线回传能力" : "内部 Shipment 事实尚未通过门禁");
        } else {
            try {
                platform = connector.checkShipmentResult(result);
            } catch (RuntimeException exception) {
                platform = SourcePlatformCheckResult.unavailable(
                        effective.facts().sourceChannel(),
                        "SOURCE_PLATFORM_CHECK_UNAVAILABLE",
                        "来源平台当前事实读取失败");
            }
        }
        SourceSyncCheck check = policy.evaluate(effective, platform, artifact);
        return new Prepared(
                check, result.withExpectedPlatformEffectHash(platform.effectHash()), connector, platform);
    }

    private SourceSyncIntent persistIntent(
            Prepared prepared,
            SourceSyncExecuteCommand command,
            String idempotencyKey,
            CommandContext context) {
        if (!Objects.equals(command.expectedCheckHash(), prepared.check().checkHash())) {
            throw BusinessException.conflict("SOURCE_SYNC_CHECK_STALE", "平台或 Shipment 事实已变化，请重新检查");
        }
        if (!prepared.check().ready()) {
            throw BusinessException.unprocessable("SOURCE_SYNC_CHECK_BLOCKED", "来源回传检查仍有阻断项");
        }
        SourceSyncFactsReader.Loaded locked = facts.loadLocked(prepared.check().shipmentId());
        // 平台检查与 XLSX 构造已经在 claim 后、事务外完成。导入文件/原始行是不可变证据；
        // 短事务这里只锁定并重验业务事实，再复用已构造产物，避免持有连接执行文件 I/O/压缩。
        SourceShipmentArtifact lockedArtifact = prepared.result().artifact();
        String lockedArtifactHash = policy.artifactHash(locked.facts(), lockedArtifact);
        if (!Objects.equals(prepared.check().artifactHash(), lockedArtifactHash)) {
            throw BusinessException.conflict("SOURCE_SYNC_FACTS_CHANGED", "Shipment 事实在确认后已变化，请重新检查");
        }
        SourceShipmentResult lockedResult = result(locked.facts(), lockedArtifact)
                .withExpectedPlatformEffectHash(prepared.platform().effectHash());
        SourceSyncCheck stableCheck = policy.evaluate(locked, prepared.platform(), lockedArtifact);
        if (!stableCheck.ready()
                || !Objects.equals(prepared.check().checkHash(), stableCheck.checkHash())) {
            throw BusinessException.conflict("SOURCE_SYNC_CHECK_STALE", "来源回传检查在意图落盘前已失效");
        }
        SourceSyncIntent intent = store.begin(stableCheck, idempotencyKey, lockedResult);
        audits.record(audit(context, intent.orderId(), "shipment.source_sync.intent", AuditActorType.HUMAN,
                Map.of("shipment_id", intent.shipmentId(), "check_hash", intent.checkHash()),
                Map.of("status", "SYNCING", "version", intent.version()), 202, "SOURCE_SYNC_INTENT_RECORDED"));
        return intent;
    }

    private ExternalAttempt executeExternal(
            SourceSyncIntent intent,
            IdempotencyService.ExternalWriteClaim claim,
            PlatformConnector connector) {
        AtomicBoolean effectStarted = new AtomicBoolean();
        long startedAt = System.nanoTime();
        try {
            SourceSyncResult result = connector.pushShipmentResult(intent.result(), () -> {
                claim.verifyActive();
                store.markEffectStarted(intent);
                effectStarted.set(true);
            });
            return new ExternalAttempt(result, elapsedMillis(startedAt), effectStarted.get());
        } catch (RuntimeException exception) {
            SourceSyncResult result = effectStarted.get()
                    ? SourceSyncResult.failed(
                            "RECONCILIATION_REQUIRED",
                            "来源平台写入开始后结果未知，禁止盲目重试")
                    : SourceSyncResult.failed(
                            "SOURCE_PLATFORM_WRITE_FAILED",
                            "来源平台写入前失败，尚未产生远端效果");
            return new ExternalAttempt(result, elapsedMillis(startedAt), effectStarted.get());
        }
    }

    private ExternalCompletion<SourceSyncOutcome> complete(
            SourceSyncIntent intent,
            ExternalAttempt attempt,
            CommandContext context) {
        SourceSyncResult result = attempt == null ? null : attempt.result();
        int latencyMs = attempt == null ? 0 : attempt.latencyMs();
        SourceSyncFactsReader.Loaded current = facts.loadLocked(intent.shipmentId());
        SourceShipmentArtifact currentArtifact = intent.result().artifact();
        String currentHash = policy.artifactHash(current.facts(), currentArtifact);
        if (!Objects.equals(intent.artifactHash(), currentHash)) {
            if (!possibleExternalEffect(attempt, result)) {
                SourceSyncResult safeDrift = SourceSyncResult.failed(
                        "SOURCE_SYNC_FACTS_CHANGED_BEFORE_WRITE",
                        "平台写入前 Shipment 事实变化，必须重新检查");
                store.completeSafeFailure(intent, safeDrift);
                store.openExecutionReview(
                        intent, SourceSyncStatus.SYNC_FAILED, safeDrift.businessCode());
                auditOutcome(intent, context, SourceSyncStatus.SYNC_FAILED,
                        safeDrift.businessCode(), 409, null, latencyMs);
                return ExternalCompletion.failed(BusinessException.conflict(
                        safeDrift.businessCode(), safeDrift.message()));
            }
            if (!attempt.effectStarted()) {
                store.markEffectStarted(intent);
            }
            SourceSyncResult drift = SourceSyncResult.failed(
                    "SOURCE_SYNC_FACTS_CHANGED_AFTER_WRITE",
                    "外部调用期间 Shipment 事实变化，必须人工对账",
                    result == null ? null : result.platformRef());
            store.completeReconciliationRequired(intent, drift, "SOURCE_SYNC_FACTS_CHANGED_AFTER_WRITE");
            store.openExecutionReview(
                    intent, SourceSyncStatus.RECONCILIATION_REQUIRED, "SOURCE_SYNC_FACTS_CHANGED_AFTER_WRITE");
            auditOutcome(intent, context, SourceSyncStatus.RECONCILIATION_REQUIRED,
                    "SOURCE_SYNC_FACTS_CHANGED_AFTER_WRITE", 409,
                    result == null ? null : result.platformRef(), latencyMs);
            return ExternalCompletion.failed(BusinessException.conflict(
                    "RECONCILIATION_REQUIRED", "外部调用期间 Shipment 事实变化，必须人工对账"));
        }
        if (result != null && result.success()) {
            SourceSyncOutcome outcome = store.completeSuccess(intent, result);
            events.append(intent.orderId(), "SOURCE_SYNCED", null, null, intent.shipmentId(), null,
                    DataScope.BUSINESS,
                    Map.of("shipment_id", String.valueOf(intent.shipmentId()),
                            "source_channel", intent.result().channel().name()),
                    context.operator());
            auditOutcome(intent, context, SourceSyncStatus.SYNCED, outcome.businessCode(), 201,
                    outcome.platformRef(), latencyMs);
            store.reconcileReviewCase(checkAfterSuccess(intent), context.operator());
            return ExternalCompletion.succeeded(outcome);
        }
        if (result != null && "RECONCILIATION_REQUIRED".equals(result.businessCode())) {
            if (!attempt.effectStarted()) {
                // Adapter 内层可能重放一个历史未知结果，或在自身 marker 与外层 marker 之间崩溃。
                // 即使本次没有再次外调，也必须保守登记“可能已有平台效果”，才能单调进入人工对账。
                store.markEffectStarted(intent);
            }
            store.completeReconciliationRequired(intent, result, "RECONCILIATION_REQUIRED");
            store.openExecutionReview(intent, SourceSyncStatus.RECONCILIATION_REQUIRED, result.businessCode());
            auditOutcome(intent, context, SourceSyncStatus.RECONCILIATION_REQUIRED, result.businessCode(), 409,
                    result.platformRef(), latencyMs);
            return ExternalCompletion.failed(BusinessException.conflict(
                    "RECONCILIATION_REQUIRED", "来源平台写结果未知，必须人工对账"));
        }
        store.completeSafeFailure(intent, result);
        String code = result == null || result.businessCode() == null
                ? "SOURCE_PLATFORM_WRITE_FAILED" : result.businessCode();
        auditOutcome(intent, context, SourceSyncStatus.SYNC_FAILED, code, 502,
                result == null ? null : result.platformRef(), latencyMs);
        store.openExecutionReview(intent, SourceSyncStatus.SYNC_FAILED, code);
        return ExternalCompletion.failed(new BusinessException(
                502, code, result == null ? "来源平台回传失败" : result.message()));
    }

    private SourceSyncOutcome doReconcile(
            long shipmentId,
            SourceSyncReconcileCommand command,
            CommandContext context) {
        SourceSyncStore.ReconciliationIntent intent = store.lockReconciliation(shipmentId, command);
        SourceSyncFactsReader.Loaded current = facts.loadLocked(shipmentId);
        SourceShipmentArtifact artifact = artifacts.prepare(current.facts());
        boolean intentFactsChanged = !Objects.equals(intent.sourceLineRef(), current.facts().sourceLineRef())
                || !Objects.equals(intent.carrierCode(), current.facts().carrierCode())
                || !Objects.equals(intent.trackingNumber(), current.facts().trackingNumber())
                || !Objects.equals(intent.artifactHash(), policy.artifactHash(current.facts(), artifact));
        if (intentFactsChanged) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_RECONCILIATION_FACTS_CHANGED",
                    "Shipment/Tracking/Carrier/数量事实已漂移，禁止应用旧对账结论");
        }
        PlatformConnector connector = connectors.require(intent.channel());
        if (command.decision() == SourceSyncReconciliationDecision.NOT_ACCEPTED
                && intent.platformIntentKey() != null
                && !connector.releaseShipmentIntent(intent.platformIntentKey())) {
            throw BusinessException.conflict(
                    "SOURCE_SYNC_PLATFORM_INTENT_RELEASE_FAILED",
                    "平台内部原始意图未能安全释放，仍禁止新回传");
        }
        SourceSyncOutcome outcome = store.applyReconciliation(intent, command.decision(), command.note());
        if (command.decision() == SourceSyncReconciliationDecision.ACCEPTED) {
            long orderId = current.facts().orderId();
            events.append(orderId, "SOURCE_SYNCED", null, null, shipmentId, null, DataScope.BUSINESS,
                    Map.of("shipment_id", String.valueOf(shipmentId), "reconciled", true), context.operator());
            store.reconcileReviewCase(checkAfterReconciliation(intent, current.facts()), context.operator());
        }
        audits.record(audit(context, current.facts().orderId(), "shipment.source_sync.reconcile",
                AuditActorType.HUMAN,
                Map.of("shipment_id", shipmentId, "decision", command.decision().name(), "note_present", true,
                        "expected_version", command.expectedVersion()),
                Map.of("status", outcome.status().name(), "version", outcome.version()),
                200, outcome.businessCode()));
        return outcome;
    }

    private SourceShipmentResult result(SourceSyncFacts facts, SourceShipmentArtifact artifact) {
        return new SourceShipmentResult(
                facts.sourceChannel(), facts.sourceRef(), facts.sourceLineRef(),
                facts.internalShippedQuantity(), facts.shippedSourceQuantity(), "SHIPPED",
                facts.carrierOutputValue(), facts.trackingNumber(), null,
                facts.receiverName(), facts.receiverPhone(), facts.receiverAddress(), facts.shipmentId(), artifact);
    }

    private SourceSyncFactsReader.Loaded withBlocker(
            SourceSyncFactsReader.Loaded loaded, String code, String field, String message) {
        List<SourceSyncBlocker> blockers = new ArrayList<>(loaded.blockers());
        blockers.add(new SourceSyncBlocker(code, field, message));
        return new SourceSyncFactsReader.Loaded(
                loaded.facts(), List.copyOf(blockers), loaded.projection(), loaded.reconciliationIntent());
    }

    private void auditCheck(SourceSyncCheck check, CommandContext context, AuditActorType actorType) {
        audits.record(audit(context, check.internal().orderId(), "shipment.source_sync.check", actorType,
                Map.of("shipment_id", check.shipmentId()),
                Map.of("ready", check.ready(), "check_hash", check.checkHash(),
                        "blocker_codes", check.blockers().stream()
                                .map(SourceSyncBlocker::code)
                                .map(SourceShipmentSyncService::safeAuditCode)
                                .distinct()
                                .toList()),
                200, check.ready() ? "SOURCE_SYNC_CHECK_READY" : "SOURCE_SYNC_CHECK_BLOCKED"));
    }

    private void auditOutcome(
            SourceSyncIntent intent,
            CommandContext context,
            SourceSyncStatus status,
            String code,
            int httpStatus,
            String platformRef,
            int latencyMs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status.name());
        String safePlatformRef = safePlatformRef(platformRef);
        if (safePlatformRef != null) {
            response.put("platform_ref", safePlatformRef);
        }
        audits.record(audit(context, intent.orderId(), "shipment.source_sync.execute", AuditActorType.HUMAN,
                Map.of("shipment_id", intent.shipmentId(), "check_hash", intent.checkHash(),
                        "source_line_ref_present", true, "tracking_present", true),
                response, httpStatus, code).latencyMs(Math.max(0, latencyMs)));
    }

    private AuditLogService.AuditCommand audit(
            CommandContext context,
            long orderId,
            String operation,
            AuditActorType actorType,
            Object request,
            Object response,
            int status,
            String code) {
        return new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(orderId)
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(actorType).service("source-sync").operation(operation)
                .requestPayload(request).responsePayload(response).httpStatus(status).businessCode(safeAuditCode(code));
    }

    private SourceSyncCheck checkAfterSuccess(SourceSyncIntent intent) {
        SourceSyncFacts facts = new SourceSyncFacts(
                intent.shipmentId(), intent.orderId(), intent.result().channel(), intent.result().sourceRef(),
                intent.sourceLineRef(), null, null, null, null, null, null, "FULLY_FULFILLED",
                intent.carrierCode(), null, null, intent.trackingNumber());
        return new SourceSyncCheck(
                intent.shipmentId(), true, intent.checkHash(), intent.artifactHash(), facts,
                SourcePlatformCheckResult.unavailable(intent.result().channel()), List.of(),
                new SourceSyncProjection(SourceSyncStatus.SYNCED, 0, intent.version() + 1, null, null, null));
    }

    private SourceSyncCheck checkAfterReconciliation(
            SourceSyncStore.ReconciliationIntent intent,
            SourceSyncFacts current) {
        return new SourceSyncCheck(
                intent.shipmentId(), true, intent.checkHash(), intent.artifactHash(), current,
                SourcePlatformCheckResult.unavailable(intent.channel()), List.of(),
                new SourceSyncProjection(
                        SourceSyncStatus.SYNCED, 0, intent.version() + 1, null, null, null));
    }

    private static void requireAuthenticatedOperator(CommandContext context) {
        if (context != null
                && context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())) {
            return;
        }
        throw BusinessException.forbidden(
                "SOURCE_SYNC_OPERATOR_UNAUTHORIZED",
                "来源平台回传必须由服务端已认证且身份一致的人工操作员执行");
    }

    private static String safeAuditCode(String code) {
        return code != null && code.matches("[A-Z0-9._-]{1,64}")
                ? code : "SOURCE_SYNC_PLATFORM_CODE_REDACTED";
    }

    private static String safePlatformRef(String value) {
        return value != null && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : null;
    }

    private static int elapsedMillis(long startedAt) {
        long elapsed = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        return (int) Math.min(Integer.MAX_VALUE, elapsed);
    }

    private static boolean possibleExternalEffect(ExternalAttempt attempt, SourceSyncResult result) {
        return attempt != null && (attempt.effectStarted()
                || (result != null && (result.success()
                        || "RECONCILIATION_REQUIRED".equals(result.businessCode()))));
    }

    private record Prepared(
            SourceSyncCheck check,
            SourceShipmentResult result,
            PlatformConnector connector,
            SourcePlatformCheckResult platform) {}

    private record ExternalAttempt(SourceSyncResult result, int latencyMs, boolean effectStarted) {}
}
