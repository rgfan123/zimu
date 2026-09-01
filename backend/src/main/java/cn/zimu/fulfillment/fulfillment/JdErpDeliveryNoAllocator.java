package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.jd.JdErpDeliveryNoNamespace;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 京东商家出库单号的本地保留器。
 *
 * <p>新号全部由数据库函数分配到 {@code ZIMU-SO-*} 独占命名空间，并先写入
 * {@code shipment_jd_outbounds.erp_delivery_no} 的唯一键，再允许任何京东查询或写入。
 * {@code sync_status=NONE} 只表示号码已保留、尚未产生 addSoOrder 写意图。
 */
@Service
class JdErpDeliveryNoAllocator {

    private static final int MAX_ALLOCATION_ATTEMPTS = 16;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    JdErpDeliveryNoAllocator(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Caller already owns the Shipment row lock in an active planning transaction. */
    String reserveInCurrentTransaction(long shipmentId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD erpDeliveryNo reservation requires an active transaction");
        }
        String existing = existing(shipmentId);
        if (existing != null) {
            return existing;
        }
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            String candidate = nextCandidate();
            int inserted = jdbc.update(
                    """
                    INSERT INTO app.shipment_jd_outbounds
                        (shipment_id, erp_delivery_no, sync_status, retry_count)
                    VALUES (?, ?, 'NONE', 0)
                    ON CONFLICT DO NOTHING
                    """,
                    shipmentId,
                    candidate);
            if (inserted == 1) {
                return candidate;
            }
            existing = existing(shipmentId);
            if (existing != null) {
                return existing;
            }
        }
        throw new IllegalStateException("unable to reserve a locally unique JD erpDeliveryNo");
    }

    /**
     * Replace a candidate only while no external write could have happened. A concurrent transition to
     * SUBMITTING/SUBMITTED or an uncertain failure freezes the old reference and returns it unchanged.
     */
    String replaceSafeCandidate(long shipmentId, String expectedCandidate) {
        for (int attempt = 0; attempt < MAX_ALLOCATION_ATTEMPTS; attempt++) {
            try {
                String value = requiresNew.execute(status -> replaceOnce(shipmentId, expectedCandidate));
                if (value == null) {
                    throw new IllegalStateException("JD erpDeliveryNo replacement returned no value");
                }
                return value;
            } catch (DuplicateKeyException duplicate) {
                // The database unique key is the final local concurrency guard. Allocate another candidate.
            }
        }
        throw new IllegalStateException("unable to replace a colliding JD erpDeliveryNo");
    }

    static boolean belongsToOwnedNamespace(String value) {
        return JdErpDeliveryNoNamespace.owns(value);
    }

    private String replaceOnce(long shipmentId, String expectedCandidate) {
        LocalReservation row = jdbc.query(
                """
                SELECT erp_delivery_no, sync_status, failure_phase, last_error_code
                FROM app.shipment_jd_outbounds
                WHERE shipment_id=?
                FOR UPDATE
                """,
                rs -> rs.next()
                        ? new LocalReservation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
                        : null,
                shipmentId);
        if (row == null) {
            throw BusinessException.conflict(
                    "JD_ERP_DELIVERY_NO_RESERVATION_MISSING", "京东外部单号保留记录不存在，请刷新后重试");
        }
        if (!Objects.equals(row.erpDeliveryNo(), expectedCandidate)) {
            return row.erpDeliveryNo();
        }
        if (!safeToReplace(row)) {
            return row.erpDeliveryNo();
        }
        String candidate = nextCandidate();
        jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET erp_delivery_no=?, request_hash=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND erp_delivery_no=?
                """,
                candidate,
                shipmentId,
                expectedCandidate);
        return candidate;
    }

    private boolean safeToReplace(LocalReservation row) {
        if ("NONE".equals(row.syncStatus())) {
            return true;
        }
        return ShipmentJdOutboundPreparer.SYNC_STATUS_SYNC_FAILED.equals(row.syncStatus())
                && row.failurePhase() != null
                && !"RECONCILIATION_REQUIRED".equals(row.lastErrorCode())
                && ("VALIDATION".equals(row.failurePhase())
                        || !ShipmentJdOutboundPreparer.UNCERTAIN_EXTERNAL_RESULTS.contains(row.lastErrorCode()));
    }

    private String existing(long shipmentId) {
        List<String> values = jdbc.query(
                "SELECT erp_delivery_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                (rs, row) -> rs.getString(1),
                shipmentId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private String nextCandidate() {
        return jdbc.queryForObject("SELECT app.next_jd_erp_delivery_no()", String.class);
    }

    private record LocalReservation(
            String erpDeliveryNo,
            String syncStatus,
            String failurePhase,
            String lastErrorCode) {
    }
}
