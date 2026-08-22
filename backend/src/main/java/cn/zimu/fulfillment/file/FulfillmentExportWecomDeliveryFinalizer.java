package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.AsyncTaskStore.ApplicationDisposition;
import cn.zimu.fulfillment.message.AsyncTaskStore.ApplicationFence;
import cn.zimu.fulfillment.order.OperationalAlertService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 履约导出企微 delivery 的租约 fence + 事务性 finalize 深模块（Issue #84 加固）。
 *
 * <p>Runner 在外部调用（upload/send）**之后**把结局应用到这里；每个方法都是一个短事务，绝不在
 * 外部调用期间持有事务或行锁。所有 finalize 都先锁住 async_tasks 行并复查「仍是当前
 * RUNNING/FINALIZING owner 且租约未过期」，再在**同一事务**内做
 * delivery 的 SENDING→终态/SENT CAS 与 state 更新，最后用
 * {@link AsyncTaskStore#succeedOwned} 原子收口任务。fence 丢失（LOST_LEASE）或 delivery CAS
 * 未命中时返回 false/ABORTED——**绝不改动 state、绝不关闭告警**，旧 Worker 静默放弃，让持有
 * 租约的新 owner 走 SENDING 恢复。
 *
 * <p>claim（INITIAL 的 beginAttempt / REMINDER 的 prepareReminder）现在**与 owner fence 同事务
 * 线性化**：先锁 async_tasks 行复查「仍当前 owner 且租约活跃 + 续租」，再在同一短事务 CAS
 * delivery PENDING→SENDING（REMINDER 同时持久化 recipient 证据 + 复查代际）。外部 upload/send
 * 始终在事务/锁之外。
 *
 * <p>REMINDER 的 finalize 绑定到 INITIAL 代际（delivery.initial_generation）：代际已变 → 只落
 * SUPERSEDED 证据（绝不改新时间线/reminder_count/next_reminder_at/告警）；INITIAL 成功 ack 的
 * 告警关闭也按成功 delivery 代际收窄（只关 <= 该代际的告警）。
 *
 * <p>INITIAL 成功 ack 采用**两阶段**本地收口（避免「任务已 SUCCEEDED 但遗留告警未关」的不可
 * 恢复空隙）：{@link #finalizeInitialSent} 只原子落地 delivery SENT + state 时间线并**故意**
 * 保持任务 RUNNING/owned；随后 {@link #completeInitialSent} 在同一短事务内先只关闭该导出告警
 * 再 {@code succeedOwned}。两者之间崩溃 → 任务仍 RUNNING（可被重新领取），重进见 delivery SENT
 * 走同一收口，绝不重新 upload/send。
 */
@Component
public class FulfillmentExportWecomDeliveryFinalizer {

    private final FulfillmentExportWecomStore store;
    private final AsyncTaskStore taskStore;
    private final OperationalAlertService alerts;
    private final Duration lease;

    public FulfillmentExportWecomDeliveryFinalizer(
            FulfillmentExportWecomStore store,
            AsyncTaskStore taskStore,
            OperationalAlertService alerts,
            @Value("${app.wecom-export-worker.lease-seconds:2400}") long leaseSeconds) {
        this.store = store;
        this.taskStore = taskStore;
        this.alerts = alerts;
        this.lease = Duration.ofSeconds(Math.max(FulfillmentExportWecomWorker.DEFAULT_LEASE_SECONDS, leaseSeconds));
    }

    /**
     * 租约时长（测试观察 seam：与 {@link FulfillmentExportWecomWorker} 使用同一下限，
     * 断言配置低于 40 分钟时被钳制到同一最小值）。
     */
    Duration lease() {
        return lease;
    }

    /**
     * 外部调用前的续租/所有权复查：仅当前 RUNNING owner 且租约未过期时原子延长租约。
     * 返回 false = 租约/所有权已丢失（被第二实例重新领取），调用方必须放弃 upload/send。
     */
    public boolean renewLease(AsyncTaskStore.AsyncTask task) {
        return taskStore.renewLease(task.id(), task.leaseOwner(), lease);
    }

    // ------------------------------------------------------------------
    // claim：owner fence + delivery PENDING→SENDING + recipient 同事务线性化
    // ------------------------------------------------------------------

    /**
     * INITIAL 领取（fenced 原子）：锁 async_tasks 行复查 owner/租约 + 续租，同一事务 CAS
     * delivery PENDING→SENDING。CAS 未命中时区分「终态幂等」与「他方 SENDING」：
     * {@link ClaimOutcome#TERMINAL} 由调用方重进终态分支；{@link ClaimOutcome#IN_FLIGHT}
     * 由调用方重进 SENDING 恢复（绝不因他方在途而误 succeed）。
     */
    @Transactional
    public ClaimOutcome claimInitial(
            AsyncTaskStore.AsyncTask task, long exportId, int sequence, int attempts) {
        ApplicationFence fence = taskStore.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == ApplicationDisposition.LOST_LEASE) {
            return ClaimOutcome.LOST_LEASE;
        }
        if (fence.disposition() == ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return ClaimOutcome.TASK_SUPERSEDED;
        }
        if (!taskStore.renewLease(task.id(), task.leaseOwner(), lease)) {
            return ClaimOutcome.LOST_LEASE;
        }
        if (store.beginAttempt(exportId, FulfillmentExportWecomStore.INITIAL, sequence, attempts, "RESOLVE_CHAT")) {
            return ClaimOutcome.CLAIMED;
        }
        FulfillmentExportWecomStore.Delivery delivery =
                store.delivery(exportId, FulfillmentExportWecomStore.INITIAL, sequence).orElse(null);
        if (delivery == null) {
            return ClaimOutcome.NOOP;
        }
        return switch (delivery.status()) {
            case "SENT", "FAILED", "UNKNOWN", "SUPERSEDED" -> ClaimOutcome.TERMINAL;
            case "SENDING" -> ClaimOutcome.IN_FLIGHT;
            default -> ClaimOutcome.NOOP; // PENDING 但 state 已停止/收齐
        };
    }

    /**
     * REMINDER 领取（fenced 原子）：锁 async_tasks 行复查 owner/租约 + 续租，同一事务执行
     * {@link FulfillmentExportWecomStore#prepareReminder}（锁 state 行复查 ACTIVE/代际/due/收齐
     * + CAS delivery PENDING→SENDING + 持久化 recipient）。
     */
    @Transactional
    public ClaimOutcome claimReminder(
            AsyncTaskStore.AsyncTask task, long exportId, int sequence, int attempts) {
        ApplicationFence fence = taskStore.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == ApplicationDisposition.LOST_LEASE) {
            return ClaimOutcome.LOST_LEASE;
        }
        if (fence.disposition() == ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return ClaimOutcome.TASK_SUPERSEDED;
        }
        if (!taskStore.renewLease(task.id(), task.leaseOwner(), lease)) {
            return ClaimOutcome.LOST_LEASE;
        }
        return switch (store.prepareReminder(exportId, sequence, attempts, "SEND")) {
            case CLAIMED -> ClaimOutcome.CLAIMED;
            case COMPLETED -> ClaimOutcome.COMPLETED;
            case SUPERSEDED, NOOP -> ClaimOutcome.NOOP;
        };
    }

    // ------------------------------------------------------------------
    // INITIAL finalize（两阶段）
    // ------------------------------------------------------------------

    /**
     * INITIAL 成功 ack 第一阶段（fenced 原子）：fence 通过 → delivery SENDING→SENT CAS →
     * state ACTIVE（仅当未停止/收齐且是 latest INITIAL）。**故意不** {@code succeedOwned}——
     * 任务保持 RUNNING/owned，等待 {@link #completeInitialSent} 在同一事务内先关闭该导出遗留
     * 告警再收口任务。返回 true 表示 delivery/state 已原子落地（调用方随后调用第二阶段收口）；
     * false = fence 丢失或 CAS 未命中（不得关告警、不得再动 state）。
     */
    @Transactional
    public boolean finalizeInitialSent(
            AsyncTaskStore.AsyncTask task,
            long exportId,
            int sequence,
            long deliveryId,
            String chatId,
            String requestId,
            Instant ackSentAt,
            String mediaIdSha256,
            Instant trackingDueAt,
            Instant nextReminderAt) {
        if (lostOrSuperseded(task)) {
            return false;
        }
        return store.markInitialSent(
                exportId, sequence, deliveryId, chatId, requestId, ackSentAt,
                mediaIdSha256, trackingDueAt, nextReminderAt);
    }

    /**
     * INITIAL 成功 ack 第二阶段（fenced 短事务、幂等）：在**同一事务**内先只关闭该导出
     * 遗留告警（shipment + detail.export_id + 代际 <= {@code generation} 精确隔离，绝不误关
     * 更新代际 resend 失败的红告警），再 {@code succeedOwned} 收口任务。告警关闭失败 →
     * 整事务回滚，任务保持 RUNNING/owned 可重试（重进见 delivery SENT 走同一收口，绝不重新
     * upload/send）。fence 丢失 → 静默放弃（新 owner 走同一收口）；SUPERSEDED → 旧任务已由
     * 其 delivery SENT 完成，直接收口。告警关闭抛出的异常向调用方传播（触发退避重试），这是
     * 「任务未 SUCCEEDED 且告警未关」可恢复性的关键。
     */
    @Transactional
    public void completeInitialSent(AsyncTaskStore.AsyncTask task, long exportId, int generation) {
        ApplicationFence fence = lockOutcomeFence(task);
        if (fence.disposition() == ApplicationDisposition.LOST_LEASE) {
            return;
        }
        if (fence.disposition() == ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return;
        }
        Long shipmentId = store.firstShipmentId(exportId);
        if (shipmentId != null) {
            alerts.resolveWecomExportAlerts(shipmentId, exportId, generation, "wecom_initial_ack");
        }
        taskStore.succeedOwned(task.id(), task.leaseOwner());
    }

    // ------------------------------------------------------------------
    // REMINDER finalize（代际栅栏）
    // ------------------------------------------------------------------

    /**
     * REMINDER 成功 ack finalize（fenced 原子 + 代际栅栏）：fence 通过 → delivery SENDING→SENT
     * CAS + state 时间线 → {@code succeedOwned}。代际已变 → delivery 只落 SUPERSEDED，绝不改
     * 新代际时间线。
     */
    @Transactional
    public ReminderFinalizeOutcome finalizeReminderSent(
            AsyncTaskStore.AsyncTask task,
            long exportId,
            long deliveryId,
            String requestId,
            Instant ackSentAt,
            Instant nextReminderAt) {
        if (lostOrSuperseded(task)) {
            return ReminderFinalizeOutcome.ABORTED;
        }
        FulfillmentExportWecomStore.ReminderFinalize result =
                store.markReminderSent(exportId, deliveryId, requestId, ackSentAt, nextReminderAt);
        if (result != FulfillmentExportWecomStore.ReminderFinalize.ABORTED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
        }
        return ReminderFinalizeOutcome.valueOf(result.name());
    }

    /**
     * 可安全重试失败的 fenced 收口：在同一事务内复查 fence + 重读 delivery 的 attempts，
     * 决定退回 PENDING（退避重试）还是终态 FAILED/SUPERSEDED。REMINDER 的终态 FAILED 走
     * 代际栅栏（代际已变 → SUPERSEDED，不告警）。CAS 未命中或 fence 丢失 → ABORTED
     * （不改业务状态）。
     */
    @Transactional
    public FailureFinalize finalizeRetryableFailure(
            AsyncTaskStore.AsyncTask task,
            long exportId,
            String kind,
            int sequence,
            String errorCode,
            String errorMessage,
            Duration backoff) {
        if (lostOrSuperseded(task)) {
            return FailureFinalize.ABORTED;
        }
        FulfillmentExportWecomStore.Delivery current =
                store.delivery(exportId, kind, sequence).orElse(null);
        if (current == null) {
            return FailureFinalize.ABORTED;
        }
        if (current.attempts() >= current.maxAttempts()) {
            if (FulfillmentExportWecomStore.REMINDER.equals(kind)) {
                FulfillmentExportWecomStore.ReminderFinalize result =
                        store.markReminderFailed(exportId, current.id(), errorCode, errorMessage);
                if (result == FulfillmentExportWecomStore.ReminderFinalize.SUPERSEDED) {
                    taskStore.succeedOwned(task.id(), task.leaseOwner());
                    return FailureFinalize.SUPERSEDED;
                }
                return result == FulfillmentExportWecomStore.ReminderFinalize.APPLIED
                        ? FailureFinalize.TERMINAL
                        : FailureFinalize.ABORTED;
            }
            if (!store.markFailed(exportId, kind, sequence, errorCode, errorMessage)) {
                return FailureFinalize.ABORTED;
            }
            return FailureFinalize.TERMINAL;
        }
        if (!store.retryPending(exportId, kind, sequence, errorCode, errorMessage)) {
            return FailureFinalize.ABORTED;
        }
        taskStore.fail(task.id(), task.leaseOwner(), errorCode, backoff);
        return FailureFinalize.RETRY_SCHEDULED;
    }

    /** 确定性终态失败（fenced）：INITIAL → FAILED + state FAILED；REMINDER → 代际栅栏 finalize。 */
    @Transactional
    public FinalizeOutcome markFailed(
            AsyncTaskStore.AsyncTask task,
            long exportId,
            String kind,
            int sequence,
            String errorCode,
            String errorMessage) {
        return finalizeFailedOrUnknown(task, exportId, kind, sequence, errorCode, errorMessage, false);
    }

    /** 结局未知（fenced）：INITIAL → UNKNOWN + state UNKNOWN；REMINDER → 代际栅栏 finalize。 */
    @Transactional
    public FinalizeOutcome markUnknown(
            AsyncTaskStore.AsyncTask task,
            long exportId,
            String kind,
            int sequence,
            String errorCode,
            String errorMessage) {
        return finalizeFailedOrUnknown(task, exportId, kind, sequence, errorCode, errorMessage, true);
    }

    private FinalizeOutcome finalizeFailedOrUnknown(
            AsyncTaskStore.AsyncTask task,
            long exportId,
            String kind,
            int sequence,
            String errorCode,
            String errorMessage,
            boolean unknown) {
        if (lostOrSuperseded(task)) {
            return FinalizeOutcome.ABORTED;
        }
        if (FulfillmentExportWecomStore.REMINDER.equals(kind)) {
            FulfillmentExportWecomStore.Delivery delivery =
                    store.delivery(exportId, FulfillmentExportWecomStore.REMINDER, sequence).orElse(null);
            if (delivery == null) {
                return FinalizeOutcome.ABORTED;
            }
            FulfillmentExportWecomStore.ReminderFinalize result = unknown
                    ? store.markReminderUnknown(exportId, delivery.id(), errorCode, errorMessage)
                    : store.markReminderFailed(exportId, delivery.id(), errorCode, errorMessage);
            if (result == FulfillmentExportWecomStore.ReminderFinalize.SUPERSEDED) {
                taskStore.succeedOwned(task.id(), task.leaseOwner());
                return FinalizeOutcome.SUPERSEDED;
            }
            return result == FulfillmentExportWecomStore.ReminderFinalize.APPLIED
                    ? FinalizeOutcome.APPLIED
                    : FinalizeOutcome.ABORTED;
        }
        boolean applied = unknown
                ? store.markUnknown(exportId, kind, sequence, errorCode, errorMessage)
                : store.markFailed(exportId, kind, sequence, errorCode, errorMessage);
        return applied ? FinalizeOutcome.APPLIED : FinalizeOutcome.ABORTED;
    }

    /**
     * 复查 fence：LOST_LEASE → 旧 Worker 不得再动业务状态；SUPERSEDED → 只把旧任务收口为
     * SUCCEEDED（不再应用本代结果），两者都返回 true（调用方放弃）。CURRENT → 返回 false 继续。
     */
    private boolean lostOrSuperseded(AsyncTaskStore.AsyncTask task) {
        ApplicationFence fence = lockOutcomeFence(task);
        if (fence.disposition() == ApplicationDisposition.LOST_LEASE) {
            return true;
        }
        if (fence.disposition() == ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return true;
        }
        return false;
    }

    /**
     * 业务结局收口允许 RUNNING 或 FINALIZING owner。任务在外部调用后崩溃并耗尽领取预算时，
     * 下一次会以 FINALIZING 重新领取；该 owner 仍必须能够把遗留 SENDING 单调收口为 UNKNOWN
     * 并创建告警。先尝试普通应用 fence，只有它报告 LOST_LEASE 时才尝试 finalization fence，
     * 避免依赖调用方手里可能已经过时的 task.status 快照。
     */
    private ApplicationFence lockOutcomeFence(AsyncTaskStore.AsyncTask task) {
        ApplicationFence fence = taskStore.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() != ApplicationDisposition.LOST_LEASE) {
            return fence;
        }
        return taskStore.lockFinalizationFence(task.id(), task.leaseOwner());
    }

    /** claim（owner fence + PENDING→SENDING）的线性化结果。 */
    public enum ClaimOutcome {
        /** fence 丢失（旧 owner 被新 owner 取代）：静默放弃，绝不改业务状态。 */
        LOST_LEASE,
        /** 已有更新代际任务：旧任务已 {@code succeedOwned} 收口，放弃。 */
        TASK_SUPERSEDED,
        /** 已 CAS 到 SENDING（REMINDER 含 recipient + 代际复查）：调用方可以外部发送。 */
        CLAIMED,
        /** delivery 已终态：调用方重进终态幂等分支。 */
        TERMINAL,
        /** delivery 已被他方 SENDING：调用方重进 SENDING 恢复，绝不误 succeed。 */
        IN_FLIGHT,
        /** import 先提交，state 已标 COMPLETED：调用方 succeed（no-op）。 */
        COMPLETED,
        /** 停止/收齐/代际已变（reminder 已标 SUPERSEDED）：调用方 succeed（no-op）。 */
        NOOP
    }

    /** FAILED/UNKNOWN finalize 的结果。 */
    public enum FinalizeOutcome {
        /** delivery 终态 + state 已更新：调用方随后幂等告警收口。 */
        APPLIED,
        /** reminder 代际已变：delivery 只落 SUPERSEDED，任务已收口，绝不告警。 */
        SUPERSEDED,
        /** fence 丢失或 delivery CAS 未命中：不改任何业务状态。 */
        ABORTED
    }

    /** reminder 成功 finalize 的结果（映射 {@link FulfillmentExportWecomStore.ReminderFinalize}）。 */
    public enum ReminderFinalizeOutcome {
        APPLIED,
        SUPERSEDED,
        ABORTED
    }

    /** 可重试失败的 fenced 收口结果。 */
    public enum FailureFinalize {
        /** 已退回 PENDING 并退避重排任务。 */
        RETRY_SCHEDULED,
        /** 已达上限：delivery FAILED + state 已更新，调用方随后幂等告警收口。 */
        TERMINAL,
        /** reminder 代际已变：delivery 只落 SUPERSEDED，任务已收口，绝不告警。 */
        SUPERSEDED,
        /** fence 丢失或 delivery CAS 未命中：不改任何业务状态。 */
        ABORTED
    }
}
