package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 来源订单批次的待确认清单。
 *
 * <p>存在的理由：此前批次只能按 id 打开（上传后拿到、或手工拼 {@code ?import_batch=} 链接），
 * 前端没有任何「今天有哪些批次等着我确认」的入口，确认发货实际只能靠企业微信卡片触发。
 * 这个清单就是那个入口。
 *
 * <p>只读，且刻意不调用 {@code ProviderFileService#validateSourceBatchExportability}——
 * 那会改行状态并建复核事项，渲染列表不该有副作用。
 */
@Service
class SourceBatchListService {

    /** 一次最多列这么多批次：这是操作台的当日待办，不是分页归档。 */
    private static final int MAX_BATCHES = 50;

    private final JdbcTemplate jdbc;

    SourceBatchListService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 待确认批次：尚未确认的，以及已确认但仍有行没进履约导出的（阻断行修好后待补做）。
     *
     * <p>行数统计用一次聚合查完，未处理行数用 LATERAL 逐批次算——批次数有上限，
     * 不会退化成大表扫描。
     */
    @Transactional(readOnly = true)
    List<Map<String, Object>> pendingConfirmation() {
        return jdbc.query(
                """
                SELECT ib.id, ib.batch_no, ib.original_file_name, ib.status,
                       ib.received_at, ib.confirmed_at, ib.confirmed_by,
                       source.effective_source_channel,
                       counts.total_rows, counts.ready_rows, counts.blocked_rows, counts.benign_skipped_rows,
                       pending.pending_rows
                FROM app.import_batches ib
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                CROSS JOIN LATERAL (
                    SELECT count(*) total_rows,
                           count(*) FILTER (WHERE rir.status='ACCEPTED') ready_rows,
                           count(*) FILTER (
                               WHERE rir.status<>'ACCEPTED'
                                 AND NOT (rir.status='REJECTED' AND rir.error_code='ORDER_ALREADY_EXISTS')
                           ) blocked_rows,
                           count(*) FILTER (
                               WHERE rir.status='REJECTED' AND rir.error_code='ORDER_ALREADY_EXISTS'
                           ) benign_skipped_rows
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
                ORDER BY ib.received_at DESC, ib.id DESC
                LIMIT ?
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", resultSet.getString("id"));
                    value.put("batch_no", resultSet.getString("batch_no"));
                    value.put("original_file_name", resultSet.getString("original_file_name"));
                    value.put("status", resultSet.getString("status"));
                    String channel = resultSet.getString("effective_source_channel");
                    value.put("source_channel", channel);
                    value.put("source_channel_display_name", SourceChannelDisplayNames.displayName(channel));
                    value.put("received_at", resultSet.getTimestamp("received_at").toInstant());
                    value.put("confirmed_at", resultSet.getTimestamp("confirmed_at") == null
                            ? null : resultSet.getTimestamp("confirmed_at").toInstant());
                    value.put("confirmed_by", resultSet.getString("confirmed_by"));
                    int readyRows = resultSet.getInt("ready_rows");
                    int blockedRows = resultSet.getInt("blocked_rows");
                    int pendingRows = resultSet.getInt("pending_rows");
                    value.put("total_rows", resultSet.getInt("total_rows"));
                    value.put("ready_rows", readyRows);
                    value.put("blocked_rows", blockedRows);
                    value.put("benign_skipped_rows", resultSet.getInt("benign_skipped_rows"));
                    value.put("pending_rows", pendingRows);
                    // 与 SourceBatchConfirmReadiness 同一口径：有待处理的已接收行才可确认。
                    value.put("confirmable", pendingRows > 0);
                    value.put("partial", pendingRows > 0 && blockedRows > 0);
                    return value;
                },
                MAX_BATCHES);
    }
}
