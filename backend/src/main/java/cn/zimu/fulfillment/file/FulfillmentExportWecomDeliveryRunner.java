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
import java.nio.file.Path;
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
 * 不静默吞掉。终态 delivery（FAILED/UNKNOWN）重进时先幂等 ensure 告警再 succeed。INITIAL
 * 成功 ack 则两阶段收口（先原子落地 SENT 再同一事务关告警 + succeed），SENT 重进走同一
 * 收口，绝不重新 upload/send。
 */
@Component
public class FulfillmentExportWecomDeliveryRunner {

    public static final String ALERT_TYPE = "FULFILLMENT_EXPORT_WECOM";
    public static final String STUCK_ERROR = "DELIVERY_STUCK_IN_SENDING";
    public static final String ALERT_FINALIZE_ERROR = "WECOM_ALERT_CREATE_FAILED";
    public static final String ALERT_RESOLVE_ERROR = "WECOM_ALERT_RESOLVE_FAILED";

    private static final Logger log = LoggerFactory.getLogger(FulfillmentExportWecomDeliveryRunner.class);

    private final FulfillmentExportWecomStore store;
    private final AsyncTaskStore taskStore;
    private final WecomGroupChatResolver groupChatResolver;
    private final WecomOutboundGateway wecomGateway;
    private final ContentAddressedFileStore fileStore;
    private final OperationalAlertService alerts;
    private final FulfillmentExportWecomDeliveryFinalizer finalizer;
    private final Duration backoff;

    public FulfillmentExportWecomDeliveryRunner(
            FulfillmentExportWecomStore store,
            AsyncTaskStore taskStore,
            WecomGroupChatResolver groupChatResolver,
            WecomOutboundGateway wecomGateway,
            ContentAddressedFileStore fileStore,
            OperationalAlertService alerts,
            FulfillmentExportWecomDeliveryFinalizer finalizer,
            @Value("${app.wecom-export-worker.backoff-seconds:30}") long backoffSeconds) {
        this.store = store;
        this.taskStore = taskStore;
        this.groupChatResolver = groupChatResolver;
        this.wecomGateway = wecomGateway;
        this.fileStore = fileStore;
        this.alerts = alerts;
        this.finalizer = finalizer;
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
        handleInitialDelivery(task, payload, delivery);
    }

    private void handleInitialDelivery(
            AsyncTaskStore.AsyncTask task, Payload payload, FulfillmentExportWecomStore.Delivery delivery) {
        switch (delivery.status()) {
            case "SENT" ->
                    completeInitialSent(task, payload); // 已成功：幂等收口（关该导出告警 + succeed），绝不重新发送
            case "FAILED", "UNKNOWN" ->
                    finalizeAlertOrBackoff(task, payload, delivery, delivery.stage()); // 终态：幂等 ensure 告警再收口
            case "SUPERSEDED" ->
                    succeed(task); // 防御（INITIAL 不会被取代；状态机完整但绝不盲重发）
            case "SENDING" -> {
                // 崩溃遗留：外部结局未知（可能已送达），绝不盲重发，转 UNKNOWN 人工对账。
                // fenced：仅当前持有租约的 owner 才能落 UNKNOWN（旧 Worker 放弃，新 owner 处理）。
                if (finalizer.markUnknown(
                        task,
                        payload.exportId(),
                        FulfillmentExportWecomStore.INITIAL,
                        payload.sequence(),
                        STUCK_ERROR,
                        "Worker 在外部发送期间中断，结局未知，需人工对账") == FulfillmentExportWecomDeliveryFinalizer.FinalizeOutcome.APPLIED) {
                    finalizeAlertOrBackoff(task, payload, store.delivery(
                                    payload.exportId(), FulfillmentExportWecomStore.INITIAL, payload.sequence())
                            .orElse(delivery),
                            "SEND");
                }
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
        // claim 与 owner fence 同事务线性化：旧 owner 丢失租约后绝不 PENDING→SENDING、绝不 upload/send。
        FulfillmentExportWecomDeliveryFinalizer.ClaimOutcome outcome =
                finalizer.claimInitial(task, payload.exportId(), payload.sequence(), task.attempts());
        switch (outcome) {
            case CLAIMED -> {}
            case TERMINAL, IN_FLIGHT -> {
                // CAS 未命中：区分终态幂等与他方 SENDING，重读后重进状态机（绝不误 succeed）。
                FulfillmentExportWecomStore.Delivery current = store.delivery(
                                payload.exportId(), FulfillmentExportWecomStore.INITIAL, payload.sequence())
                        .orElse(null);
                if (current == null) {
                    succeed(task);
                    return;
                }
                handleInitialDelivery(task, payload, current);
                return;
            }
            case COMPLETED, NOOP -> {
                succeed(task); // 停止/收齐后 no-op
                return;
            }
            case LOST_LEASE, TASK_SUPERSEDED -> {
                return; // 旧 owner 静默放弃，新 owner 处理
            }
        }
        FulfillmentExportWecomStore.ExportFacts facts = store.exportFacts(payload.exportId());
        FulfillmentExportWecomStore.ExportState state = store.state(payload.exportId()).orElse(null);

        String chatId;
        if (state != null && state.chatId() != null && !state.chatId().isBlank()) {
            // 快照群（§6）：initial 已成功过（人工重发 sequence>1）→ 提醒与人工重发永远使用
            // 快照群，不重新解析；履约方改群只影响之后新生成的导出。
            chatId = state.chatId();
        } else {
            // 无快照（首次尝试或 initial 在 ack 前从未成功）：每次实际尝试都实时解析当前配置群。
            try {
                chatId = groupChatResolver.resolve(facts.providerId());
            } catch (BusinessException ex) {
                retryableOrTerminal(task, payload, "RESOLVE_CHAT", "WECOM_GROUP_CHAT_MISSING", ex.getMessage());
                return;
            }
        }

        // 上传前先把本次实际路由群写入 delivery 证据（recipient 证据）并推进阶段到 UPLOAD：
        // UNKNOWN/FAILED 时运营可据此回答「可能发到了哪个群」。绝不改 state.chat_id——
        // 快照只在 initial 成功 ack 时建立。CAS 未命中（delivery 已非当前 SENDING）→ 放弃。
        if (!store.markChatResolved(
                payload.exportId(), FulfillmentExportWecomStore.INITIAL, payload.sequence(), chatId)) {
            return;
        }

        Path file;
        try {
            file = fileStore.openRead(facts.fileRef());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 本地内容寻址读取失败：pre-submit 且确定性（文件缺失/不可读/引用越界），按安全
            // 重试预算（1 次重试，总尝试 2 次）→ 终态 FAILED + RED 告警，绝不 UNKNOWN、
            // 绝不调用 upload/send；错误码/消息稳定且不含 file_ref/path。
            retryableOrTerminal(task, payload, "UPLOAD", "WECOM_FILE_REF_UNAVAILABLE", "无法读取已留存导出文件");
            return;
        }

        // 上传前续租/复查所有权：租约/所有权已丢失（被第二实例重新领取）→ 旧 Worker 直接放弃，
        // 绝不调用 upload/send；新 owner 走 SENDING 恢复。
        if (!finalizer.renewLease(task)) {
            return;
        }

        WecomUploadResult upload;
        try {
            upload = wecomGateway.upload(file, facts.wecomFilename(), WecomMediaType.FILE);
        } catch (WecomUploadValidationException ex) {
            // 前置校验失败（如文件超限/缺失/不可读）：确定性失败，直接终态 FAILED + 告警，绝不重试。
            // 文件/路径类校验码映射为固定安全文案（defense in depth：即便源码回归重嵌 path 也不落库）。
            terminalFailed(task, payload, "UPLOAD", ex.code(), safeUploadValidationMessage(ex));
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
        // 上传后、发送前续租/复查所有权：租约/所有权丢失 → 旧 Worker 放弃，绝不发送。
        if (!finalizer.renewLease(task)) {
            return;
        }
        WecomSendResult sent = wecomGateway.send(WecomOutboundMessage.file(chatId, upload.mediaId()));
        switch (sent.status()) {
            case SUCCESS -> {
                Instant ack = sent.acknowledgedAt();
                FulfillmentExportWecomStore.ExportState state =
                        store.state(payload.exportId()).orElse(null);
                long slaMinutes = state == null ? 0 : state.slaMinutes();
                Instant due = ack.plus(Duration.ofMinutes(slaMinutes));
                // 两阶段本地收口（不重复外部调用）：
                //   阶段 1 fenced 原子落地 delivery SENT + state 时间线，任务保持 RUNNING/owned；
                //   阶段 2 在同一事务内只关闭该导出遗留告警 + succeedOwned。
                // 阶段 1/2 之间崩溃 → 任务仍 RUNNING，重进见 delivery SENT 走同一收口，绝不重新 upload/send。
                if (finalizer.finalizeInitialSent(
                        task,
                        payload.exportId(),
                        payload.sequence(),
                        deliveryId,
                        chatId,
                        sent.requestId(),
                        ack,
                        sha256(upload.mediaId()),
                        due,
                        due)) {
                    completeInitialSent(task, payload);
                }
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
        handleReminderDelivery(task, payload, delivery);
    }

    private void handleReminderDelivery(
            AsyncTaskStore.AsyncTask task, Payload payload, FulfillmentExportWecomStore.Delivery delivery) {
        switch (delivery.status()) {
            case "SENT" -> succeed(task);
            case "SUPERSEDED" -> succeed(task); // 旧提醒已被新 INITIAL 代际取代：幂等 no-op
            case "FAILED", "UNKNOWN" -> finalizeAlertOrBackoff(task, payload, delivery, delivery.stage());
            case "SENDING" -> {
                if (finalizer.markUnknown(
                        task,
                        payload.exportId(),
                        FulfillmentExportWecomStore.REMINDER,
                        payload.sequence(),
                        STUCK_ERROR,
                        "Worker 在外部发送期间中断，结局未知，需人工对账") == FulfillmentExportWecomDeliveryFinalizer.FinalizeOutcome.APPLIED) {
                    finalizeAlertOrBackoff(task, payload, store.delivery(
                                    payload.exportId(), FulfillmentExportWecomStore.REMINDER, payload.sequence())
                            .orElse(delivery),
                            "SEND");
                }
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
        // claim（owner fence + 锁 state 复查 ACTIVE/代际/due/收齐 + CAS→SENDING + recipient 证据）
        // 单短事务线性化：旧 owner 丢失租约后绝不 PENDING→SENDING、绝不发送。
        FulfillmentExportWecomDeliveryFinalizer.ClaimOutcome outcome =
                finalizer.claimReminder(task, payload.exportId(), payload.sequence(), task.attempts());
        switch (outcome) {
            case CLAIMED -> {}
            case TERMINAL, IN_FLIGHT -> {
                FulfillmentExportWecomStore.Delivery current = store.delivery(
                                payload.exportId(), FulfillmentExportWecomStore.REMINDER, payload.sequence())
                        .orElse(null);
                if (current == null) {
                    succeed(task);
                    return;
                }
                handleReminderDelivery(task, payload, current);
                return;
            }
            case COMPLETED, NOOP -> {
                succeed(task); // 已停止/已收齐（同事务已标 COMPLETED）/代际已变（已标 SUPERSEDED）：幂等 no-op
                return;
            }
            case LOST_LEASE, TASK_SUPERSEDED -> {
                return; // 旧 owner 静默放弃，新 owner 处理
            }
        }
        // CLAIMED：recipient 证据已在 claim 事务内持久化到 delivery。
        FulfillmentExportWecomStore.ExportState state =
                store.state(payload.exportId()).orElse(null);
        if (state == null || state.chatId() == null || state.chatId().isBlank()) {
            unknown(task, payload, "SEND", "REMINDER_CHAT_SNAPSHOT_MISSING",
                    "提醒群快照缺失，需人工对账");
            return;
        }
        FulfillmentExportWecomStore.ExportFacts facts = store.exportFacts(payload.exportId());
        long waitedMinutes = Math.max(0, Duration.between(state.initialSentAt(), Instant.now()).toMinutes());
        String content = WecomTrackingReminderMessage.build(
                facts.batchNo(), facts.providerName(), waitedMinutes,
                store.missingTrackingShipmentCount(payload.exportId()));
        // 提醒发送前续租/复查所有权：租约/所有权丢失 → 旧 Worker 放弃，绝不发送。
        if (!finalizer.renewLease(task)) {
            return;
        }
        WecomSendResult sent = wecomGateway.send(WecomOutboundMessage.markdown(state.chatId(), content));
        switch (sent.status()) {
            case SUCCESS -> {
                Instant ack = sent.acknowledgedAt();
                // fenced 原子 finalize（delivery SENDING→SENT + state 时间线 + succeedOwned；
                // 代际已变则只落 SUPERSEDED 证据）。
                finalizer.finalizeReminderSent(
                        task,
                        payload.exportId(),
                        delivery.id(),
                        sent.requestId(),
                        ack,
                        ack.plus(Duration.ofMinutes(state.intervalMinutes())));
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
     * 终态 FAILED + 告警收口。全部经 fenced 收口：旧 Worker 丢失租约后不得 retryPending/markFailed。
     */
    private void retryableOrTerminal(
            AsyncTaskStore.AsyncTask task, Payload payload, String stage, String errorCode, String errorMessage) {
        FulfillmentExportWecomDeliveryFinalizer.FailureFinalize outcome = finalizer.finalizeRetryableFailure(
                task, payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage, backoff);
        if (outcome == FulfillmentExportWecomDeliveryFinalizer.FailureFinalize.TERMINAL) {
            finalizeAlertOrBackoff(task, payload, store.delivery(
                            payload.exportId(), payload.kind(), payload.sequence())
                    .orElseThrow(),
                    stage);
        }
        // SUPERSEDED/ABORTED/RETRY_SCHEDULED：无需告警（delivery 已被取代/未改/已退避重排）。
    }

    /** 结局未知：delivery UNKNOWN + 导出 UNKNOWN（initial）/暂停提醒（reminder）+ RED 告警（fenced）。 */
    private void unknown(
            AsyncTaskStore.AsyncTask task, Payload payload, String stage, String errorCode, String errorMessage) {
        if (finalizer.markUnknown(
                task, payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage)
                == FulfillmentExportWecomDeliveryFinalizer.FinalizeOutcome.APPLIED) {
            finalizeAlertOrBackoff(task, payload, store.delivery(
                            payload.exportId(), payload.kind(), payload.sequence())
                    .orElseThrow(),
                    stage);
        }
    }

    /** 确定性终态失败（如上传前置校验失败）：delivery FAILED + 告警收口，绝不重试（fenced）。 */
    private void terminalFailed(
            AsyncTaskStore.AsyncTask task, Payload payload, String stage, String errorCode, String errorMessage) {
        if (finalizer.markFailed(
                task, payload.exportId(), payload.kind(), payload.sequence(), errorCode, errorMessage)
                == FulfillmentExportWecomDeliveryFinalizer.FinalizeOutcome.APPLIED) {
            finalizeAlertOrBackoff(task, payload, store.delivery(
                            payload.exportId(), payload.kind(), payload.sequence())
                    .orElseThrow(),
                    stage);
        }
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
        detail.put("initial_generation", delivery.initialGeneration());
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

    /**
     * INITIAL 成功 ack 的第二阶段收口：在同一事务内只关闭该导出的遗留告警 + succeedOwned
     * （见 {@link FulfillmentExportWecomDeliveryFinalizer#completeInitialSent}）。告警关闭失败
     * → 整事务回滚（任务未 SUCCEEDED、告警未关），退避重试后重进 delivery SENT 走同一收口，
     * 绝不重新 upload/send。
     */
    private void completeInitialSent(AsyncTaskStore.AsyncTask task, Payload payload) {
        try {
            // 告警关闭按成功 delivery 的 INITIAL 代际（= sequence）收窄：只关 <= 该代际的告警。
            finalizer.completeInitialSent(task, payload.exportId(), payload.sequence());
        } catch (RuntimeException ex) {
            log.warn("履约导出企微告警关闭失败 exportId={} error={}", payload.exportId(), ex.getMessage());
            taskStore.fail(task.id(), task.leaseOwner(), ALERT_RESOLVE_ERROR, backoff);
        }
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

    /**
     * 上传前置校验的落库文案（defense in depth）：文件/路径类校验码映射为固定安全文案，
     * 即使上传器源码回归重新把绝对 Path 嵌进异常消息，也不落库/不告警；其余校验码沿用其
     * 已不含 path 的中文可读消息。
     */
    private static String safeUploadValidationMessage(WecomUploadValidationException ex) {
        return switch (ex.code()) {
            case "UPLOAD_FILE_NOT_FOUND" -> "上传文件不存在";
            case "UPLOAD_FILE_NOT_REGULAR" -> "上传路径不是普通文件";
            case "UPLOAD_FILE_NOT_READABLE" -> "上传文件不可读";
            case "UPLOAD_FILE_SIZE_UNREADABLE" -> "无法读取文件大小";
            default -> ex.getMessage();
        };
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
