package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BUSINESS 履约、发货、物流与采购聚合查询。 */
@Service
public class FulfillmentReadService {

    private static final String FULFILLMENT_FROM = """
            FROM app.fulfillments f
            JOIN app.order_lines ol ON ol.id = f.order_line_id
            JOIN app.orders o ON o.id = ol.order_id
            LEFT JOIN app.customers c ON c.id = o.customer_id
            WHERE o.data_scope = 'BUSINESS'
            """;
    private static final String SHIPMENT_FROM = """
            FROM app.shipments s
            JOIN app.orders o ON o.id = s.order_id
            LEFT JOIN app.customers c ON c.id = o.customer_id
            WHERE o.data_scope = 'BUSINESS'
            """;
    private static final String TICKET_FROM = """
            FROM app.procurement_tickets pt
            JOIN app.fulfillments f ON f.id = pt.fulfillment_id
            JOIN app.order_lines ol ON ol.id = f.order_line_id
            JOIN app.orders o ON o.id = ol.order_id
            WHERE o.data_scope = 'BUSINESS'
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    public FulfillmentReadService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> fulfillments(
            int page, int size, Instant dateFrom, Instant dateTo, Long providerId, String progress, String outcome) {
        List<Object> args = new ArrayList<>();
        String filters = filters(args, dateFrom, dateTo, providerId, progress, outcome,
                "f.created_at", "f.fulfillment_provider_id", "f.shipping_progress", "f.outcome");
        long total = count(FULFILLMENT_FROM + filters, args);
        List<Object> pageArgs = pageArgs(args, page, size);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT f.*, o.order_no, o.receiver_name, c.customer_name " + FULFILLMENT_FROM + filters
                        + " ORDER BY f.created_at DESC, f.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> fulfillment(rs), pageArgs.toArray());
        return page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> fulfillment(long id) {
        Map<String, Object> value = jdbc.query(
                "SELECT f.*, o.order_no, o.receiver_name, c.customer_name " + FULFILLMENT_FROM + " AND f.id = ?",
                rs -> rs.next() ? fulfillment(rs) : null,
                id);
        if (value == null) throw BusinessException.notFound("履约任务不存在");
        value.put("shipments", shipmentsForFulfillment(id));
        value.put("procurement_tickets", ticketsForFulfillment(id));
        return value;
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> shipments(
            int page, int size, Instant dateFrom, Instant dateTo, Long providerId, String status) {
        List<Object> args = new ArrayList<>();
        String filters = filters(args, dateFrom, dateTo, providerId, status, null,
                "s.created_at", "s.fulfillment_provider_id", "s.shipment_status", "s.shipment_status");
        long total = count(SHIPMENT_FROM + filters, args);
        List<Object> pageArgs = pageArgs(args, page, size);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT s.*, o.order_no, o.receiver_name, c.customer_name " + SHIPMENT_FROM + filters
                        + " ORDER BY s.created_at DESC, s.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> shipment(rs), pageArgs.toArray());
        items.forEach(this::hydrateShipment);
        return page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> shipment(long id) {
        Map<String, Object> value = jdbc.query(
                "SELECT s.*, o.order_no, o.receiver_name, c.customer_name " + SHIPMENT_FROM + " AND s.id = ?",
                rs -> rs.next() ? shipment(rs) : null,
                id);
        if (value == null) throw BusinessException.notFound("发货单不存在");
        hydrateShipment(value);
        return value;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> orderShipments(long orderId) {
        boolean exists = Boolean.TRUE.equals(jdbc.query(
                "SELECT true FROM app.orders WHERE id=? AND data_scope='BUSINESS'",
                rs -> rs.next() ? Boolean.TRUE : Boolean.FALSE,
                orderId));
        if (!exists) throw BusinessException.notFound("订单不存在");
        List<Map<String, Object>> result = jdbc.query(
                "SELECT s.*, o.order_no, o.receiver_name, c.customer_name " + SHIPMENT_FROM
                        + " AND s.order_id=? ORDER BY s.shipment_sequence, s.id",
                (rs, row) -> shipment(rs), orderId);
        result.forEach(this::hydrateShipment);
        return result.stream().map(this::orderShipmentView).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> tickets(
            int page, int size, Instant dateFrom, Instant dateTo, String status) {
        List<Object> args = new ArrayList<>();
        String filters = filters(args, dateFrom, dateTo, null, status, null,
                "pt.created_at", "pt.fulfillment_id", "pt.procurement_status", "pt.procurement_status");
        long total = count(TICKET_FROM + filters, args);
        List<Object> pageArgs = pageArgs(args, page, size);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT pt.* " + TICKET_FROM + filters + " ORDER BY pt.created_at DESC, pt.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> ticket(rs), pageArgs.toArray());
        items.forEach(this::hydrateTicket);
        return page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ticket(long id) {
        Map<String, Object> value = jdbc.query(
                "SELECT pt.* " + TICKET_FROM + " AND pt.id = ?",
                rs -> rs.next() ? ticket(rs) : null,
                id);
        if (value == null) throw BusinessException.notFound("采购工单不存在");
        hydrateTicket(value);
        return value;
    }

    /** 采购工单关联订单行投影（MCP get_procurement_ticket 白名单），与 TICKET_FROM 相同的 BUSINESS 数据范围过滤。 */
    @Transactional(readOnly = true)
    public Map<String, Object> orderLineForFulfillment(long fulfillmentId) {
        return jdbc.query(
                """
                SELECT ol.id AS order_line_id, ol.sku_id, ol.sku_code_snapshot,
                       ol.product_name_snapshot, ol.unit_snapshot
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id = f.order_line_id
                JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
                WHERE f.id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("order_line_id", id(rs.getLong("order_line_id")));
                    result.put("sku_id", nullableId(rs, "sku_id"));
                    result.put("sku_code", rs.getString("sku_code_snapshot"));
                    result.put("product_name", rs.getString("product_name_snapshot"));
                    result.put("unit", rs.getString("unit_snapshot"));
                    return result;
                },
                fulfillmentId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> receipt(long id) {
        Map<String, Object> value = jdbc.query(
                """
                SELECT pr.* FROM app.procurement_receipts pr
                JOIN app.procurement_tickets pt ON pt.id=pr.procurement_ticket_id
                JOIN app.fulfillments f ON f.id=pt.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                WHERE pr.id=?
                """,
                rs -> rs.next() ? receipt(rs) : null,
                id);
        if (value == null) throw BusinessException.notFound("采购回执不存在");
        value.put("items", receiptItems(id));
        return value;
    }

    private List<Map<String, Object>> shipmentsForFulfillment(long fulfillmentId) {
        List<Map<String, Object>> result = jdbc.query(
                """
                SELECT DISTINCT s.*, o.order_no, o.receiver_name, c.customer_name
                FROM app.shipments s
                JOIN app.shipment_items si ON si.shipment_id=s.id
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                LEFT JOIN app.customers c ON c.id=o.customer_id
                WHERE si.fulfillment_id=? ORDER BY s.shipment_sequence, s.id
                """,
                (rs, row) -> shipment(rs), fulfillmentId);
        result.forEach(this::hydrateShipment);
        return result;
    }

    private List<Map<String, Object>> ticketsForFulfillment(long fulfillmentId) {
        List<Map<String, Object>> result = jdbc.query(
                "SELECT pt.* " + TICKET_FROM + " AND pt.fulfillment_id=? ORDER BY pt.created_at, pt.id",
                (rs, row) -> ticket(rs), fulfillmentId);
        result.forEach(this::hydrateTicket);
        return result;
    }

    private void hydrateShipment(Map<String, Object> value) {
        long shipmentId = Long.parseLong(value.get("id").toString());
        value.put("items", jdbc.query(
                """
                SELECT si.fulfillment_id, f.order_line_id, ol.product_name_snapshot,
                       si.instructed_quantity, si.shipped_quantity, ol.unit_snapshot
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE si.shipment_id=? ORDER BY si.id
                """,
                (rs, row) -> map(
                        "fulfillment_id", id(rs.getLong("fulfillment_id")),
                        "order_line_id", id(rs.getLong("order_line_id")),
                        "product_name", rs.getString("product_name_snapshot"),
                        "instructed_quantity", decimal(rs, "instructed_quantity"),
                        "shipped_quantity", decimal(rs, "shipped_quantity"),
                        "unit", rs.getString("unit_snapshot")), shipmentId));
        Map<String, Object> tracking = jdbc.query(
                "SELECT * FROM app.trackings WHERE shipment_id=?",
                rs -> rs.next() ? map(
                        "id", id(rs.getLong("id")),
                        "logistics_company_code", rs.getString("logistics_company_code"),
                        "logistics_company_name", rs.getString("logistics_company_name"),
                        "tracking_number", rs.getString("tracking_number"),
                        "provider_tracking_batch_id", nullableId(rs, "provider_tracking_batch_id"),
                        "received_at", instant(rs, "received_at")) : null,
                shipmentId);
        value.put("tracking", tracking);
        // Shipment 级京东出库集成记录：商户侧出库引用、同步状态、失败阶段与重试信息；
        // 只暴露诊断字段，不含请求原文、凭据或原始 PII
        value.put("jd_outbound", jdbc.query(
                "SELECT erp_delivery_no, jd_delivery_no, sync_status, failure_phase, retry_count,\n"
                        + "       last_error_code, last_error_message, submitted_at, client_mode,\n"
                        + "       tracking_query_status, tracking_query_attempt_count,\n"
                        + "       tracking_last_query_at, tracking_last_error_code,\n"
                        + "       tracking_last_error_message, tracking_last_request_id, updated_at\n"
                        + "FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    String syncStatus = rs.getString("sync_status");
                    String lastErrorCode = rs.getString("last_error_code");
                    boolean retryable = "SYNC_FAILED".equals(syncStatus)
                            && !"RECONCILIATION_REQUIRED".equals(lastErrorCode);
                    return map(
                            "erp_delivery_no", rs.getString("erp_delivery_no"),
                            "jd_delivery_no", rs.getString("jd_delivery_no"),
                            "sync_status", syncStatus,
                            "failure_phase", rs.getString("failure_phase"),
                            "retry_count", rs.getInt("retry_count"),
                            "retryable", retryable,
                            "client_mode", rs.getString("client_mode"),
                            "last_error_code", lastErrorCode,
                            "last_error_message", rs.getString("last_error_message"),
                            "submitted_at", instant(rs, "submitted_at"),
                            "tracking_query_status", rs.getString("tracking_query_status"),
                            "tracking_query_attempt_count", rs.getInt("tracking_query_attempt_count"),
                            "tracking_last_query_at", instant(rs, "tracking_last_query_at"),
                            "tracking_last_error_code", rs.getString("tracking_last_error_code"),
                            "tracking_last_error_message", rs.getString("tracking_last_error_message"),
                            "tracking_last_request_id", rs.getString("tracking_last_request_id"),
                            "updated_at", instant(rs, "updated_at"));
                },
                shipmentId));
    }

    private void hydrateTicket(Map<String, Object> value) {
        long ticketId = Long.parseLong(value.get("id").toString());
        value.put("items", jdbc.query(
                "SELECT * FROM app.procurement_ticket_items WHERE procurement_ticket_id=? ORDER BY id",
                (rs, row) -> map(
                        "id", id(rs.getLong("id")),
                        "sku_id", id(rs.getLong("sku_id")),
                        "component_sku_id", nullableComponentSku(rs.getObject("order_line_component_id", Long.class)),
                        "requested_quantity", decimal(rs, "requested_quantity"),
                        "fulfilled_quantity", decimal(rs, "fulfilled_quantity"),
                        "remaining_quantity", decimal(rs, "remaining_quantity")), ticketId));
        List<Map<String, Object>> receipts = jdbc.query(
                "SELECT * FROM app.procurement_receipts WHERE procurement_ticket_id=? ORDER BY received_at, id",
                (rs, row) -> receipt(rs), ticketId);
        receipts.forEach(receipt -> receipt.put("items", receiptItems(Long.parseLong(receipt.get("id").toString()))));
        value.put("receipts", receipts);
    }

    private List<Map<String, Object>> receiptItems(long receiptId) {
        return jdbc.query(
                """
                SELECT procurement_ticket_item_id, available_quantity
                FROM app.procurement_receipt_items WHERE procurement_receipt_id=? ORDER BY id
                """,
                (rs, row) -> map(
                        "ticket_item_id", id(rs.getLong("procurement_ticket_item_id")),
                        "available_quantity", decimal(rs, "available_quantity")), receiptId);
    }

    private Map<String, Object> fulfillment(ResultSet rs) throws SQLException {
        return map(
                "id", id(rs.getLong("id")),
                "fulfillment_no", rs.getString("fulfillment_no"),
                "order_line_id", id(rs.getLong("order_line_id")),
                "order_no", rs.getString("order_no"),
                "customer_name", rs.getString("customer_name"),
                "receiver_name", rs.getString("receiver_name"),
                "provider_id", id(rs.getLong("fulfillment_provider_id")),
                "requested_quantity", decimal(rs, "requested_quantity"),
                "cumulative_shipped_quantity", decimal(rs, "cumulative_shipped_quantity"),
                "cancelled_quantity", decimal(rs, "cancelled_quantity"),
                "shipping_progress", rs.getString("shipping_progress"),
                "outcome", rs.getString("outcome"),
                "exception_code", rs.getString("exception_code"),
                "exception_reason", rs.getString("exception_reason"),
                "version", rs.getLong("lock_version"));
    }

    private Map<String, Object> shipment(ResultSet rs) throws SQLException {
        return map(
                "id", id(rs.getLong("id")),
                "shipment_no", rs.getString("shipment_no"),
                "order_id", id(rs.getLong("order_id")),
                "order_no", rs.getString("order_no"),
                "customer_name", rs.getString("customer_name"),
                "receiver_name", rs.getString("receiver_name"),
                "provider_id", id(rs.getLong("fulfillment_provider_id")),
                "outbound_order_no", rs.getString("outbound_order_no"),
                "shipment_sequence", rs.getInt("shipment_sequence"),
                "shipment_status", rs.getString("shipment_status"),
                "receiver", map("name", rs.getString("receiver_name_snapshot"),
                        "phone", rs.getString("receiver_phone_snapshot"),
                        "address", rs.getString("receiver_address_snapshot")),
                "shipped_at", instant(rs, "shipped_at"),
                "created_at", instant(rs, "created_at"),
                "updated_at", instant(rs, "updated_at"));
    }

    /** 订单详情只返回履约事实白名单；Shipment 管理接口仍保留完整收件人快照。 */
    private Map<String, Object> orderShipmentView(Map<String, Object> shipment) {
        Object jdValue = shipment.get("jd_outbound");
        Map<?, ?> jd = jdValue instanceof Map<?, ?> value ? value : null;
        return map(
                "id", shipment.get("id"),
                "shipment_no", shipment.get("shipment_no"),
                "order_id", shipment.get("order_id"),
                "provider_id", shipment.get("provider_id"),
                "outbound_order_no", shipment.get("outbound_order_no"),
                "shipment_sequence", shipment.get("shipment_sequence"),
                "shipment_status", shipment.get("shipment_status"),
                "items", shipment.get("items"),
                "tracking", shipment.get("tracking"),
                "jd_outbound", jd == null ? null : map(
                        "erp_delivery_no", jd.get("erp_delivery_no"),
                        "jd_delivery_no", jd.get("jd_delivery_no"),
                        "sync_status", jd.get("sync_status"),
                        "failure_phase", jd.get("failure_phase"),
                        "tracking_query_status", jd.get("tracking_query_status"),
                        "updated_at", jd.get("updated_at")),
                "shipped_at", shipment.get("shipped_at"),
                "created_at", shipment.get("created_at"),
                "updated_at", shipment.get("updated_at"));
    }

    private Map<String, Object> ticket(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        Map<String, Object> totals = jdbc.queryForMap(
                """
                SELECT COALESCE(sum(requested_quantity),0) requested,
                       COALESCE(sum(fulfilled_quantity),0) fulfilled,
                       COALESCE(sum(remaining_quantity),0) remaining
                FROM app.procurement_ticket_items WHERE procurement_ticket_id=?
                """, id);
        return map(
                "id", id(id),
                "ticket_no", rs.getString("ticket_no"),
                "fulfillment_id", id(rs.getLong("fulfillment_id")),
                "retry_of_ticket_id", nullableId(rs, "retry_of_ticket_id"),
                "status", rs.getString("procurement_status"),
                "requested_quantity", decimal(totals.get("requested")),
                "fulfilled_quantity", decimal(totals.get("fulfilled")),
                "remaining_quantity", decimal(totals.get("remaining")),
                "version", rs.getLong("lock_version"),
                "created_at", instant(rs, "created_at"));
    }

    private Map<String, Object> receipt(ResultSet rs) throws SQLException {
        return map(
                "id", id(rs.getLong("id")),
                "receipt_no", rs.getString("receipt_no"),
                "ticket_id", id(rs.getLong("procurement_ticket_id")),
                "result", rs.getString("result"),
                "expected_ship_time", instant(rs, "expected_ship_time"),
                "source_ref", rs.getString("source_ref"),
                "remark", rs.getString("remark"),
                "received_by", rs.getString("received_by"),
                "received_at", instant(rs, "received_at"));
    }

    private String nullableComponentSku(Long componentId) {
        if (componentId == null) return null;
        Long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.order_line_components WHERE id=?", Long.class, componentId);
        return skuId == null ? null : id(skuId);
    }

    private long count(String from, List<Object> args) {
        return jdbc.queryForObject("SELECT count(*) " + from, Long.class, args.toArray());
    }

    private static String filters(
            List<Object> args, Instant from, Instant to, Long providerId, String state, String outcome,
            String dateColumn, String providerColumn, String stateColumn, String outcomeColumn) {
        StringBuilder sql = new StringBuilder();
        if (from != null) { sql.append(" AND ").append(dateColumn).append(" >= ?"); args.add(java.sql.Timestamp.from(from)); }
        if (to != null) { sql.append(" AND ").append(dateColumn).append(" < ?"); args.add(java.sql.Timestamp.from(to)); }
        if (providerId != null) { sql.append(" AND ").append(providerColumn).append(" = ?"); args.add(providerId); }
        if (state != null) { sql.append(" AND ").append(stateColumn).append(" = ?"); args.add(state); }
        if (outcome != null) { sql.append(" AND ").append(outcomeColumn).append(" = ?"); args.add(outcome); }
        return sql.toString();
    }

    private static List<Object> pageArgs(List<Object> args, int page, int size) {
        List<Object> result = new ArrayList<>(args);
        result.add(size);
        result.add((long) page * size);
        return result;
    }

    private static <T> PageResponse<T> page(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    private static String decimal(ResultSet rs, String column) throws SQLException {
        return decimal(rs.getBigDecimal(column));
    }

    private static String decimal(Object value) {
        return value == null ? null : new java.math.BigDecimal(value.toString()).toPlainString();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String nullableId(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : id(value);
    }

    private static String id(long id) { return String.valueOf(id); }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    @SuppressWarnings("unused")
    private Object json(Object value) {
        try {
            String json = value instanceof PGobject pg ? pg.getValue() : String.valueOf(value);
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            throw new IllegalStateException("数据库 JSON 解析失败", ex);
        }
    }
}
