package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.FulfillmentProviderWecomConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 履约导出企微发送/提醒的应用层（Issue #84）。
 *
 * <p>职责：生成事务内登记出站状态并入队 initial delivery；到期提醒扫描（原子创建唯一
 * reminder delivery + task）；人工停止/重发的幂等写命令与审计；tracking 收齐后主动标
 * COMPLETED；API 投影。所有方法只做短事务内 DB 操作，外部上传/发送发生在
 * {@link FulfillmentExportWecomDeliveryRunner}（本类之外、无事务持有期间）。
 */
@Service
public class FulfillmentExportWecomService {

    /** async_tasks.task_type（唯一归属本深模块）。 */
    public static final String TASK_TYPE = "WECOM_EXPORT_DELIVERY";

    /**
     * delivery 的外部尝试上限（= 1 次自动重试 + 首次；与 {@code ..._deliveries.max_attempts}
     * 默认值一致）。
     */
    public static final int MAX_ATTEMPTS = 2;

    /**
     * async task 总领取上限（= 外部尝试 2 次 + 1 次告警收口）。第 3 次领取只允许幂等 ensure
     * terminal alert，绝不再外部发送；告警创建失败使任务退避，第 3 次后任务 FAILED 可见。
     */
    public static final int TASK_MAX_ATTEMPTS = 3;

    private final FulfillmentExportWecomStore store;
    private final AsyncTaskStore taskStore;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final FulfillmentProviderRepository providerRepository;

    public FulfillmentExportWecomService(
            FulfillmentExportWecomStore store,
            AsyncTaskStore taskStore,
            IdempotencyService idempotency,
            AuditLogService audits,
            FulfillmentProviderRepository providerRepository) {
        this.store = store;
        this.taskStore = taskStore;
        this.idempotency = idempotency;
        this.audits = audits;
        this.providerRepository = providerRepository;
    }

    /**
     * 生成事务内登记：创建 PENDING 状态行（SLA/提醒间隔快照）并原子入队 initial delivery
     * （外部尝试 maxAttempts=2；task 领取上限 3 次，第 3 次只做告警收口）。幂等键稳定
     * （wecom-export-initial:{exportId}），同一导出不会重复入队。仅第三方生成路径调用；
     * JD 路径不调用（= 不入队）。
     */
    @Transactional
    public void scheduleInitial(long exportId, long providerId, int slaMinutes) {
        int intervalMinutes = reminderIntervalSnapshot(providerId, slaMinutes);
        store.createState(exportId, providerId, slaMinutes, intervalMinutes);
        store.createDelivery(exportId, FulfillmentExportWecomStore.INITIAL, 1, 1);
        taskStore.enqueue(
                TASK_TYPE,
                payloadRef(exportId, FulfillmentExportWecomStore.INITIAL, 1),
                "wecom-export-initial:" + exportId,
                TASK_MAX_ATTEMPTS);
    }

    /** 人工停止：版本 CAS；已 COMPLETED/MANUALLY_STOPPED 再停幂等返回现状；LEGACY 明确拒绝。 */
    @Transactional
    public IdempotentResult<Map<String, Object>> stop(
            long exportId,
            WecomExportStopCommand command,
            String idempotencyKey,
            CommandContext context) {
        if (command == null || command.expectedVersion() == null) {
            throw BusinessException.unprocessable("WECOM_STOP_COMMAND_REQUIRED", "停止企微发送必须携带 expected_version");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw BusinessException.unprocessable("WECOM_STOP_REASON_REQUIRED", "停止企微发送必须填写理由");
        }
        Map<String, Object> payload = Map.of("export_id", exportId, "command", command);
        return idempotency.execute("fulfillment_export.wecom_stop", idempotencyKey, payload, 200, () -> {
            FulfillmentExportWecomStore.ExportState state = requireSendableState(exportId, "停止");
            if ("COMPLETED".equals(state.status()) || "MANUALLY_STOPPED".equals(state.status())) {
                return summary(exportId); // 幂等 no-op：已收齐/已停止
            }
            int updated = store.stop(exportId, command.expectedVersion(), context.operator(), command.reason());
            if (updated != 1) {
                throw BusinessException.conflict("VERSION_CONFLICT", "履约导出企微状态已更新，请刷新后重试");
            }
            Map<String, Object> result = summary(exportId);
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("fulfillment-export")
                    .operation("wecom_export.stop")
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(200)
                    .businessCode("WECOM_EXPORT_STOPPED"));
            return result;
        });
    }

    /**
     * 人工重发：只生成新的可追溯 INITIAL delivery（sequence=max+1）+ async task，不在 HTTP
     * 线程直接发送。已收齐（COMPLETED）或历史（LEGACY）明确拒绝；进行中的 INITIAL
     * （PENDING/SENDING，含旧自动发送未完成）并发禁止（409 WECOM_RESEND_IN_FLIGHT）；
     * 成功 ack 后时间线以新 ack 重置。
     */
    @Transactional
    public IdempotentResult<Map<String, Object>> resend(
            long exportId,
            WecomExportResendCommand command,
            String idempotencyKey,
            CommandContext context) {
        if (command == null || command.expectedVersion() == null) {
            throw BusinessException.unprocessable(
                    "WECOM_RESEND_COMMAND_REQUIRED", "重发企微发送必须携带 expected_version");
        }
        Map<String, Object> payload = Map.of("export_id", exportId, "command", command);
        return idempotency.execute("fulfillment_export.wecom_resend", idempotencyKey, payload, 202, () -> {
            // 先锁 state 行：并发重发/停止按导出串行化，in-flight 复查读到前一个命令的已提交结果
            store.lockState(exportId);
            FulfillmentExportWecomStore.ExportState state = requireSendableState(exportId, "重发");
            if ("COMPLETED".equals(state.status())) {
                throw BusinessException.unprocessable(
                        "WECOM_EXPORT_TRACKING_COMPLETE", "该导出运单已收齐，无需重发");
            }
            if (state.version() != command.expectedVersion()) {
                throw BusinessException.conflict("VERSION_CONFLICT", "履约导出企微状态已更新，请刷新后重试");
            }
            if (store.hasInFlightInitial(exportId)) {
                throw BusinessException.conflict(
                        "WECOM_RESEND_IN_FLIGHT", "该导出已有进行中的初始发送或重发任务，请勿重复提交");
            }
            int sequence = store.nextSequence(exportId, FulfillmentExportWecomStore.INITIAL);
            Optional<Long> deliveryId = store.createDelivery(
                    exportId, FulfillmentExportWecomStore.INITIAL, sequence, sequence);
            if (deliveryId.isEmpty()) {
                throw BusinessException.conflict(
                        "WECOM_RESEND_IN_FLIGHT", "该导出已有进行中的重发任务，请勿重复提交");
            }
            store.beginResend(exportId, command.expectedVersion());
            taskStore.enqueue(
                    TASK_TYPE,
                    payloadRef(exportId, FulfillmentExportWecomStore.INITIAL, sequence),
                    "wecom-export-initial:" + exportId + ":" + deliveryId.get(),
                    TASK_MAX_ATTEMPTS);
            Map<String, Object> result = summary(exportId);
            result.put("resend_delivery_id", String.valueOf(deliveryId.get()));
            result.put("resend_sequence", sequence);
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("fulfillment-export")
                    .operation("wecom_export.resend")
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(202)
                    .businessCode("WECOM_EXPORT_RESEND_SCHEDULED"));
            return result;
        });
    }

    /**
     * tracking 导入成功后的主动收齐判定（与发送前线性化复查一起保证「已收齐后不再催」）。
     * 在 import 事务内先锁同一 state 行再判定/标 COMPLETED（见 {@link
     * FulfillmentExportWecomStore#markTrackingReceived}）。
     */
    @Transactional
    public void markTrackingReceived(long exportId) {
        store.markTrackingReceived(exportId);
    }

    /**
     * 到期提醒扫描（多实例安全）：对每个到期 ACTIVE 导出原子创建唯一 reminder delivery
     * （UNIQUE (export_id, kind, sequence) + NOT EXISTS PENDING/SENDING 复查）+ 幂等
     * async task；重复轮询/多实例只创建一个 sequence，第一条 SENT 前不会生成 sequence2。
     */
    @Transactional
    public int scanDueReminders(int limit) {
        int created = 0;
        for (long exportId : store.dueReminderCandidates(limit)) {
            int sequence = store.nextSequence(exportId, FulfillmentExportWecomStore.REMINDER);
            int generation = store.latestInitialGeneration(exportId);
            if (store.createDelivery(exportId, FulfillmentExportWecomStore.REMINDER, sequence, generation).isPresent()) {
                taskStore.enqueue(
                        TASK_TYPE,
                        payloadRef(exportId, FulfillmentExportWecomStore.REMINDER, sequence),
                        "wecom-export-reminder:" + exportId + ":" + sequence,
                        TASK_MAX_ATTEMPTS);
                created++;
            }
        }
        return created;
    }

    /** API 投影（紧凑视图）：状态行存在时返回 wecom 摘要；否则 null。 */
    @Transactional(readOnly = true)
    public Map<String, Object> view(long exportId) {
        Optional<FulfillmentExportWecomStore.ExportState> state = store.state(exportId);
        return state.map(FulfillmentExportWecomService::view).orElse(null);
    }

    /** API 投影（完整）：紧凑视图 + delivery 证据列表（人工对账用）。 */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(long exportId) {
        Optional<FulfillmentExportWecomStore.ExportState> state = store.state(exportId);
        if (state.isEmpty()) {
            return null;
        }
        Map<String, Object> result = view(state.get());
        List<Map<String, Object>> deliveries = store.deliveries(exportId).stream().map(d -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(d.id()));
            row.put("kind", d.kind());
            row.put("sequence", d.sequence());
            row.put("status", d.status());
            row.put("attempts", d.attempts());
            row.put("max_attempts", d.maxAttempts());
            row.put("stage", d.stage());
            row.put("chat_id", d.chatId());
            row.put("request_id", d.requestId());
            row.put("ack_sent_at", d.ackSentAt());
            row.put("error_code", d.errorCode());
            row.put("error_message", d.errorMessage());
            return row;
        }).toList();
        result.put("deliveries", deliveries);
        return result;
    }

    static Map<String, Object> view(FulfillmentExportWecomStore.ExportState s) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", s.status());
        result.put("chat_id", s.chatId());
        result.put("tracking_sla_minutes", s.slaMinutes());
        result.put("reminder_interval_minutes", s.intervalMinutes());
        result.put("initial_sent_at", s.initialSentAt());
        result.put("tracking_due_at", s.trackingDueAt());
        result.put("next_reminder_at", s.nextReminderAt());
        result.put("last_reminded_at", s.lastRemindedAt());
        result.put("reminder_count", s.reminderCount());
        result.put("last_error", s.lastError());
        result.put("version", s.version());
        if (s.stoppedBy() != null) {
            Map<String, Object> stopped = new LinkedHashMap<>();
            stopped.put("by", s.stoppedBy());
            stopped.put("reason", s.stoppedReason());
            stopped.put("at", s.stoppedAt());
            result.put("stopped", stopped);
        }
        return result;
    }

    /**
     * 生成时的提醒间隔快照：经 {@link FulfillmentProviderRepository} 读履约方实体，再由
     * {@link FulfillmentProviderWecomConfig} 唯一解析。仅「键缺失/null」默认 SLA；非法存量
     * 值按契约显式回退默认；数据库故障原样上抛（不吞成 SLA 默认）。
     */
    private int reminderIntervalSnapshot(long providerId, int slaMinutes) {
        FulfillmentProvider provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new IllegalStateException("履约方不存在: " + providerId));
        return FulfillmentProviderWecomConfig.requireReminderInterval(provider.getConfig(), slaMinutes);
    }

    private FulfillmentExportWecomStore.ExportState requireSendableState(long exportId, String action) {
        Optional<FulfillmentExportWecomStore.ExportState> state = store.state(exportId);
        if (state.isEmpty()) {
            throw BusinessException.unprocessable(
                    "WECOM_EXPORT_NOT_REGISTERED", "该导出未纳入企微发送（仅本变更后生成的第三方导出支持" + action + "）");
        }
        if ("LEGACY".equals(state.get().status())) {
            throw BusinessException.unprocessable(
                    "WECOM_EXPORT_LEGACY", "历史导出未纳入企微发送，不支持" + action);
        }
        return state.get();
    }

    static String payloadRef(long exportId, String kind, int sequence) {
        return "export:" + exportId + ":" + kind + ":" + sequence;
    }
}
