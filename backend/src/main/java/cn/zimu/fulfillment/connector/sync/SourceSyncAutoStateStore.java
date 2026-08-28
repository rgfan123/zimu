package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.persistence.ConcurrencyConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 自动在线回传的可查询资格终态、领取租约与重试时钟；不改写文件回传业务事实。 */
@Repository
public class SourceSyncAutoStateStore {

    public static final String PENDING = "SOURCE_SYNC_AUTO_PENDING";
    public static final String ONLINE_PUSH_NOT_APPLICABLE = "SOURCE_SYNC_ONLINE_PUSH_NOT_APPLICABLE";
    public static final String FILE_RETURN_ONLY = "SOURCE_SYNC_FILE_RETURN_ONLY";

    private static final String CANDIDATES = """
            SELECT s.id shipment_id,
                   COALESCE(source.effective_source_channel, o.source_channel) source_channel
            FROM app.shipments s
            JOIN app.trackings t ON t.shipment_id=s.id
            JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
            LEFT JOIN app.import_batches ib
              ON ib.id=o.source_import_batch_id AND ib.batch_type='SOURCE_ORDER'
            LEFT JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
            LEFT JOIN app.shipment_syncs ss
              ON ss.shipment_id=s.id
             AND ss.source_channel=COALESCE(source.effective_source_channel, o.source_channel)
            WHERE s.shipment_status='SHIPPED'
              AND (ss.shipment_id IS NULL OR ss.sync_status='SYNC_FAILED')
            """;

    private final JdbcTemplate jdbc;

    public SourceSyncAutoStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 先为新候选物化 PENDING 行，再以 {@code FOR UPDATE SKIP LOCKED} 原子领取到期行。
     * 多实例只能有一个 owner 获得同一 Shipment/渠道，租约过期后才允许恢复。
     */
    @Transactional
    public List<Claim> claimCandidates(String owner, Duration lease, int limit) {
        String stableOwner = requireOwner(owner);
        long leaseSeconds = positiveSeconds(lease, "lease");
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        jdbc.update(
                """
                INSERT INTO app.source_sync_auto_states
                    (shipment_id, source_channel, disposition, reason_code,
                     attempt_count, next_attempt_at)
                SELECT candidate.shipment_id, candidate.source_channel,
                       'PENDING', ?, 0, CURRENT_TIMESTAMP
                FROM (
                """ + CANDIDATES + """
                ) candidate
                LEFT JOIN app.source_sync_auto_states existing
                  ON existing.shipment_id=candidate.shipment_id
                 AND existing.source_channel=candidate.source_channel
                WHERE existing.shipment_id IS NULL
                ORDER BY candidate.shipment_id
                LIMIT ?
                ON CONFLICT (shipment_id, source_channel) DO NOTHING
                """,
                PENDING,
                boundedLimit);
        return jdbc.query(
                """
                UPDATE app.source_sync_auto_states claimed
                SET lease_owner=?,
                    lease_until=statement_timestamp() + (? || ' seconds')::interval,
                    updated_at=CURRENT_TIMESTAMP
                WHERE (claimed.shipment_id, claimed.source_channel) IN (
                    SELECT pending.shipment_id, pending.source_channel
                    FROM app.source_sync_auto_states pending
                    JOIN (
                """ + CANDIDATES + """
                    ) candidate
                      ON candidate.shipment_id=pending.shipment_id
                     AND candidate.source_channel=pending.source_channel
                    WHERE pending.disposition IN ('PENDING', 'RETRY_WAIT')
                      AND pending.next_attempt_at <= CURRENT_TIMESTAMP
                      AND (pending.lease_until IS NULL
                        OR pending.lease_until < statement_timestamp())
                    ORDER BY pending.next_attempt_at, pending.shipment_id
                    LIMIT ?
                    FOR UPDATE OF pending SKIP LOCKED
                )
                RETURNING claimed.shipment_id, claimed.source_channel,
                          (SELECT cc.transport_mode
                             FROM app.connector_configs cc
                            WHERE cc.source_channel=claimed.source_channel) transport_mode,
                          claimed.lease_owner, claimed.lease_until
                """,
                (rs, row) -> new Claim(
                        rs.getLong("shipment_id"),
                        SourceChannel.valueOf(rs.getString("source_channel")),
                        rs.getString("transport_mode"),
                        rs.getString("lease_owner"),
                        rs.getObject("lease_until", OffsetDateTime.class)),
                stableOwner,
                leaseSeconds,
                boundedLimit);
    }

    /** 结构性不支持在线回传：落终态并释放租约。 */
    public State recordNotApplicable(Claim claim, String reasonCode) {
        return terminal(claim, Disposition.NOT_APPLICABLE, reasonCode);
    }

    /** 确定性业务/配置阻断：不打印失败日志，留给既有人工闭环并延后复查。 */
    public State defer(Claim claim, String reasonCode, Duration recheckDelay) {
        long delaySeconds = positiveSeconds(recheckDelay, "recheckDelay");
        return jdbc.query(
                        """
                        UPDATE app.source_sync_auto_states
                        SET disposition='PENDING', reason_code=?,
                            next_attempt_at=CURRENT_TIMESTAMP + (? || ' seconds')::interval,
                            lease_owner=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE shipment_id=? AND source_channel=? AND lease_owner=?
                        RETURNING shipment_id, source_channel, disposition, reason_code,
                                  attempt_count, next_attempt_at, lease_owner, lease_until,
                                  created_at, updated_at
                        """,
                        (rs, row) -> map(rs),
                        stableReason(reasonCode),
                        delaySeconds,
                        claim.shipmentId(),
                        claim.sourceChannel().name(),
                        claim.leaseOwner())
                .stream()
                .findFirst()
                .orElseThrow(() -> new LeaseLostException(claim.shipmentId()));
    }

    /** 临时不可用：同一 Shipment/渠道按 1、2、4…倍增长并封顶。 */
    public State scheduleRetry(
            Claim claim,
            String reasonCode,
            Duration initialBackoff,
            Duration maxBackoff) {
        long initialSeconds = positiveSeconds(initialBackoff, "initialBackoff");
        long maxSeconds = Math.max(initialSeconds, positiveSeconds(maxBackoff, "maxBackoff"));
        return jdbc.query(
                        """
                        UPDATE app.source_sync_auto_states
                        SET disposition='RETRY_WAIT', reason_code=?,
                            attempt_count=attempt_count + 1,
                            next_attempt_at=CURRENT_TIMESTAMP + (
                                LEAST(
                                    ?::numeric,
                                    ?::numeric * power(2, LEAST(attempt_count, 30))
                                ) || ' seconds'
                            )::interval,
                            lease_owner=NULL, lease_until=NULL,
                            updated_at=CURRENT_TIMESTAMP
                        WHERE shipment_id=? AND source_channel=? AND lease_owner=?
                        RETURNING shipment_id, source_channel, disposition, reason_code,
                                  attempt_count, next_attempt_at, lease_owner, lease_until,
                                  created_at, updated_at
                        """,
                        (rs, row) -> map(rs),
                        stableReason(reasonCode),
                        maxSeconds,
                        initialSeconds,
                        claim.shipmentId(),
                        claim.sourceChannel().name(),
                        claim.leaseOwner())
                .stream()
                .findFirst()
                .orElseThrow(() -> new LeaseLostException(claim.shipmentId()));
    }

    public Optional<State> find(long shipmentId, SourceChannel channel) {
        return jdbc.query(
                        """
                        SELECT shipment_id, source_channel, disposition, reason_code,
                               attempt_count, next_attempt_at, lease_owner, lease_until,
                               created_at, updated_at
                        FROM app.source_sync_auto_states
                        WHERE shipment_id=? AND source_channel=?
                        """,
                        (rs, row) -> map(rs),
                        shipmentId,
                        channel.name())
                .stream()
                .findFirst();
    }

    /** 已在线成功时删除调度投影；权威成功事实仍在 shipment_syncs。 */
    public void complete(Claim claim) {
        int deleted = jdbc.update(
                """
                DELETE FROM app.source_sync_auto_states
                WHERE shipment_id=? AND source_channel=? AND lease_owner=?
                """,
                claim.shipmentId(),
                claim.sourceChannel().name(),
                claim.leaseOwner());
        if (deleted != 1) {
            throw new LeaseLostException(claim.shipmentId());
        }
    }

    private State terminal(Claim claim, Disposition disposition, String reasonCode) {
        return jdbc.query(
                        """
                        UPDATE app.source_sync_auto_states
                        SET disposition=?, reason_code=?, next_attempt_at=NULL,
                            lease_owner=NULL, lease_until=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE shipment_id=? AND source_channel=? AND lease_owner=?
                        RETURNING shipment_id, source_channel, disposition, reason_code,
                                  attempt_count, next_attempt_at, lease_owner, lease_until,
                                  created_at, updated_at
                        """,
                        (rs, row) -> map(rs),
                        disposition.name(),
                        stableReason(reasonCode),
                        claim.shipmentId(),
                        claim.sourceChannel().name(),
                        claim.leaseOwner())
                .stream()
                .findFirst()
                .orElseThrow(() -> new LeaseLostException(claim.shipmentId()));
    }

    private static State map(ResultSet rs) throws SQLException {
        return new State(
                rs.getLong("shipment_id"),
                SourceChannel.valueOf(rs.getString("source_channel")),
                Disposition.valueOf(rs.getString("disposition")),
                rs.getString("reason_code"),
                rs.getInt("attempt_count"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getString("lease_owner"),
                rs.getObject("lease_until", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static long positiveSeconds(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
        return Math.max(1, value.toSeconds());
    }

    private static String requireOwner(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("lease owner 不能为空");
        }
        String stable = value.trim();
        return stable.substring(0, Math.min(128, stable.length()));
    }

    private static String stableReason(String value) {
        String stable = value == null || value.isBlank() ? "SOURCE_SYNC_TEMPORARILY_UNAVAILABLE" : value.trim();
        return stable.substring(0, Math.min(64, stable.length()));
    }

    public enum Disposition {
        PENDING,
        NOT_APPLICABLE,
        RETRY_WAIT
    }

    /**
     * 租约已被其他实例接管：这是多实例调度里的**正常并发结果**，不是代码缺陷。
     * 绕开 {@code @Repository} 持久化异常翻译的完整理由见 {@link ConcurrencyConflictException}。
     */
    public static class LeaseLostException extends ConcurrencyConflictException {

        private final long shipmentId;

        LeaseLostException(long shipmentId) {
            super("自动回传调度租约已丢失: " + shipmentId);
            this.shipmentId = shipmentId;
        }

        public long shipmentId() {
            return shipmentId;
        }
    }

    public record Claim(
            long shipmentId,
            SourceChannel sourceChannel,
            String transportMode,
            String leaseOwner,
            OffsetDateTime leaseUntil) {}

    public record State(
            long shipmentId,
            SourceChannel sourceChannel,
            Disposition disposition,
            String reasonCode,
            int attemptCount,
            OffsetDateTime nextAttemptAt,
            String leaseOwner,
            OffsetDateTime leaseUntil,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}
}
