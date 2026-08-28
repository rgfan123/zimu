package cn.zimu.fulfillment.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 来源批次「能不能确认发货」的只读投影。
 *
 * <p>存在的理由：前端确认按钮的可用性必须与 {@link SourceImportService#confirm} 的闸门同源，
 * 否则用户点下去才发现被拒。所以闸门判据只写一遍，确认路径与展示路径都读这里。
 *
 * <p>刻意保持只读：{@code ProviderFileService#validateSourceBatchExportability} 会把缺映射的行
 * 改判 NEED_REVIEW 并建复核事项（REQUIRES_NEW 事务，即使外层回滚也留痕），那是确认动作的一部分，
 * 不能在渲染列表时触发。本类只读 raw_import_rows 与既有覆盖关系，不写任何表。
 */
@Component
class SourceBatchConfirmReadiness {

    /**
     * ORDER_ALREADY_EXISTS 是良性跳过而非待处理问题：该行对应的订单早已入库。
     * 与 {@link SourceImportService#confirm} 的豁免口径必须保持一致。
     */
    private static final String BENIGN_REJECT_CODE = "ORDER_ALREADY_EXISTS";

    private final JdbcTemplate jdbc;

    SourceBatchConfirmReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个阻断行的可读说明。{@code reason} 取自行上的 error_detail.message，
     * 缺失时回退到 error_code——前端要能看到原因，而不是只拿到一个数字。
     */
    record BlockedRow(String rowId, String sourceOrderRef, String status, String errorCode, String reason) {

        Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("row_id", rowId);
            payload.put("source_order_ref", sourceOrderRef);
            payload.put("status", status);
            payload.put("error_code", errorCode);
            payload.put("reason", reason);
            return payload;
        }
    }

    /**
     * @param readyRows 已接收（ACCEPTED）行数
     * @param pendingRows 已接收但尚未进入履约导出/发货批次的行数——确认动作真正会处理的量
     * @param blockedRows 待处理行数（NEED_REVIEW 与非良性 REJECTED）
     * @param benignSkippedRows 良性跳过行数（订单早已存在）
     * @param blockers 阻断行明细
     */
    record Readiness(
            int readyRows,
            int pendingRows,
            int blockedRows,
            int benignSkippedRows,
            List<BlockedRow> blockers) {

        /** 有待处理的已接收行才值得确认；没有就没有任何事情可做。 */
        boolean confirmable() {
            return pendingRows > 0;
        }

        /** 跳过阻断行的部分确认：能发的和发不了的同时存在。 */
        boolean partial() {
            return confirmable() && blockedRows > 0;
        }

        Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ready_rows", readyRows);
            payload.put("pending_rows", pendingRows);
            payload.put("blocked_rows", blockedRows);
            payload.put("benign_skipped_rows", benignSkippedRows);
            payload.put("confirmable", confirmable());
            payload.put("partial", partial());
            payload.put("blockers", blockers.stream().map(BlockedRow::toPayload).toList());
            return payload;
        }
    }

    Readiness of(long batchId) {
        return new Readiness(
                countRows(batchId, "status='ACCEPTED'"),
                countPendingRows(batchId),
                countRows(batchId, blockedPredicate()),
                countRows(batchId, "status='REJECTED' AND error_code='" + BENIGN_REJECT_CODE + "'"),
                blockers(batchId));
    }

    /** 阻断口径：非 ACCEPTED，且排除良性重复行。与确认闸门同一条判据。 */
    static String blockedPredicate() {
        return "status<>'ACCEPTED' AND NOT (status='REJECTED' AND error_code='" + BENIGN_REJECT_CODE + "')";
    }

    private int countRows(long batchId, String predicate) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=? AND " + predicate,
                Integer.class,
                batchId);
        return count == null ? 0 : count;
    }

    /**
     * 已接收但还没落到履约导出或发货批次上的行。
     *
     * <p>这同时覆盖两种场景：首次确认（全部已接收行都待处理），以及阻断行被修好后
     * 重新确认（只剩新转 ACCEPTED 的那几行待处理）。判据与
     * {@code ProviderFileService#candidateRows} 的「未导出」排除条件对齐。
     */
    private int countPendingRows(long batchId) {
        Integer count = jdbc.queryForObject(
                """
                WITH raw_line_links AS (
                    SELECT rir.id raw_row_id, rir.order_line_id
                    FROM app.raw_import_rows rir
                    WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                    WHERE rir.import_batch_id=?
                )
                SELECT count(DISTINCT rir.id)
                FROM app.raw_import_rows rir
                JOIN raw_line_links rll ON rll.raw_row_id=rir.id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                  AND NOT EXISTS (
                    SELECT 1 FROM app.fulfillment_export_items fei
                    WHERE fei.raw_import_row_id=rir.id AND fei.order_line_id=rll.order_line_id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM app.shipment_items si
                    JOIN app.fulfillments f ON f.id=si.fulfillment_id
                    WHERE f.order_line_id=rll.order_line_id
                  )
                """,
                Integer.class,
                batchId,
                batchId,
                batchId);
        return count == null ? 0 : count;
    }

    private List<BlockedRow> blockers(long batchId) {
        return jdbc.query(
                """
                SELECT id, source_order_ref, status, error_code,
                       error_detail->>'message' detail_message
                FROM app.raw_import_rows
                WHERE import_batch_id=? AND """ + blockedPredicate()
                        + " ORDER BY sheet_index, row_index LIMIT 200",
                (resultSet, rowNum) -> {
                    String errorCode = resultSet.getString("error_code");
                    String detailMessage = resultSet.getString("detail_message");
                    return new BlockedRow(
                            resultSet.getString("id"),
                            resultSet.getString("source_order_ref"),
                            resultSet.getString("status"),
                            errorCode,
                            detailMessage == null || detailMessage.isBlank() ? errorCode : detailMessage);
                },
                batchId);
    }
}
