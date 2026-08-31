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
     * 「定义上无事可做」的行：订单早已入库，或来源侧早已发完。
     *
     * <p>这类行不是待处理问题——确认发货对它们结构上就没有动作可做。飞象用户导「全部订单」
     * 必然混进历史已发单，2026-08-28 生产实例里 10 行只有 1 张新单，被 9 行
     * SOURCE_ORDER_ALREADY_FULFILLED 挡住，而这 9 行既没有复核事项也没有行级处置端点，
     * 用户完全无路可走。
     *
     * <p>注意与真正的阻断行区分：NEED_REVIEW（缺 SKU 映射等）是「本应建单却没建成」，
     * 用户最终是想让它发出去的，那类行走部分确认跳过 + 修好后补做，不在本豁免之列。
     */
    private static final String BENIGN_CODES = "'ORDER_ALREADY_EXISTS', 'SOURCE_ORDER_ALREADY_FULFILLED'";

    /**
     * 良性豁免的结构性保险：这两类行从不建单，{@code order_line_id} 恒为 NULL（生产实测）。
     * 将来若某类行开始建单，它就会自动回到阻断口径，而不是被静默放过。
     */
    private static String benignPredicate(String alias) {
        return "(" + alias + "order_line_id IS NULL AND " + alias + "error_code IN (" + BENIGN_CODES + "))";
    }

    /** 表别名前缀：无别名传空串，有别名传 {@code "rir."}。 */
    private static String prefix(String tableAlias) {
        return tableAlias == null || tableAlias.isBlank() ? "" : tableAlias + ".";
    }

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
        // 候选流水线（2026-08-31 合入）里，干净 RECEIVED 行 = 已过就绪门禁、等放行事务成单的行：
        // 对确认闸门而言它们与 ACCEPTED 同为「就绪」，且天然属于待处理量（确认动作会把它们成单）。
        return new Readiness(
                countRows(batchId, "(status='ACCEPTED' OR " + READY_RECEIVED + ")"),
                countPendingRows(batchId) + countRows(batchId, READY_RECEIVED),
                countRows(batchId, blockedPredicate("")),
                countRows(batchId, benignSkippedPredicate("")),
                blockers(batchId));
    }

    /** 候选流水线的就绪行：已接收入候选、无任何错误标记、等待放行成单。 */
    private static final String READY_RECEIVED = "(status='RECEIVED' AND error_code IS NULL)";

    /** 阻断口径：非 ACCEPTED、非干净 RECEIVED（候选流水线的就绪行），且排除「定义上无事可做」的行。 */
    static String blockedPredicate(String tableAlias) {
        String alias = prefix(tableAlias);
        return alias + "status<>'ACCEPTED' AND NOT (" + alias + "status='RECEIVED' AND " + alias
                + "error_code IS NULL) AND NOT " + benignPredicate(alias);
    }

    /** 良性跳过口径：非 ACCEPTED，且确实属于「定义上无事可做」。 */
    static String benignSkippedPredicate(String tableAlias) {
        String alias = prefix(tableAlias);
        return alias + "status<>'ACCEPTED' AND " + benignPredicate(alias);
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
        // 注意：文本块会剥掉每行的行尾空白，别把 "AND " 留在文本块末尾再拼接——
        // 那会拼成 "ANDstatus" 并在运行时炸成 SQL 语法错误。这里整条 SQL 用普通拼接。
        String sql = "SELECT id, source_order_ref, status, error_code,"
                + " error_detail->>'message' detail_message"
                + " FROM app.raw_import_rows"
                + " WHERE import_batch_id=? AND " + blockedPredicate("")
                + " ORDER BY sheet_index, row_index LIMIT 200";
        return jdbc.query(
                sql,
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
