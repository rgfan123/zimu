package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.wecom.WecomGroupChatResolver;
import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadResult;
import cn.zimu.fulfillment.connector.wecom.WecomUploadStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadValidationException;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.order.CreateOperationalAlertCommand;
import cn.zimu.fulfillment.order.OperationalAlertSeverity;
import cn.zimu.fulfillment.order.OperationalAlertService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 单次 delivery 的执行状态机（Issue #84）。
 *
 * <p>纪律：外部调用（resolve chat / upload / send）绝不在持有长事务或行锁期间发生——
 * 每次尝试先短事务 CAS PENDING→SENDING，外部执行后短事务 finalize。崩溃落在 SENDING 时
 * 转 UNKNOWN + 告警，绝不盲重发（避免「外部已发成功但本地未落库」造成重复）。安全失败
 * （帧未提交）自动重试 1 次（外部总尝试 2 次，delivery max_attempts=2）后终态告警；
 * 已提交后的 TIMEOUT/LOST/非 retryable 一律 UNKNOWN，遵守 #81 未知态纪律。
 *
 * <p>告警收口是持久可恢复的：async task max_attempts=3，第 3 次领取只允许幂等 ensure
 * terminal alert，绝不再外部发送；告警创建失败使任务退避重试（第 3 次后任务 FAILED 可见），
 * 不静默吞掉。终态 delivery（FAILED/UNKNOWN）重进时先幂等 ensure 告警再 succeed。
 */
@Component
public class FulfillmentExportWecomDeliveryRunner {

    public static final String ALERT_TYPE = "FULFILLMENT_EXPORT_WECOM";
    public static final String STUCK_ERROR = "DELIVERY_STUCK_IN_SENDING";
    public static final String ALERT_FINALIZE_ERROR = "WECOM_ALERT_CREATE_FAILED";

    private static final Logger log = LoggerFactory.getLogger(FulfillmentExportWecomDeliveryRunner.class);

    private final FulfillmentExportWecomStore store;
    private final AsyncTaskStore taskStore;
    private final WecomGroupChatResolver groupChatResolver;
    private final WecomOutboundGateway wecomGateway;
    private final ContentAddressedFileStore fileStore;
    private final OperationalAlertService alerts;
    private final Duration backoff;

    public FulfillmentExportWecomDeliveryRunner(
            FulfillmentExportWecomStore store,
            AsyncTaskStore taskStore,
            WecomGroupChatResolver groupChatResolver,
            WecomOutboundGateway wecomGateway,
            ContentAddressedFileStore fileStore,
            OperationalAlertService alerts,
            @Value("${app.wecom-export-worker.backoff-seconds:30}") long backoffSeconds) {
        this.store = store;
        this.taskStore = taskStore;
        this.groupChatResolver = groupChatResolver;
        this.wecomGateway = wecomGateway;
        this.fileStore = fileStore;
        this.alerts = alerts;
        this.backoff = Duration.ofSeconds(Math.max(1, backoffSeconds));
    }

    /** Worker 入口：按 payload_ref 解析并执行（INITIAL 或 REMINDER）。 */
    public void execute(AsyncTaskStore.AsyncTask task) {
        Payload payload = parse(task.payloadRef());
        if (payload == null) {
            succeed(task);
            return;
        }
        if (FulfillmentExportWecomStore.INITIAL.equals(payload.kind())) {
            executeInitial(task, payload);
        } else {
            executeReminder(task, payload);
        }
    }

    // ------------------------------------------------------------------
    // INITIAL：resolve chat → upload → 文件消息 send
    // ------------------------------------------------------------------

    private void executeInitial(AsyncTaskStore.AsyncTask task, Payload payload) {
        FulfillmentExportWecomStore.Delivery delivery =
                store.delivery(payload.exportId(), FulfillmentExportWecomStore.INITIAL, payload.sequence())
                        .orElse(null);
        if (delivery == null) {
            succeed(task);
            return;
        }
        switch (delivery.status()) {
            case "SENT" -> succeed(task); // 已成功：幂等 no-op
            case "FAILED", "UNKNOWN" ->
                    finalizeAlertOrBackoff(task, payload, delivery, delivery.stage()); // 终态：幂等 ensure 告警再收口
            case "SENDING" -> {
                // 崩溃遗留：外部结局未知（可能已送达），绝不盲重发，转 UNKNOWN 人工对账
                store.markUnknown(
                        payload.exportId(),
                        FulfillmentExportWecomStore.INITIAL,
                        payload.sequence(),
                        STUCK_ERROR,
                        "Worker 在外部发送期间中断，结局未知，需人工对账");
                finalizeAlertOrBackoff(task, payload, store.delivery(
                                payload.exportId(), FulfillmentExportWecomStore.INITIAL, payload.sequence())
                        .orElse(delivery),
                        "SEND");
            }
            case "PENDING" -> {
                if (task.attempts() > delivery.maxAttempts()) {
                    succeed(task); // 防御：第 3 次领取只允许告警收口，绝不再外部发送
                    return;
                }
                runInitialAttempt(task, payload, delivery);
            }
            default -> succeed(task);
        }
    }

    private void runInitialAttempt(
            AsyncTaskStore.AsyncTask task, Payload payload, FulfillmentExportWecomStore.Delivery delivery) {
        boolean claimed = store.beginAttempt(
                payload.exportId(), FulfillmentExportWecomStore.INITIAL, payload.sequence(),
                task.attempts(), "RESOLVE_CHAT");
        if (!claimed) {
            succeed(task); // 停止/收齐后 no-op
            return;
        }
        FulfillmentExportWecomStore.ExportFacts facts = store.exportFacts(payload.exportId());

        String chatId;
        try {
            chatId = groupChatResolver.resolve(facts.providerId());
        } catch (BusinessException ex) {
            retryableOrTerminal(task, payload, "RESOLVE_CHAT", "WECOM_GROUP_CHAT_MISSING", ex.getMessage());
            return;
        }

        WecomUploadResult upload;
        try {
            upload = wecomGateway.upload(
                    fileStore.openRead(facts.fileRef()), facts.batchNo() + ".xlsx", WecomMediaType.FILE);
        } catch (WecomUploadValidationException ex) {
            // 前置校验失败（如文件超限）：确定性失败，直接终态 FAILED + 告警，绝不重试
            terminalFailed(task, payload, "UPLOAD", ex.code(), ex.getMessage());
            return;
        }
        switch (upload.status()) {
            case SUCCESS -> sendInitialFile(task, payload, facts, delivery.id(), chatId, upload);
            case FAILED -> {
                if (upload.retryable()) {
                    retryableOrTerminal(task, payload, "UPLOAD", stableUploadCode(upload), upload.errorMessage());
                } else {
                    unknown(task, payload, "UPLOAD", stableUploadCode(upload), upload.errorMessage());
                }
            }
            case UNKNOWN -> unknown(task, payload, "UPLOAD", stableUploadCode(upload), upload.errorMessage());
        }
    }

    private void sendInitialFile(
            AsyncTaskStore.AsyncTask task,
            Payload payload,
            FulfillmentExportWecomStore.ExportFacts facts,
            long deliveryId,
            String chatId,
            WecomUploadResult upload) {
        WecomSendResult sent = wecomGateway.send(WecomOutboundMessage.file(chatId, upload.mediaId()));
        switch (sent.status()) {
            case SUCCESS -> {
                Instant ack = sent.acknowledgedAt();
                FulfillmentExportWecomStore.ExportState state =
                        store.state(payload.exportId()).orElse(null);
                long slaMinutes = state == null ? 0 : state.slaMinutes();
                Instant due = ack.plus(Duration.ofMinutes(slaMinutes));
                store.markInitialSent(
                        payload.exportId(),
                        payload.sequence(),
                        deliveryId,
                        chatId,
                        sent.requestId(),
                        ack,
                        sha256(upload.mediaId()),
                        due,
                        due);
                // 成功人工重发（或任何 initial 成功）后只关闭该导出的遗留告警（shipment + detail.export_id），
                // 不误关共享同一 shipment/fulfillment 的其他导出；留可追溯证据
                Long shipmentId = store.firstShipmentId(payload.exportId());
                if (shipmentId != null) {
                    alerts.resolveWecomExportAlerts(shipmentId, payload.exportId(), "wecom_initial_ack");
                }
                succeed(task);
            }
            case FAILED -> {
                if (sent.retryable()) {
                    retryableOrTerminal(task, payload, "SEND", "WECOM_SEND_FAILED_RETRYABLE", sendErrorMessage(sent));
                } else {
                    unknown(task, payload, "SEND", "WECOM_SEND_FAILED", sendErrorMessage(sent));
                }
            }
            case TIMEOUT -> unknown(task, payload, "SEND", "ACK_TIMEOUT", "发送超时，结局未知，需人工对账");
        }
    }

    // ------------------------------------------------------------------
    // REMINDER：线性化发送前准备（锁 state 行复查）→ 快照群 markdown
    // ------------------------------------------------------------------

    private void executeReminder(AsyncTaskStore.AsyncTask task, Payload payload) {
        FulfillmentExportWecomStore.Delivery delivery =
                store.delivery(payload.exportId(), FulfillmentExportWecomStore.REMINDER, payload.sequence())
                        .orElse(null);
        if (delivery == null) {
            succeed(task);
            return;
        }
        switch (delivery.status()) {
            case "SENT" -> succeed(task);
            case "FAILED", "UNKNOWN" -> finalizeAlertOrBackoff(task, payload, delivery, delivery.stage());
            case "SENDING" -> {
                store.markUnknown(
                        payload.exportId(),
                        FulfillmentExportWecomStore.REMINDER,
                        payload.sequence(),
                        STUCK_ERROR,
                        "Worker 在外部发送期间中断，结局未知，需人工对账");
                finalizeAlertOrBackoff(task, payload, store.delivery(
                                payload.exportId(), FulfillmentExportWecomStore.REMINDER, payload.sequence())
                        .orElse(delivery),
                        "SEND");
            }
            case "PENDING" -> {
                if (task.attempts() > delivery.maxAttempts()) {
                    succeed(task); // 防御：第 3 次领取只允许告警收口，绝不再外部发送
                    return;
                }
                runReminderAttempt(task, payload, delivery);
            }
            default -> succeed(task);
        }
    }

    private void runReminderAttempt(
            AsyncTaskStore.AsyncTask task, Payload payload, FulfillmentExportWecomStore.Delivery delivery) {
        // 单短事务线性化：锁 state 行 FOR UPDATE + 同事务复查 ACTIVE/due/收齐全量 + CAS→SENDING。
        // 已收齐则同事务标 COMPLETED 并 no-op；import 先提交则本 CAS 必失败，绝不发送。
        FulfillmentExportWecomStore.ReminderPrepare prepared =
                store.prepareReminder(payload.exportId(), payload.sequence(), task.attempts(), "SEND");
        if (prepared != FulfillmentExportWecomStore.ReminderPrepare.CLAIMED) {
            succeed(task); // 已停止/已收齐（同事务已标 COMPLETED）/时间线已变：幂等 no-op
            return;
        }
        FulfillmentExportWecomStore.ExportState state =
                store.state(payload.exportId()).orElse(null);
        if (state == null || state.chatId() == null) {
            unknown(task, payload, "SEND", "REMINDER_CHAT_SNAPSHOT_MISSING",
                    "提醒群快照缺失，需人工对账");
            return;
        }
        FulfillmentExportWecomStore.ExportFacts facts = store.exportFacts(payload.exportId());
        long waitedMinutes = Math.max(0, Duration.between(state.initialSentAt(), Instant.now()).toMinutes());
        String content = WecomTrackingReminderMessage.build(
                facts.batchNo(), facts.providerName(), waitedMinutes,
                store.missingTrackingShipmentCount(payload.exportId()));
        WecomSendResult sent = wecomGateway.send(WecomOutboundMessage.markdown(state.chatId(), content));
        switch (sent.status()) {
            case SUCCESS -> {
                Instant ack = sent.acknowledgedAt();
                store.markReminderSent(
                        payload.exportId(),
                        delivery.id(),
                        sent.requestId(),
                        ack,
                        ack.plus(Duration.ofMinutes(state.intervalMinutes())));
                succeed(task);
            }
            case FAILED -> {
                if (sent.retryable()) {
                    retryableOrTerminal(task, payload, "SEND", "WECOM_SEND_FAILED_RETRYABLE", sendErrorMessage(sent));
                } else {
                    unknown(task, payload, "SEND", "WECOM_SEND_FAILED", sendErrorMessage(sent));
                }
            }
            case TIMEOUT -> unknown(task, payload, "SEND", "ACK_TIMEOUT", "发送超时，结局未知，需人工对账");
        }
    }

    // ------------------------------------------------------------------
    // 结局分支
    // ------------------------------------------------------------------

    /**
     * 可安全重试的失败：未达上限（外部总尝试 2 次）→ delivery 回 PENDING + 任务退避；已达 →
     * 终态 FAILED + 告警收口。
     */
    private void retryableOrTerminal(
            AsyncTaskStore.AsyncTask task, Payload payload, String stage, String errorCode, String errorMessage) {
        // 先读当前尝试计数（beginAttempt 已镜像 task 的 claim 计数），再决定重置或终态；
        // 顺序不能反：retryPending 会把 delivery 重置为 PENDING，终态转移要求 SENDING。
        FulfillmentExportWecomStore.Delivery current = store.delivery(
                payload.exportId(), payload.kind(), payload.sequence()).orElseThrow();
        if (current.attempts() >= current.maxAttempts()) {
            store.markFailed(payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage);
            finalizeAlertOrBackoff(task, payload, store.delivery(
                            payload.exportId(), payload.kind(), payload.sequence())
                    .orElse(current),
                    stage);
        } else {
            store.retryPending(
                    payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage);
            taskStore.fail(task.id(), task.leaseOwner(), errorCode, backoff);
        }
    }

    /** 结局未知：delivery UNKNOWN + 导出 UNKNOWN（initial）/暂停提醒（reminder）+ RED 告警。 */
    private void unknown(
            AsyncTaskStore.AsyncTask task, Payload payload, String stage, String errorCode, String errorMessage) {
        store.markUnknown(payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage);
        finalizeAlertOrBackoff(task, payload, store.delivery(
                        payload.exportId(), payload.kind(), payload.sequence())
                .orElseThrow(),
                stage);
    }

    /** 确定性终态失败（如上传前置校验失败）：delivery FAILED + 告警收口，绝不重试。 */
    private void terminalFailed(
            AsyncTaskStore.AsyncTask task, Payload payload, String stage, String errorCode, String errorMessage) {
        store.markFailed(payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage);
        finalizeAlertOrBackoff(task, payload, store.delivery(
                        payload.exportId(), payload.kind(), payload.sequence())
                .orElseThrow(),
                stage);
    }

    /**
     * 终态 delivery 的告警收口：幂等 ensure 成功后任务 succeed；告警创建失败使任务退避重试
     * （第 3 次后任务 FAILED，可见可查），绝不静默吞掉。重进时从 delivery 证据读取稳定错误。
     */
    private void finalizeAlertOrBackoff(
            AsyncTaskStore.AsyncTask task,
            Payload payload,
            FulfillmentExportWecomStore.Delivery delivery,
            String stage) {
        try {
            ensureAlert(payload, delivery, stage);
            succeed(task);
        } catch (RuntimeException ex) {
            log.warn(
                    "履约导出企微告警创建失败 exportId={} deliveryId={} error={}",
                    payload.exportId(), delivery.id(), ex.getMessage());
            taskStore.fail(task.id(), task.leaseOwner(), ALERT_FINALIZE_ERROR, backoff);
        }
    }

    /**
     * 终态失败/未知 → RED 运营告警：subject 用该导出第一个真实 shipment（+ fulfillment 业务
     * 主体）；detail 按 export_id/delivery_id 隔离，无 media_id/config/secret。
     */
    private void ensureAlert(
            Payload payload, FulfillmentExportWecomStore.Delivery delivery, String stage) {
        FulfillmentExportWecomStore.ExportFacts facts = store.exportFacts(payload.exportId());
        Long fulfillmentId = store.firstFulfillmentId(payload.exportId());
        Long shipmentId = store.firstShipmentId(payload.exportId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("export_id", String.valueOf(payload.exportId()));
        detail.put("delivery_id", String.valueOf(delivery.id()));
        detail.put("batch_no", facts.batchNo());
        detail.put("provider_code", facts.providerCode());
        detail.put("stage", stage);
        detail.put("stable_error", delivery.errorCode() == null ? "UNKNOWN" : delivery.errorCode());
        detail.put("error", delivery.errorMessage());
        detail.put("attempts", delivery.attempts());
        detail.put("kind", payload.kind());
        alerts.createSystem(new CreateOperationalAlertCommand(
                ALERT_TYPE,
                OperationalAlertSeverity.RED,
                null,
                null,
                fulfillmentId,
                shipmentId,
                "履约导出企微发送失败：批次 " + facts.batchNo() + "（" + facts.providerName() + "）",
                detail));
    }

    private void succeed(AsyncTaskStore.AsyncTask task) {
        taskStore.succeed(task.id(), task.leaseOwner());
    }

    /**
     * 上传稳定错误码：transport 的 errorMessage 已是稳定码（OUTBOUND_BACKPRESSURE/
     * FINISH_ACK_UNKNOWN/UPLOAD_FILE_TOO_LARGE/...），优先使用；仅数值 errcode 时拼本地
     * 前缀，绝不把裸数值当稳定码存库。
     */
    private static String stableUploadCode(WecomUploadResult upload) {
        if (upload.errorMessage() != null && !upload.errorMessage().isBlank()) {
            return upload.errorMessage();
        }
        return upload.errorCode() == null ? "WECOM_UPLOAD_FAILED" : "WECOM_UPLOAD_ERRCODE_" + upload.errorCode();
    }

    /**
     * send 错误消息 = 服务端 errmsg + 可选数值 errcode；服务端 errmsg 不直接当稳定码，
     * error_code 一律用本地稳定码（WECOM_SEND_FAILED_RETRYABLE/WECOM_SEND_FAILED/ACK_TIMEOUT）。
     */
    private static String sendErrorMessage(WecomSendResult sent) {
        if (sent.errorCode() == null) {
            return sent.errorMessage();
        }
        return sent.errorMessage() + " (errcode=" + sent.errorCode() + ")";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** payload_ref=export:{exportId}:{kind}:{sequence}。 */
    static Payload parse(String payloadRef) {
        String[] parts = payloadRef == null ? new String[0] : payloadRef.split(":");
        if (parts.length != 4 || !"export".equals(parts[0])) {
            return null;
        }
        try {
            return new Payload(Long.parseLong(parts[1]), parts[2], Integer.parseInt(parts[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    record Payload(long exportId, String kind, int sequence) {}
}
