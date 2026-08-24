package cn.zimu.fulfillment.batch;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 导入批次四段链路进度的确定性投影（Excel 履约闭环）。
 *
 * <p>四段的口径固定在此，不由任何模型参与：
 * <ol>
 *   <li>**收表**：批次里建出多少订单行，其中多少被 OPEN 复核事项挡住；</li>
 *   <li>**发货**：这些订单建出多少发货单，京东 SDK 已提交多少、失败多少；</li>
 *   <li>**回填**：多少发货单拿到了运单号；</li>
 *   <li>**回传**：多少发货单已回传给来源平台（V54 的 shipment_syncs 状态机）。</li>
 * </ol>
 *
 * <p>「未接入」与「0」严格区分：第三方履约方没有 SDK 通道时，发货段的 done 不是 0
 * 而是该段对这批不适用。把两者混成 0，界面上就会出现「0 单待发但批次没走完」这种
 * 自相矛盾的读数。
 */
@Service
public class ImportBatchProgressService {

    /** 单个批次最多返回的阻塞分组数——阻塞原因超过这个数量，人要看的是列表页不是卡片。 */
    private static final int MAX_BLOCKERS = 6;

    private final JdbcTemplate jdbc;

    public ImportBatchProgressService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ImportBatchProgress of(long batchId) {
        List<BatchRow> rows = jdbc.query(
                """
                SELECT b.id, b.batch_no, b.batch_type, b.source_channel, b.status, b.revision_no,
                       b.received_at, b.processed_at,
                       (SELECT count(*) FROM app.orders o
                         WHERE o.source_import_batch_id = b.id)                       AS order_count,
                       (SELECT count(*) FROM app.order_lines l
                         JOIN app.orders o ON o.id = l.order_id
                        WHERE o.source_import_batch_id = b.id)                        AS line_count,
                       (SELECT count(*) FROM app.review_cases rc
                         LEFT JOIN app.orders o ON o.id = rc.order_id
                        WHERE rc.status = 'OPEN'
                          AND (rc.import_batch_id = b.id OR o.source_import_batch_id = b.id))
                                                                                      AS open_review_count,
                       (SELECT count(*) FROM app.shipments s
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id)                        AS shipment_count,
                       (SELECT count(*) FROM app.shipments s
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id
                          AND s.shipment_status IN ('SHIPPED', 'DELIVERED'))          AS shipped_count,
                       (SELECT count(*) FROM app.shipments s
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id
                          AND s.shipment_status = 'FAILED')                           AS shipment_failed_count,
                       (SELECT count(*) FROM app.shipment_jd_outbounds jo
                         JOIN app.shipments s ON s.id = jo.shipment_id
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id
                          AND jo.sync_status = 'SYNC_FAILED')                         AS jd_failed_count,
                       (SELECT count(*) FROM app.shipment_syncs ss
                         JOIN app.shipments s ON s.id = ss.shipment_id
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id
                          AND ss.tracking_number IS NOT NULL)                         AS tracked_count,
                       (SELECT count(*) FROM app.shipment_syncs ss
                         JOIN app.shipments s ON s.id = ss.shipment_id
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id
                          AND ss.sync_status = 'SYNCED')                              AS returned_count,
                       (SELECT count(*) FROM app.shipment_syncs ss
                         JOIN app.shipments s ON s.id = ss.shipment_id
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id
                          AND ss.sync_status IN ('SYNC_FAILED', 'RECONCILIATION_REQUIRED'))
                                                                                      AS return_failed_count,
                       (SELECT count(*) FROM app.shipment_syncs ss
                         JOIN app.shipments s ON s.id = ss.shipment_id
                         JOIN app.orders o ON o.id = s.order_id
                        WHERE o.source_import_batch_id = b.id)                        AS sync_row_count
                FROM app.import_batches b
                WHERE b.id = ?
                """,
                (rs, rowNum) -> map(rs),
                batchId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("导入批次不存在: " + batchId);
        }
        BatchRow row = rows.getFirst();
        return new ImportBatchProgress(
                row.id(),
                row.batchNo(),
                row.batchType(),
                row.sourceChannel(),
                row.status(),
                row.revisionNo(),
                row.receivedAt(),
                row.processedAt(),
                intakeStage(row),
                outboundStage(row),
                trackingStage(row),
                sourceReturnStage(row),
                blockers(batchId));
    }

    // ------------------------------------------------------------------
    // 四段口径
    // ------------------------------------------------------------------

    /** 收表：订单行建出来即算完成，被 OPEN 复核挡住的算 blocked。 */
    private static ImportBatchProgress.Stage intakeStage(BatchRow row) {
        boolean parsed = "COMPLETED".equals(row.status()) || "COMPLETED_WITH_REVIEW".equals(row.status());
        if (!parsed) {
            // 批次还在解析中或已失败：这一段还没有可信的分母
            return new ImportBatchProgress.Stage("收表", 0, 0, "FAILED".equals(row.status()) ? 1 : 0, true);
        }
        return new ImportBatchProgress.Stage(
                "收表", row.lineCount(), row.lineCount() - row.openReviewCount(), row.openReviewCount(), true);
    }

    /**
     * 发货：分母是这批建出的发货单数。没有发货单时该段**未接入**而不是「0 完成」——
     * 复核没过就不会有发货单，此时报「0/0 已完成」会让人以为发完了。
     */
    private static ImportBatchProgress.Stage outboundStage(BatchRow row) {
        if (row.shipmentCount() == 0) {
            return ImportBatchProgress.Stage.unsupported("发货");
        }
        return new ImportBatchProgress.Stage(
                "发货",
                row.shipmentCount(),
                row.shippedCount(),
                row.shipmentFailedCount() + row.jdFailedCount(),
                true);
    }

    /** 回填：分母同发货单数；拿到运单号即算完成。 */
    private static ImportBatchProgress.Stage trackingStage(BatchRow row) {
        if (row.shipmentCount() == 0) {
            return ImportBatchProgress.Stage.unsupported("回填");
        }
        return new ImportBatchProgress.Stage(
                "回填", row.shipmentCount(), row.trackedCount(), 0, true);
    }

    /**
     * 回传：只有存在 shipment_syncs 行才算接入。彩食鲜/聚福宝在线回传是 V54 的能力，
     * 没建回传意图的批次（例如纯 Excel 通道）这一段本就不适用，标未接入而非 0。
     */
    private static ImportBatchProgress.Stage sourceReturnStage(BatchRow row) {
        if (row.syncRowCount() == 0) {
            return ImportBatchProgress.Stage.unsupported("回传");
        }
        return new ImportBatchProgress.Stage(
                "回传", row.syncRowCount(), row.returnedCount(), row.returnFailedCount(), true);
    }

    // ------------------------------------------------------------------
    // 阻塞事实：按稳定码分组，带一个可去后台搜的业务号
    // ------------------------------------------------------------------

    private List<ImportBatchProgress.Blocker> blockers(long batchId) {
        List<ImportBatchProgress.Blocker> blockers = new ArrayList<>(jdbc.query(
                """
                SELECT '收表' AS stage, rc.reason_code AS code, count(*)::int AS cnt,
                       min(rc.case_no) AS sample_no
                FROM app.review_cases rc
                LEFT JOIN app.orders o ON o.id = rc.order_id
                WHERE rc.status = 'OPEN'
                  AND (rc.import_batch_id = ? OR o.source_import_batch_id = ?)
                GROUP BY rc.reason_code
                ORDER BY count(*) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ImportBatchProgress.Blocker(
                        rs.getString("stage"), rs.getString("code"),
                        rs.getInt("cnt"), rs.getString("sample_no")),
                batchId,
                batchId,
                MAX_BLOCKERS));
        blockers.addAll(jdbc.query(
                """
                SELECT '发货' AS stage, COALESCE(jo.last_error_code, 'UNKNOWN') AS code,
                       count(*)::int AS cnt, min(jo.erp_delivery_no) AS sample_no
                FROM app.shipment_jd_outbounds jo
                JOIN app.shipments s ON s.id = jo.shipment_id
                JOIN app.orders o ON o.id = s.order_id
                WHERE o.source_import_batch_id = ? AND jo.sync_status = 'SYNC_FAILED'
                GROUP BY COALESCE(jo.last_error_code, 'UNKNOWN')
                ORDER BY count(*) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ImportBatchProgress.Blocker(
                        rs.getString("stage"), rs.getString("code"),
                        rs.getInt("cnt"), rs.getString("sample_no")),
                batchId,
                MAX_BLOCKERS));
        blockers.addAll(jdbc.query(
                """
                SELECT '回传' AS stage, COALESCE(ss.last_error_code, ss.sync_status) AS code,
                       count(*)::int AS cnt, min(s.shipment_no) AS sample_no
                FROM app.shipment_syncs ss
                JOIN app.shipments s ON s.id = ss.shipment_id
                JOIN app.orders o ON o.id = s.order_id
                WHERE o.source_import_batch_id = ?
                  AND ss.sync_status IN ('SYNC_FAILED', 'RECONCILIATION_REQUIRED')
                GROUP BY COALESCE(ss.last_error_code, ss.sync_status)
                ORDER BY count(*) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ImportBatchProgress.Blocker(
                        rs.getString("stage"), rs.getString("code"),
                        rs.getInt("cnt"), rs.getString("sample_no")),
                batchId,
                MAX_BLOCKERS));
        return List.copyOf(blockers);
    }

    private static BatchRow map(ResultSet rs) throws SQLException {
        return new BatchRow(
                rs.getLong("id"),
                rs.getString("batch_no"),
                rs.getString("batch_type"),
                rs.getString("source_channel"),
                rs.getString("status"),
                rs.getInt("revision_no"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("processed_at", OffsetDateTime.class),
                rs.getInt("order_count"),
                rs.getInt("line_count"),
                rs.getInt("open_review_count"),
                rs.getInt("shipment_count"),
                rs.getInt("shipped_count"),
                rs.getInt("shipment_failed_count"),
                rs.getInt("jd_failed_count"),
                rs.getInt("tracked_count"),
                rs.getInt("returned_count"),
                rs.getInt("return_failed_count"),
                rs.getInt("sync_row_count"));
    }

    private record BatchRow(
            long id,
            String batchNo,
            String batchType,
            String sourceChannel,
            String status,
            int revisionNo,
            OffsetDateTime receivedAt,
            OffsetDateTime processedAt,
            int orderCount,
            int lineCount,
            int openReviewCount,
            int shipmentCount,
            int shippedCount,
            int shipmentFailedCount,
            int jdFailedCount,
            int trackedCount,
            int returnedCount,
            int returnFailedCount,
            int syncRowCount) {}
}
