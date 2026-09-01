package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.card.BatchPreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.PreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.ShipmentResultCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 发货结果卡来源：订单 → 发货批次 → 京东出库单 → 运单。
 *
 * <p>路由跟随 {@code preship}：确认卡发给谁，结果就回给谁。分开配两个会话只会制造
 * 「在这边确认、到那边找回执」的割裂，而这两张卡本来就是同一次对话的一问一答。
 * 卡面不含手机号与详细地址，但仍随 preship 走单聊——收货人姓名同样是客户信息。
 */
@Service
public class ShipmentResultCardSource implements WecomBusinessCardSource {

    static final int RAW_TABLE_ROW_LIMIT = 60;

    private static final String[] RAW_HEADERS = {"字段", "值"};
    private static final int[] RAW_COLUMN_WIDTHS = {220, 620};
    private static final String[] INTEGRATED_HEADERS = {
        "SKU 编码", "商品名", "规格", "单位", "请求数量", "组合装", "收件信息", "发货批次", "京东出库单", "运单号"
    };
    private static final int[] INTEGRATED_COLUMN_WIDTHS = {120, 200, 170, 60, 82, 88, 320, 150, 150, 180};
    static final String TRUNCATED_NOTICE = "已截断，完整数据见系统";

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;
    private final PendingListImageRenderer imageRenderer;
    private final ObjectMapper objectMapper;

    public ShipmentResultCardSource(
            JdbcTemplate jdbc,
            WecomBusinessCardRouteProperties routes,
            CardDeepLinks links,
            PendingListImageRenderer imageRenderer,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
        this.imageRenderer = imageRenderer;
        this.objectMapper = objectMapper;
    }

    @Override
    public String domain() {
        return ShipmentResultCard.DOMAIN;
    }

    /** 跟随 preship 的会话，且同样只进单聊。 */
    @Override
    public Optional<Route> route(long entityId) {
        Optional<Route> configured = routes.resolve(PreShipConfirmCard.DOMAIN);
        return configured.filter(route -> route.type() == RouteType.SINGLE);
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        List<ShipmentResultCard.View> rows = jdbc.query(
                """
                SELECT o.id, o.lock_version, o.source_channel, o.source_ref, o.receiver_name,
                       jo.jd_delivery_no,
                       t.tracking_number,
                       t.logistics_company_name
                FROM app.orders o
                JOIN app.shipments s              ON s.order_id = o.id
                JOIN app.shipment_jd_outbounds jo ON jo.shipment_id = s.id
                LEFT JOIN app.trackings t         ON t.shipment_id = s.id
                WHERE o.id = ?
                  AND o.lock_version = ?
                  AND jo.sync_status = 'SUBMITTED'
                ORDER BY s.id DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new ShipmentResultCard.View(
                        rs.getLong("id"),
                        rs.getLong("lock_version"),
                        rs.getString("source_channel"),
                        rs.getString("source_ref"),
                        rs.getString("receiver_name"),
                        rs.getString("jd_delivery_no"),
                        rs.getString("tracking_number"),
                        rs.getString("logistics_company_name"),
                        links.of("/fulfillment/shipments?order_no=" + rs.getString("source_ref"))),
                entityId,
                entityVersion);
        // 尚未建单成功 / 版本已推进：没有结果可播报
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(ShipmentResultCard.render(rows.getFirst()));
    }

    /**
     * 发货核对图：原始文件行与系统实际发货口径。
     * 与卡面使用同一版本门禁，事实已变时不生成旧图。
     */
    @Override
    public List<Attachment> attachments(long entityId, long entityVersion) {
        Optional<Route> configured = routes.resolve(PreShipConfirmCard.DOMAIN);
        // 图中有手机号和详细地址；即使将来放宽 route()，附件也绝不能进群。
        if (configured.isEmpty() || configured.get().type() != RouteType.SINGLE) {
            return List.of();
        }
        if (!isCurrentShipmentFact(entityId, entityVersion)) {
            return List.of();
        }

        List<Attachment> attachments = new ArrayList<>(2);
        List<RawImportRow> rawRows = rawImportRows(entityId);
        List<String[]> rawTableRows = rawTableRows(rawRows);
        if (!rawTableRows.isEmpty()) {
            RawImportRow first = rawRows.getFirst();
            byte[] rawImage = imageRenderer.render(
                    "原始文件 · " + BatchPreShipConfirmCard.channelLabel(first.sourceChannel())
                            + " · " + first.originalFileName(),
                    RAW_HEADERS,
                    rawTableRows,
                    RAW_COLUMN_WIDTHS);
            attachments.add(imageAttachment("原始文件订单.png", rawImage));
        }

        List<IntegratedRow> integratedRows = integratedRows(entityId);
        if (!integratedRows.isEmpty()) {
            byte[] integratedImage = imageRenderer.render(
                    "系统整合后 · 实际发货口径",
                    INTEGRATED_HEADERS,
                    integratedRows.stream().map(IntegratedRow::cells).toList(),
                    INTEGRATED_COLUMN_WIDTHS);
            attachments.add(imageAttachment("系统整合后.png", integratedImage));
        }
        return List.copyOf(attachments);
    }

    private boolean isCurrentShipmentFact(long orderId, long orderVersion) {
        Boolean current = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM app.orders o
                    JOIN app.shipments s              ON s.order_id = o.id
                    JOIN app.shipment_jd_outbounds jo ON jo.shipment_id = s.id
                    WHERE o.id = ?
                      AND o.lock_version = ?
                      AND jo.sync_status = 'SUBMITTED'
                )
                """,
                Boolean.class,
                orderId,
                orderVersion);
        return Boolean.TRUE.equals(current);
    }

    private List<RawImportRow> rawImportRows(long orderId) {
        return jdbc.query(
                """
                SELECT r.sheet_name, r.row_index, r.raw_cells::text AS raw_cells,
                       o.source_channel, b.original_file_name
                FROM app.raw_import_rows r
                JOIN app.orders o         ON o.id = r.order_id
                JOIN app.import_batches b ON b.id = r.import_batch_id
                WHERE r.order_id = ?
                ORDER BY r.sheet_index, r.row_index, r.id
                """,
                (rs, rowNum) -> new RawImportRow(
                        rs.getString("sheet_name"),
                        rs.getInt("row_index"),
                        parseRawCells(rs.getString("raw_cells")),
                        rs.getString("source_channel"),
                        rs.getString("original_file_name")),
                orderId);
    }

    private JsonNode parseRawCells(String rawCells) {
        try {
            return objectMapper.readTree(rawCells);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("原始文件行 JSON 解析失败", ex);
        }
    }

    private static List<String[]> rawTableRows(List<RawImportRow> rawRows) {
        List<String[]> rows = new ArrayList<>();
        int groupNo = 1;
        for (RawImportRow rawRow : rawRows) {
            List<String[]> fields = nonEmptyFields(rawRow.rawCells());
            if (fields.isEmpty()) {
                continue;
            }
            rows.add(new String[] {
                "原始行 " + groupNo++ + " · " + rawRow.sheetName() + " 第" + rawRow.rowIndex() + "行", ""
            });
            rows.addAll(fields);
        }
        return limitRawTableRows(rows);
    }

    static List<String[]> limitRawTableRows(List<String[]> rows) {
        if (rows.size() <= RAW_TABLE_ROW_LIMIT) {
            return rows;
        }
        List<String[]> limited = new ArrayList<>(rows.subList(0, RAW_TABLE_ROW_LIMIT - 1));
        limited.add(new String[] {TRUNCATED_NOTICE, ""});
        return limited;
    }

    private static List<String[]> nonEmptyFields(JsonNode rawCells) {
        List<String[]> fields = new ArrayList<>();
        if (rawCells != null && rawCells.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iterator = rawCells.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> field = iterator.next();
                nonEmptyValue(field.getValue()).ifPresent(value ->
                        fields.add(new String[] {field.getKey(), value}));
            }
        } else if (rawCells != null && rawCells.isArray()) {
            for (int index = 0; index < rawCells.size(); index++) {
                int columnNo = index + 1;
                nonEmptyValue(rawCells.get(index)).ifPresent(value ->
                        fields.add(new String[] {"第" + columnNo + "列", value}));
            }
        }
        return fields;
    }

    private static Optional<String> nonEmptyValue(JsonNode value) {
        if (value == null || value.isNull() || (value.isContainerNode() && value.isEmpty())) {
            return Optional.empty();
        }
        String text = value.isTextual() ? value.textValue() : value.toString();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private List<IntegratedRow> integratedRows(long orderId) {
        return jdbc.query(
                """
                SELECT l.sku_code_snapshot, l.product_name_snapshot, l.specification_snapshot,
                       l.unit_snapshot, l.requested_quantity, l.line_type,
                       o.receiver_name, o.receiver_phone, o.receiver_address,
                       shipped.shipment_no, shipped.jd_delivery_no, shipped.tracking_number
                FROM app.orders o
                JOIN app.order_lines l ON l.order_id = o.id
                JOIN LATERAL (
                    SELECT s.shipment_no, jo.jd_delivery_no, t.tracking_number
                    FROM app.shipments s
                    JOIN app.shipment_jd_outbounds jo ON jo.shipment_id = s.id
                    LEFT JOIN app.trackings t         ON t.shipment_id = s.id
                    WHERE s.order_id = o.id AND jo.sync_status = 'SUBMITTED'
                    ORDER BY s.id DESC
                    LIMIT 1
                ) shipped ON TRUE
                WHERE o.id = ?
                ORDER BY l.line_no
                """,
                (rs, rowNum) -> new IntegratedRow(
                        display(rs.getString("sku_code_snapshot")),
                        rs.getString("product_name_snapshot"),
                        rs.getString("specification_snapshot"),
                        rs.getString("unit_snapshot"),
                        PreShipConfirmCardSource.countText(rs.getInt("requested_quantity")),
                        "CUSTOM_BUNDLE".equals(rs.getString("line_type")) ? "组合装" : "普通商品",
                        rs.getString("receiver_name") + " / " + rs.getString("receiver_phone")
                                + " / " + rs.getString("receiver_address"),
                        rs.getString("shipment_no"),
                        display(rs.getString("jd_delivery_no")),
                        display(rs.getString("tracking_number"))),
                orderId);
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static Attachment imageAttachment(String filename, byte[] content) {
        if (content.length >= WecomMediaType.IMAGE.maxSizeBytes()) {
            throw new IllegalStateException(filename + " 超过企业微信图片 10MB 上限");
        }
        return new Attachment(filename, content, WecomMediaType.IMAGE);
    }

    /**
     * 运单回填后自动播报：这是闭环真正的最后一步，而且它由轮询触发，
     * 没有任何人的点击可以挂钩——不扫描就永远不会有人被告知运单到了。
     */
    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT o.id, o.lock_version
                FROM app.orders o
                JOIN app.shipments s              ON s.order_id = o.id
                JOIN app.shipment_jd_outbounds jo ON jo.shipment_id = s.id
                JOIN app.trackings t              ON t.shipment_id = s.id
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'shipped'
                      AND c.entity_id = o.id
                      AND c.entity_version = o.lock_version
                WHERE jo.sync_status = 'SUBMITTED'
                  AND t.received_at >= ?
                  AND c.id IS NULL
                ORDER BY t.received_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        ShipmentResultCard.DOMAIN, rs.getLong("id"), rs.getLong("lock_version")),
                since,
                limit);
    }

    private record RawImportRow(
            String sheetName,
            int rowIndex,
            JsonNode rawCells,
            String sourceChannel,
            String originalFileName) {}

    private record IntegratedRow(
            String skuCode,
            String productName,
            String specification,
            String unit,
            String requestedQuantity,
            String bundleMarker,
            String receiver,
            String shipmentNo,
            String jdDeliveryNo,
            String trackingNo) {

        String[] cells() {
            return new String[] {
                skuCode,
                productName,
                specification,
                unit,
                requestedQuantity,
                bundleMarker,
                receiver,
                shipmentNo,
                jdDeliveryNo,
                trackingNo
            };
        }
    }
}
