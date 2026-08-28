package cn.zimu.fulfillment.connector.schedule;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 自动发货的批次筛选：哪些批次「完全就绪」，哪些「有阻断行、只能等人」。
 *
 * <p><b>为什么自动发货比人工确认更严</b>：{@code SourceImportService#confirm} 支持部分确认——
 * 跳过阻断行，先把能发的发出去。那个能力是给人用的：人点下确认，响应里会列出被跳过的行，
 * 他当场知道少发了什么。定时任务没有这个「当场知道」的环节，自动跳过阻断行等于
 * 每天固定时刻悄悄少发一批货，而且没有任何人在看。
 *
 * <p>所以本类的判据是 {@code pending_rows > 0 AND blocked_rows = 0}：
 * 有活可干，且一行问题都没有。只要有一行阻断，整批交给人，并推消息说明。
 *
 * <p><b>口径来源与已知风险</b>：阻断判据取自 {@link AutoShipBlockedPredicate}，它是
 * {@code SourceBatchConfirmReadiness#blockedPredicate} 的镜像（原件包私有，跨包引用不到）。
 * 分叉风险与三道防线写在那个类上，其中最硬的一道是逐字符比对两者输出的 parity 测试。
 */
@Component
class AutoShipReadiness {

    /** 阻断口径：非 ACCEPTED，且排除良性无事可做行。判据只有一个来源，见 AutoShipBlockedPredicate。 */
    private static final String BLOCKED_PREDICATE = AutoShipBlockedPredicate.blockedPredicate("rir");

    /**
     * 待办批次 + 就绪度。判据与 {@code SourceBatchListService#pendingConfirmation} 对齐：
     * 未确认的，以及已确认但仍有行没进履约导出的（阻断行修好后待补做）。
     *
     * <p>阻断原因只取 {@code error_code}（受控词表），**不取 error_detail 里的自由文本**：
     * 那段文本由各解析器自行拼装，可能带上收件人字段，而本结果会被渲染进企微卡片。
     */
    private static final String SQL =
            """
            SELECT ib.id, ib.batch_no, source.effective_source_channel,
                   counts.blocked_rows, pending.pending_rows, counts.blocked_codes
            FROM app.import_batches ib
            JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
            CROSS JOIN LATERAL (
                SELECT count(*) FILTER (WHERE %s) blocked_rows,
                       COALESCE(string_agg(DISTINCT rir.error_code, ',')
                                FILTER (WHERE %s), '') blocked_codes
                FROM app.raw_import_rows rir
                WHERE rir.import_batch_id=ib.id
            ) counts
            CROSS JOIN LATERAL (
                SELECT count(DISTINCT rir.id) pending_rows
                FROM app.raw_import_rows rir
                JOIN (
                    SELECT inner_row.id raw_row_id, inner_row.order_line_id
                    FROM app.raw_import_rows inner_row
                    WHERE inner_row.import_batch_id=ib.id AND inner_row.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows inner_row ON inner_row.id=rirol.raw_import_row_id
                    WHERE inner_row.import_batch_id=ib.id
                ) links ON links.raw_row_id=rir.id
                WHERE rir.import_batch_id=ib.id AND rir.status='ACCEPTED'
                  AND NOT EXISTS (
                    SELECT 1 FROM app.fulfillment_export_items fei
                    WHERE fei.raw_import_row_id=rir.id AND fei.order_line_id=links.order_line_id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM app.shipment_items si
                    JOIN app.fulfillments f ON f.id=si.fulfillment_id
                    WHERE f.order_line_id=links.order_line_id
                  )
            ) pending
            WHERE ib.batch_type='SOURCE_ORDER'
              AND (ib.confirmed_at IS NULL OR pending.pending_rows > 0)
              AND (counts.blocked_rows > 0 OR pending.pending_rows > 0)
            ORDER BY ib.received_at, ib.id
            LIMIT ?
            """
                    .formatted(BLOCKED_PREDICATE, BLOCKED_PREDICATE);

    private final JdbcTemplate jdbc;

    AutoShipReadiness(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param blockedCodes 阻断行的 error_code 去重列表（受控词表，无自由文本）
     */
    record Candidate(
            long batchId,
            String batchNo,
            String sourceChannel,
            int pendingRows,
            int blockedRows,
            List<String> blockedCodes) {

        /** 完全就绪：有活可干，且一行阻断都没有。只有这种批次会被自动确认。 */
        boolean fullyReady() {
            return pendingRows > 0 && blockedRows == 0;
        }
    }

    /** 取本次运行的候选批次；上限由调用方给，爆炸半径必须有界。 */
    List<Candidate> candidates(int limit) {
        return jdbc.query(
                SQL,
                (resultSet, rowNum) -> {
                    String codes = resultSet.getString("blocked_codes");
                    return new Candidate(
                            resultSet.getLong("id"),
                            resultSet.getString("batch_no"),
                            resultSet.getString("effective_source_channel"),
                            resultSet.getInt("pending_rows"),
                            resultSet.getInt("blocked_rows"),
                            codes == null || codes.isBlank()
                                    ? List.of()
                                    : List.of(codes.split(",")));
                },
                Math.max(1, limit));
    }
}
