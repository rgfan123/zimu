package cn.zimu.fulfillment.file;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 履约导出企微发送状态与 delivery 证据的唯一读写方（Issue #84）。
 *
 * <p>只操作 {@code app.fulfillment_export_wecom_states} / {@code app.fulfillment_export_wecom_deliveries}
 * 两张表；不做外部调用、不拼消息、不写告警。短事务语义：每个方法自成一个事务（或加入调用方
 * 事务），保证「外部调用绝不在持有长事务/行锁期间发生」——Runner 在外部 upload/send 前后分别
 * 调用本类方法完成 CAS 与 finalize。
 *
 * <p>并发保证：{@code UNIQUE (export_id, kind, sequence)} 使同一 initial 或同一 reminder
 * sequence 不可能被并发创建/发送两次；状态转移全部是带条件的 UPDATE（CAS）。
 */
@Repository
public class FulfillmentExportWecomStore {

    public static final String INITIAL = "INITIAL";
    public static final String REMINDER = "REMINDER";

    /** 可执行 delivery 的导出状态（其余状态一律 no-op，防止停止/收齐后被唤醒发送）。 */
    static final String SENDABLE_STATES = "'PENDING','ACTIVE','UNKNOWN','FAILED'";

    private final JdbcTemplate jdbc;

    /**
     * 测试确定性 seam（package-private，默认 no-op）：state 行被 {@code FOR UPDATE} 锁定
     * 后回调一次（{@link #prepareReminder} 与 {@link #markTrackingReceived} 内），用于并发
     * 测试在两个事务之间制造确定性的锁等待（latch/两事务）；生产路径恒为 no-op。
     */
    private volatile Runnable afterStateLock = () -> {};

    void setAfterStateLock(Runnable hook) {
        this.afterStateLock = hook == null ? () -> {} : hook;
    }

    public FulfillmentExportWecomStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 状态行
    // ------------------------------------------------------------------

    /** 生成事务内创建状态行（PENDING）；已存在（幂等重放/续发）则不动。 */
    @Transactional
    public void createState(long exportId, long providerId, int slaMinutes, int reminderIntervalMinutes) {
        jdbc.update(
                """
                INSERT INTO app.fulfillment_export_wecom_states
                    (export_id, provider_id, status, tracking_sla_minutes, reminder_interval_minutes)
                VALUES (?, ?, 'PENDING', ?, ?)
                ON CONFLICT (export_id) DO NOTHING
                """,
                exportId,
                providerId,
                slaMinutes,
                reminderIntervalMinutes);
    }

    @Transactional(readOnly = true)
    public Optional<ExportState> state(long exportId) {
        return jdbc.query(
                """
                SELECT export_id, provider_id, status, chat_id, tracking_sla_minutes,
                       reminder_interval_minutes, initial_sent_at, tracking_due_at,
                       next_reminder_at, last_reminded_at, reminder_count, last_error,
                       stopped_by, stopped_reason, stopped_at, lock_version
                FROM app.fulfillment_export_wecom_states WHERE export_id=?
                """,
                (rs, row) -> mapState(rs),
                exportId).stream().findFirst();
    }

    /** 收齐回传后标记 COMPLETED（从 PENDING/ACTIVE/FAILED/UNKNOWN 进入；人工停止与历史行不动）。 */
    @Transactional
    public void markCompleted(long exportId) {
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET status='COMPLETED', next_reminder_at=NULL,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status IN ('PENDING','ACTIVE','FAILED','UNKNOWN')
                """,
                exportId);
    }

    /**
     * tracking 导入事务内的收齐判定（线性化点）：先锁 state 行 {@code FOR UPDATE}，再在同一
     * 事务重算全量 tracking、标 COMPLETED。与 {@link #prepareReminder} 互斥：谁先拿到 state
     * 行锁谁先线性化——reminder prepare 先提交则发送决策发生在收齐之前（合法）；
     * 本方法先提交则 reminder prepare 复查必见 COMPLETED，绝不发送。
     */
    @Transactional
    public void markTrackingReceived(long exportId) {
        List<Long> locked = jdbc.query(
                "SELECT export_id FROM app.fulfillment_export_wecom_states WHERE export_id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1),
                exportId);
        if (locked.isEmpty()) {
            return; // 未登记企微发送的导出（JD/历史无状态行）不参与
        }
        afterStateLock.run();
        if (isTrackingComplete(exportId)) {
            markCompleted(exportId);
        }
    }

    /**
     * 提醒发送前的线性化准备（单短事务）：锁 state 行 {@code FOR UPDATE}，在同一事务复查
     * ACTIVE/代际/due/收齐全量，再 CAS delivery PENDING→SENDING 并持久化 recipient 证据。
     *
     * <p>与 {@link #markTrackingReceived}（import 事务内锁同一行）互斥：若本方法先提交，
     * 发送决策线性化在收齐之前（允许发送，外部 send 仍在事务外）；若 import 先提交，本方法
     * 复查必见 COMPLETED（或已收齐）而返回 no-op，绝不发送。
     *
     * <p><b>代际栅栏</b>：提醒 delivery 的 {@code initial_generation} 必须仍等于当前最新
     * INITIAL sequence；否则说明期间有人工重发的新 INITIAL 已登记/成功，本提醒已过期——同事务
     * 标 {@code SUPERSEDED}（绝不再发送、绝不阻塞 scanner 生成新代际提醒）。返回：
     * <ul>
     *   <li>{@link ReminderPrepare#CLAIMED}：已 CAS 到 SENDING（含 recipient 证据），调用方可以外部发送；</li>
     *   <li>{@link ReminderPrepare#COMPLETED}：同事务已复查收齐并标 COMPLETED，调用方 no-op；</li>
     *   <li>{@link ReminderPrepare#SUPERSEDED}：代际已变，delivery 已标 SUPERSEDED，调用方 no-op；</li>
     *   <li>{@link ReminderPrepare#NOOP}：已停止/暂停/时间线已变/未领取到，调用方 no-op。</li>
     * </ul>
     */
    @Transactional
    public ReminderPrepare prepareReminder(long exportId, int sequence, int attempts, String stage) {
        List<StateLockRow> rows = jdbc.query(
                """
                SELECT status, chat_id FROM app.fulfillment_export_wecom_states
                WHERE export_id=? FOR UPDATE
                """,
                (rs, row) -> new StateLockRow(rs.getString(1), rs.getString(2)),
                exportId);
        if (rows.isEmpty() || !"ACTIVE".equals(rows.getFirst().status())) {
            return ReminderPrepare.NOOP;
        }
        String chatId = rows.getFirst().chatId();
        afterStateLock.run();
        // 代际栅栏（先于 due/收齐判定）：期间有人工重发新 INITIAL → 本提醒过期，标 SUPERSEDED，
        // 绝不在新代际时间线上发送或阻塞新提醒。
        List<Long> staleIds = jdbc.query(
                """
                SELECT id FROM app.fulfillment_export_wecom_deliveries
                WHERE export_id=? AND kind='REMINDER' AND sequence=? AND initial_generation <> ?
                """,
                (rs, row) -> rs.getLong(1),
                exportId,
                sequence,
                latestInitialGeneration(exportId));
        if (!staleIds.isEmpty()) {
            markReminderSuperseded(staleIds.getFirst());
            return ReminderPrepare.SUPERSEDED;
        }
        Boolean stillDue = jdbc.queryForObject(
                """
                SELECT next_reminder_at IS NOT NULL AND next_reminder_at <= CURRENT_TIMESTAMP
                FROM app.fulfillment_export_wecom_states WHERE export_id=?
                """,
                Boolean.class,
                exportId);
        if (!Boolean.TRUE.equals(stillDue)) {
            return ReminderPrepare.NOOP;
        }
        if (isTrackingComplete(exportId)) {
            markCompleted(exportId); // 收齐事实优先：同事务标 COMPLETED，绝不催已收齐
            return ReminderPrepare.COMPLETED;
        }
        // CAS PENDING→SENDING 并持久化 recipient 证据（快照群；不改 state.chat_id）。
        int updated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='SENDING', attempts=?, stage=?, chat_id=?, error_code=NULL,
                    error_message=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND kind='REMINDER' AND sequence=? AND status='PENDING'
                """,
                attempts,
                stage,
                chatId,
                exportId,
                sequence);
        return updated == 1 ? ReminderPrepare.CLAIMED : ReminderPrepare.NOOP;
    }

    /** 人工停止：版本 CAS + 状态守卫；返回受影响行数（0 = 版本冲突或已终态）。 */
    @Transactional
    public int stop(long exportId, long expectedVersion, String operator, String reason) {
        return jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET status='MANUALLY_STOPPED', stopped_by=?, stopped_reason=?, stopped_at=CURRENT_TIMESTAMP,
                    next_reminder_at=NULL, last_error=NULL,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND lock_version=? AND status IN ('PENDING','ACTIVE','FAILED','UNKNOWN')
                """,
                operator,
                reason,
                exportId,
                expectedVersion);
    }

    /** 人工重发：登记新的 in-flight initial（版本 CAS），清提醒时间线；返回受影响行数。 */
    @Transactional
    public int beginResend(long exportId, long expectedVersion) {
        return jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET status='PENDING', next_reminder_at=NULL,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND lock_version=?
                """,
                exportId,
                expectedVersion);
    }

    /**
     * initial 成功 ack finalize：仅当该 delivery 仍是 latest INITIAL 且状态未被人工停止/收齐。
     * delivery SENDING→SENT CAS 未命中（返回 false）时**绝不触碰 state**（可能是他方已抢先
     * finalize/转 UNKNOWN），上层据此回滚任务收口且不关闭告警。
     */
    @Transactional
    public boolean markInitialSent(
            long exportId,
            int sequence,
            long deliveryId,
            String chatId,
            String requestId,
            Instant ackSentAt,
            String mediaIdSha256,
            Instant trackingDueAt,
            Instant nextReminderAt) {
        int deliveryUpdated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='SENT', stage='FINALIZED', chat_id=?, request_id=?, ack_sent_at=?,
                    media_id_sha256=?, error_code=NULL, error_message=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING'
                """,
                chatId,
                requestId,
                ts(ackSentAt),
                mediaIdSha256,
                deliveryId);
        if (deliveryUpdated != 1) {
            return false;
        }
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET status='ACTIVE', chat_id=?, initial_sent_at=?, tracking_due_at=?, next_reminder_at=?,
                    last_error=NULL, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status IN ('PENDING','ACTIVE','UNKNOWN','FAILED')
                  AND NOT EXISTS (
                      SELECT 1 FROM app.fulfillment_export_wecom_deliveries newer
                      WHERE newer.export_id=? AND newer.kind='INITIAL' AND newer.sequence > ?
                        AND newer.id <> ?)
                """,
                chatId,
                ts(ackSentAt),
                ts(trackingDueAt),
                ts(nextReminderAt),
                exportId,
                exportId,
                sequence,
                deliveryId);
        return true;
    }

    /**
     * reminder 成功 ack finalize（代际栅栏）：锁 delivery 行 + state 行，复查该 reminder 的
     * {@code initial_generation} 是否仍等于当前最新 INITIAL 代际。是 → delivery SENT + state
     * last_reminded_at/count/next_reminder；否（期间有人工重发新 INITIAL）→ delivery 只落
     * {@code SUPERSEDED} 证据，绝不改新代际时间线/reminder_count/next_reminder_at。delivery
     * 已非 SENDING（被他人 finalize）→ {@link ReminderFinalize#ABORTED}。
     */
    @Transactional
    public ReminderFinalize markReminderSent(
            long exportId, long deliveryId, String requestId, Instant ackSentAt, Instant nextReminderAt) {
        LockedReminder reminder = lockReminder(deliveryId);
        if (reminder == null) {
            return ReminderFinalize.ABORTED;
        }
        if (reminder.initialGeneration() != latestInitialGeneration(reminder.exportId())) {
            markReminderSuperseded(deliveryId);
            return ReminderFinalize.SUPERSEDED;
        }
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='SENT', stage='FINALIZED', request_id=?, ack_sent_at=?,
                    error_code=NULL, error_message=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING'
                """,
                requestId,
                ts(ackSentAt),
                deliveryId);
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET last_reminded_at=?, reminder_count=reminder_count+1, next_reminder_at=?,
                    last_error=NULL, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status='ACTIVE'
                """,
                ts(ackSentAt),
                ts(nextReminderAt),
                exportId);
        return ReminderFinalize.APPLIED;
    }

    /**
     * reminder 确定性终态失败 finalize（代际栅栏）：锁 delivery + state 行，代际未变 → delivery
     * FAILED + 暂停提醒（next_reminder_at=NULL）；代际已变 → delivery 只落 SUPERSEDED，绝不改
     * 新代际时间线/不暂停新提醒。delivery 已非 SENDING → {@link ReminderFinalize#ABORTED}。
     */
    @Transactional
    public ReminderFinalize markReminderFailed(long exportId, long deliveryId, String errorCode, String errorMessage) {
        LockedReminder reminder = lockReminder(deliveryId);
        if (reminder == null) {
            return ReminderFinalize.ABORTED;
        }
        if (reminder.initialGeneration() != latestInitialGeneration(reminder.exportId())) {
            markReminderSuperseded(deliveryId);
            return ReminderFinalize.SUPERSEDED;
        }
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='FAILED', error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING'
                """,
                errorCode,
                errorMessage,
                deliveryId);
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET next_reminder_at=NULL, last_error=?,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status='ACTIVE'
                """,
                errorMessage,
                exportId);
        return ReminderFinalize.APPLIED;
    }

    /**
     * reminder 结局未知 finalize（代际栅栏）：锁 delivery + state 行，代际未变 → delivery UNKNOWN
     * + 暂停提醒；代际已变 → delivery 只落 SUPERSEDED，绝不改新代际时间线/不暂停新提醒。
     * delivery 已非 SENDING → {@link ReminderFinalize#ABORTED}。
     */
    @Transactional
    public ReminderFinalize markReminderUnknown(long exportId, long deliveryId, String errorCode, String errorMessage) {
        LockedReminder reminder = lockReminder(deliveryId);
        if (reminder == null) {
            return ReminderFinalize.ABORTED;
        }
        if (reminder.initialGeneration() != latestInitialGeneration(reminder.exportId())) {
            markReminderSuperseded(deliveryId);
            return ReminderFinalize.SUPERSEDED;
        }
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='UNKNOWN', error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='SENDING'
                """,
                errorCode,
                errorMessage,
                deliveryId);
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET next_reminder_at=NULL, last_error=?,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status='ACTIVE'
                """,
                errorMessage,
                exportId);
        return ReminderFinalize.APPLIED;
    }

    /** 锁定 reminder delivery 行（FOR UPDATE）并复查其代际是否仍等于当前最新 INITIAL 代际。 */
    private LockedReminder lockReminder(long deliveryId) {
        List<LockedReminder> rows = jdbc.query(
                """
                SELECT id, export_id, status, initial_generation
                FROM app.fulfillment_export_wecom_deliveries
                WHERE id=? FOR UPDATE
                """,
                (rs, row) -> new LockedReminder(
                        rs.getLong("id"), rs.getLong("export_id"), rs.getString("status"),
                        rs.getInt("initial_generation")),
                deliveryId);
        if (rows.isEmpty() || !"SENDING".equals(rows.getFirst().status())) {
            return null;
        }
        // 锁 state 行：与 resend（lockState + beginResend）串行化，代际读到的 MAX(INITIAL.sequence)
        // 是稳定快照（无并发新 INITIAL 正在创建）。
        lockStateRow(rows.getFirst().exportId());
        return rows.getFirst();
    }

    /** 提醒 delivery 已被更新 INITIAL 代际取代：同事务落 SUPERSEDED 证据，不改 state/不告警。 */
    private void markReminderSuperseded(long deliveryId) {
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='SUPERSEDED', error_code='WECOM_REMINDER_SUPERSEDED',
                    error_message='提醒已被更新的 initial 重发取代', updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                deliveryId);
    }

    /** 锁 state 行（reminder finalize 与 resend/tracking import 的串行化点）。 */
    private void lockStateRow(long exportId) {
        jdbc.query(
                "SELECT export_id FROM app.fulfillment_export_wecom_states WHERE export_id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1),
                exportId);
    }

    private record LockedReminder(long id, long exportId, String status, int initialGeneration) {}

    /** reminder finalize 的代际栅栏结果。 */
    public enum ReminderFinalize {
        /** 代际未变：delivery 已转 SENT/FAILED/UNKNOWN + state 已更新，调用方按结局告警/收口。 */
        APPLIED,
        /** 代际已变：delivery 只落 SUPERSEDED，绝不改 state/告警。 */
        SUPERSEDED,
        /** delivery 已非 SENDING（被他人抢先 finalize）：不改任何业务状态。 */
        ABORTED
    }

    /**
     * 可安全重试的失败：delivery 回到 PENDING 等下次尝试（记录稳定错误）；未达上限时的调用方
     * 负责以退避重排 async task。返回 true 表示 delivery SENDING→PENDING CAS 命中（当前 SENDING）。
     */
    @Transactional
    public boolean retryPending(long exportId, String kind, int sequence, String errorCode, String errorMessage) {
        int updated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='PENDING', error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND kind=? AND sequence=? AND status='SENDING'
                """,
                errorCode,
                errorMessage,
                exportId,
                kind,
                sequence);
        return updated == 1;
    }

    /**
     * INITIAL 确定性终态失败：delivery FAILED + 导出置 FAILED。delivery SENDING→FAILED CAS
     * 未命中（返回 false）时绝不触碰 state。REMINDER 的代际栅栏 finalize 见
     * {@link #markReminderFailed}。
     */
    @Transactional
    public boolean markFailed(long exportId, String kind, int sequence, String errorCode, String errorMessage) {
        int deliveryUpdated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='FAILED', error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND kind=? AND sequence=? AND status='SENDING'
                """,
                errorCode,
                errorMessage,
                exportId,
                kind,
                sequence);
        if (deliveryUpdated != 1) {
            return false;
        }
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET status='FAILED', next_reminder_at=NULL, last_error=?,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status IN ('PENDING','ACTIVE','UNKNOWN','FAILED')
                """,
                errorMessage,
                exportId);
        return true;
    }

    /**
     * INITIAL 结局未知（发送已提交但未获 ack/证据矛盾）：delivery UNKNOWN + 导出置 UNKNOWN。
     * delivery SENDING→UNKNOWN CAS 未命中（返回 false）时绝不触碰 state。REMINDER 的代际栅栏
     * finalize 见 {@link #markReminderUnknown}。
     */
    @Transactional
    public boolean markUnknown(long exportId, String kind, int sequence, String errorCode, String errorMessage) {
        int deliveryUpdated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET status='UNKNOWN', error_code=?, error_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND kind=? AND sequence=? AND status='SENDING'
                """,
                errorCode,
                errorMessage,
                exportId,
                kind,
                sequence);
        if (deliveryUpdated != 1) {
            return false;
        }
        jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_states
                SET status='UNKNOWN', next_reminder_at=NULL, last_error=?,
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND status IN ('PENDING','ACTIVE','FAILED')
                """,
                errorMessage,
                exportId);
        return true;
    }

    // ------------------------------------------------------------------
    // delivery 行
    // ------------------------------------------------------------------

    /**
     * 原子创建 delivery（UNIQUE 冲突 = 已存在，返回 null）：同一 sequence 不可能重复入队。
     * {@code initialGeneration} = 该 delivery 绑定到的 INITIAL 代际（INITIAL 行为自身 sequence；
     * REMINDER 行为创建时最新的 INITIAL sequence，见 {@link #latestInitialGeneration}）。
     */
    @Transactional
    public Optional<Long> createDelivery(long exportId, String kind, int sequence, int initialGeneration) {
        List<Long> ids = jdbc.query(
                """
                INSERT INTO app.fulfillment_export_wecom_deliveries
                    (export_id, kind, sequence, initial_generation)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (export_id, kind, sequence) DO NOTHING
                RETURNING id
                """,
                (rs, row) -> rs.getLong(1),
                exportId,
                kind,
                sequence,
                initialGeneration);
        return ids.stream().findFirst();
    }

    /** 该导出当前最新的 INITIAL 代际（= MAX(INITIAL.sequence)，无 INITIAL 时 0）。 */
    @Transactional(readOnly = true)
    public int latestInitialGeneration(long exportId) {
        Integer latest = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM app.fulfillment_export_wecom_deliveries "
                        + "WHERE export_id=? AND kind='INITIAL'",
                Integer.class,
                exportId);
        return latest == null ? 0 : latest;
    }

    @Transactional(readOnly = true)
    public Optional<Delivery> delivery(long exportId, String kind, int sequence) {
        return jdbc.query(
                """
                SELECT id, export_id, kind, sequence, initial_generation, status, attempts,
                       max_attempts, stage, chat_id, request_id, ack_sent_at, media_id_sha256,
                       error_code, error_message
                FROM app.fulfillment_export_wecom_deliveries
                WHERE export_id=? AND kind=? AND sequence=?
                """,
                (rs, row) -> mapDelivery(rs),
                exportId,
                kind,
                sequence).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Delivery> deliveries(long exportId) {
        return jdbc.query(
                """
                SELECT id, export_id, kind, sequence, initial_generation, status, attempts,
                       max_attempts, stage, chat_id, request_id, ack_sent_at, media_id_sha256,
                       error_code, error_message
                FROM app.fulfillment_export_wecom_deliveries
                WHERE export_id=? ORDER BY kind, sequence
                """,
                (rs, row) -> mapDelivery(rs),
                exportId);
    }

    @Transactional(readOnly = true)
    public int nextSequence(long exportId, String kind) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence),0)+1 FROM app.fulfillment_export_wecom_deliveries "
                        + "WHERE export_id=? AND kind=?",
                Integer.class,
                exportId,
                kind);
        return next == null ? 1 : next;
    }

    /**
     * 上传前把本次实际路由群写入 delivery 证据并推进阶段到 UPLOAD（CAS：仅当 delivery 仍是
     * SENDING）。不改 state.chat_id（快照只在 initial 成功 ack 时建立）：UNKNOWN/FAILED 的
     * delivery 因此保留 recipient 证据，运营可据此回答「可能发到了哪个群」。返回 true 表示
     * 恰好更新了一条当前 SENDING delivery；false 表示 CAS 未命中（delivery 已被他方转移），
     * 调用方必须放弃后续 upload/send。
     */
    @Transactional
    public boolean markChatResolved(long exportId, String kind, int sequence, String chatId) {
        int updated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries
                SET chat_id=?, stage='UPLOAD', updated_at=CURRENT_TIMESTAMP
                WHERE export_id=? AND kind=? AND sequence=? AND status='SENDING'
                """,
                chatId,
                exportId,
                kind,
                sequence);
        return updated == 1;
    }

    /**
     * 领取尝试（INITIAL 专用）：CAS delivery PENDING→SENDING（attempts 镜像 async task 的
     * claim 计数），且导出状态仍在可发送集合内。返回 false 表示 no-op（停止/收齐后不应发送）。
     * REMINDER 的发送前复查（含 recipient 持久化与代际栅栏）由 {@link #prepareReminder} 在单
     * 事务线性化完成。
     */
    @Transactional
    public boolean beginAttempt(long exportId, String kind, int sequence, int attempts, String stage) {
        int updated = jdbc.update(
                """
                UPDATE app.fulfillment_export_wecom_deliveries d
                SET status='SENDING', attempts=?, stage=?, error_code=NULL, error_message=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE d.export_id=? AND d.kind=? AND d.sequence=? AND d.status='PENDING'
                  AND EXISTS (
                      SELECT 1 FROM app.fulfillment_export_wecom_states s
                      WHERE s.export_id=d.export_id AND s.status IN ("""
                        + SENDABLE_STATES
                        + """
                  )
                )
                """,
                attempts,
                stage,
                exportId,
                kind,
                sequence);
        return updated == 1;
    }

    // ------------------------------------------------------------------
    // 完成判定与扫描
    // ------------------------------------------------------------------

    /**
     * 收齐判定：该导出的全部 export_items 的 shipment 都必须已有「已收齐」的 tracking
     * （tracking 行 + 其 PROVIDER_TRACKING 批次 COMPLETED + 运单号非空）；任一缺失即未完成。
     */
    @Transactional(readOnly = true)
    public boolean isTrackingComplete(long exportId) {
        Boolean complete = jdbc.queryForObject(
                """
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM app.fulfillment_export_items fei
                    WHERE fei.fulfillment_export_id=?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM app.trackings t
                          JOIN app.import_batches ib ON ib.id=t.provider_tracking_batch_id
                              AND ib.status='COMPLETED'
                          WHERE t.shipment_id=fei.shipment_id
                            AND t.tracking_number IS NOT NULL AND btrim(t.tracking_number) <> ''
                      )
                )
                """,
                Boolean.class,
                exportId);
        return Boolean.TRUE.equals(complete);
    }

    /**
     * 到期提醒候选：ACTIVE + next_reminder_at<=now，且**没有进行中的 reminder delivery**
     * （PENDING/SENDING）——已创建但未 ack 的提醒不会因重复轮询/多实例扫描生成下一个 sequence；
     * SKIP LOCKED 防多实例重复领取同一批。
     */
    @Transactional
    public List<Long> dueReminderCandidates(int limit) {
        return jdbc.query(
                """
                SELECT s.export_id
                FROM app.fulfillment_export_wecom_states s
                WHERE s.status='ACTIVE' AND s.next_reminder_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                      SELECT 1 FROM app.fulfillment_export_wecom_deliveries d
                      WHERE d.export_id=s.export_id AND d.kind='REMINDER'
                        AND d.status IN ('PENDING','SENDING')
                  )
                ORDER BY s.next_reminder_at, s.export_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> rs.getLong(1),
                limit);
    }

    /** 该导出未回传的 shipment 数（提醒文案用）。 */
    @Transactional(readOnly = true)
    public int missingTrackingShipmentCount(long exportId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM (
                    SELECT DISTINCT fei.shipment_id
                    FROM app.fulfillment_export_items fei
                    WHERE fei.fulfillment_export_id=?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM app.trackings t
                          JOIN app.import_batches ib ON ib.id=t.provider_tracking_batch_id
                              AND ib.status='COMPLETED'
                          WHERE t.shipment_id=fei.shipment_id
                            AND t.tracking_number IS NOT NULL AND btrim(t.tracking_number) <> ''
                      )
                ) missing
                """,
                Integer.class,
                exportId);
        return count == null ? 0 : count;
    }

    /** 告警 subject：该导出第一个 fulfillment（真实业务主体）。 */
    @Transactional(readOnly = true)
    public Long firstFulfillmentId(long exportId) {
        return jdbc.queryForObject(
                "SELECT MIN(fulfillment_id) FROM app.fulfillment_export_items WHERE fulfillment_export_id=?",
                Long.class,
                exportId);
    }

    /**
     * 告警 subject：该导出第一个真实 shipment（业务主体；续发导出可与原导出共享 fulfillment，
     * 但告警去重/关闭必须按 export_id 隔离，不能跨导出误关）。
     */
    @Transactional(readOnly = true)
    public Long firstShipmentId(long exportId) {
        return jdbc.queryForObject(
                "SELECT MIN(shipment_id) FROM app.fulfillment_export_items WHERE fulfillment_export_id=?",
                Long.class,
                exportId);
    }

    /** 是否存在进行中的 INITIAL delivery（PENDING/SENDING）：进行中禁止人工重发（防并发双发）。 */
    @Transactional(readOnly = true)
    public boolean hasInFlightInitial(long exportId) {
        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.fulfillment_export_wecom_deliveries
                    WHERE export_id=? AND kind='INITIAL' AND status IN ('PENDING','SENDING'))
                """,
                Boolean.class,
                exportId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 锁 state 行（人工重发/停止等写命令的串行化点）：并发写命令按导出串行执行，
     * 后续的 in-flight 复查/CAS 读取到前一个命令的已提交结果，行为确定。
     */
    @Transactional
    public void lockState(long exportId) {
        jdbc.query(
                "SELECT export_id FROM app.fulfillment_export_wecom_states WHERE export_id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1),
                exportId);
    }

    /** 发送侧事实：批次号/文件引用/履约方编码与名称（worker 上传与告警 detail 用）。 */
    @Transactional(readOnly = true)
    public ExportFacts exportFacts(long exportId) {
        List<ExportFacts> rows = jdbc.query(
                """
                SELECT fe.export_batch_no, fe.file_ref, fe.fulfillment_provider_id,
                       fp.provider_code, fp.provider_name
                FROM app.fulfillment_exports fe
                JOIN app.fulfillment_providers fp ON fp.id=fe.fulfillment_provider_id
                WHERE fe.id=?
                """,
                (rs, row) -> new ExportFacts(
                        rs.getString("export_batch_no"),
                        rs.getString("file_ref"),
                        rs.getLong("fulfillment_provider_id"),
                        rs.getString("provider_code"),
                        rs.getString("provider_name")),
                exportId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("履约导出不存在: " + exportId);
        }
        return rows.getFirst();
    }

    // ------------------------------------------------------------------
    // 映射
    // ------------------------------------------------------------------

    private static ExportState mapState(ResultSet rs) throws SQLException {
        return new ExportState(
                rs.getLong("export_id"),
                rs.getLong("provider_id"),
                rs.getString("status"),
                rs.getString("chat_id"),
                rs.getInt("tracking_sla_minutes"),
                rs.getInt("reminder_interval_minutes"),
                nullableInstant(rs, "initial_sent_at"),
                nullableInstant(rs, "tracking_due_at"),
                nullableInstant(rs, "next_reminder_at"),
                nullableInstant(rs, "last_reminded_at"),
                rs.getInt("reminder_count"),
                rs.getString("last_error"),
                rs.getString("stopped_by"),
                rs.getString("stopped_reason"),
                nullableInstant(rs, "stopped_at"),
                rs.getLong("lock_version"));
    }

    private static Delivery mapDelivery(ResultSet rs) throws SQLException {
        return new Delivery(
                rs.getLong("id"),
                rs.getLong("export_id"),
                rs.getString("kind"),
                rs.getInt("sequence"),
                rs.getInt("initial_generation"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getString("stage"),
                rs.getString("chat_id"),
                rs.getString("request_id"),
                nullableInstant(rs, "ack_sent_at"),
                rs.getString("media_id_sha256"),
                rs.getString("error_code"),
                rs.getString("error_message"));
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 导出企微出站状态行投影。 */
    public record ExportState(
            long exportId,
            long providerId,
            String status,
            String chatId,
            int slaMinutes,
            int intervalMinutes,
            Instant initialSentAt,
            Instant trackingDueAt,
            Instant nextReminderAt,
            Instant lastRemindedAt,
            int reminderCount,
            String lastError,
            String stoppedBy,
            String stoppedReason,
            Instant stoppedAt,
            long version) {}

    /** 单次 delivery 证据行投影。 */
    public record Delivery(
            long id,
            long exportId,
            String kind,
            int sequence,
            int initialGeneration,
            String status,
            int attempts,
            int maxAttempts,
            String stage,
            String chatId,
            String requestId,
            Instant ackSentAt,
            String mediaIdSha256,
            String errorCode,
            String errorMessage) {}

    /** 发送侧事实投影。 */
    public record ExportFacts(
            String batchNo, String fileRef, long providerId, String providerCode, String providerName) {}

    /** {@link #prepareReminder} 的线性化结果。 */
    public enum ReminderPrepare {
        /** 已 CAS 到 SENDING（含 recipient 证据）：调用方可以外部发送。 */
        CLAIMED,
        /** 同事务复查已收齐并标 COMPLETED：调用方 no-op。 */
        COMPLETED,
        /** 代际已变：delivery 已同事务标 SUPERSEDED，调用方 no-op。 */
        SUPERSEDED,
        /** 已停止/暂停/时间线已变/未领取到：调用方 no-op。 */
        NOOP
    }

    /** {@link #prepareReminder} 锁 state 行时读取的投影。 */
    private record StateLockRow(String status, String chatId) {}
}
