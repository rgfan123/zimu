package cn.zimu.fulfillment.connector.feixiang;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从 Shipment 反查飞象平台<b>数字</b>子订单 ID（{@code order_son_id}）。
 *
 * <p><b>为什么需要这一步。</b>飞象的写与读用的是两种不同的 ID，而领域层只带得出一种：
 * {@code SourceSyncFacts.sourceLineRef} 对 JSON 拉取链路是 {@code order_son_sn}（{@code S…}
 * 子订单号），而详情接口 {@code ajaxGetSendBeforePro} 只吃数字 {@code order_son_id}，
 * 发货接口 {@code ajaxSendOrderProduct} 只吃数字 {@code order_product_id}。三者互不可代入
 * （HAR 里已经有过一次混用导致平台回「供应商不正确」的事故）。
 *
 * <p><b>只从快照取"身份"，不从快照取"事实"。</b>这里读的是导入时落在
 * {@code raw_import_rows.raw_cells->'snapshot'} 里的 {@code order_son_id}——平台主键，
 * 不可变。数量、状态、收货人、是否已发货这些<b>会漂移</b>的事实一律不从快照读，
 * 由调用方在提交前重新只读拉取平台详情获得。
 *
 * <p><b>两个 fail-closed 判据</b>：
 * <ul>
 *   <li>解析不出唯一 {@code order_son_id}（含 Excel 导入链路——它的 raw_cells 里根本没有
 *       这个键）一律拒绝。这天然把在线回传限制在 2026-08-28 之后 JSON 拉取进来的批次上，
 *       是一道免费的灰度边界。</li>
 *   <li>该来源行是多个子订单合并而成（{@code merged_order_son_ids} 存在）时同样拒绝：
 *       {@code ajaxSendOrderProduct} 能否跨子订单一次提交<b>没有任何证据</b>，
 *       不拿客户平台去试。</li>
 * </ul>
 */
@Component
public class FeixiangShipmentLineage {

    private final JdbcTemplate jdbc;

    public FeixiangShipmentLineage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 解析结果；{@code orderSonId} 非空即可用于详情/发货接口。 */
    public record Resolution(String orderSonId, String businessCode, String message) {

        public boolean resolved() {
            return orderSonId != null && !orderSonId.isBlank();
        }

        static Resolution ok(String orderSonId) {
            return new Resolution(orderSonId, "OK", "已解析飞象子订单 ID");
        }

        static Resolution failed(String businessCode, String message) {
            return new Resolution(null, businessCode, message);
        }
    }

    public Resolution resolve(long shipmentId) {
        List<Row> rows;
        try {
            rows = jdbc.query(SQL, (rs, rowNum) -> new Row(
                    rs.getString("order_son_id"), rs.getBoolean("merged")), shipmentId);
        } catch (RuntimeException exception) {
            return Resolution.failed(
                    "FEIXIANG_LINEAGE_UNAVAILABLE", "飞象来源血缘读取失败，未提交任何平台请求");
        }
        if (rows.isEmpty()) {
            return Resolution.failed(
                    "FEIXIANG_LINEAGE_REQUIRED", "该 Shipment 没有可用的飞象来源原始行");
        }
        if (rows.stream().anyMatch(Row::merged)) {
            return Resolution.failed(
                    "FEIXIANG_MERGED_SUB_ORDER_UNSUPPORTED",
                    "该来源行由多个飞象子订单合并而成，在线回传不支持跨子订单提交，请走人工核对");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Row row : rows) {
            String id = row.orderSonId() == null ? "" : row.orderSonId().trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Resolution.failed(
                    "FEIXIANG_ORDER_SON_ID_REQUIRED",
                    "来源原始行没有飞象子订单 ID（Excel 导入批次不支持在线回传，请继续走回填文件）");
        }
        if (ids.size() != 1) {
            return Resolution.failed(
                    "FEIXIANG_ORDER_SON_ID_AMBIGUOUS",
                    "该 Shipment 对应多个飞象子订单 ID，在线回传要求一一对应");
        }
        String orderSonId = ids.iterator().next();
        if (!orderSonId.chars().allMatch(Character::isDigit)) {
            // 硬门闩：订单号（D…）与子订单号（S…）都不是数字，绝不代入详情/发货接口。
            return Resolution.failed(
                    "FEIXIANG_ORDER_SON_ID_INVALID", "飞象子订单 ID 必须是数字，拒绝代入平台接口");
        }
        return Resolution.ok(orderSonId);
    }

    private record Row(String orderSonId, boolean merged) {}

    private static final String SQL = """
            WITH target_lines AS (
                SELECT DISTINCT ol.id
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id = si.fulfillment_id
                JOIN app.order_lines ol ON ol.id = f.order_line_id
                WHERE si.shipment_id = ?
            ),
            target_raw_rows AS (
                SELECT rir.id
                FROM app.raw_import_rows rir
                JOIN target_lines tl ON tl.id = rir.order_line_id
                UNION
                SELECT rirol.raw_import_row_id
                FROM app.raw_import_row_order_lines rirol
                JOIN target_lines tl ON tl.id = rirol.order_line_id
            )
            SELECT DISTINCT
                   rir.raw_cells -> 'snapshot' ->> 'order_son_id' AS order_son_id,
                   (rir.raw_cells -> 'snapshot' -> 'merged_order_son_ids') IS NOT NULL AS merged
            FROM app.raw_import_rows rir
            JOIN target_raw_rows trr ON trr.id = rir.id
            WHERE rir.status = 'ACCEPTED'
            """;
}
