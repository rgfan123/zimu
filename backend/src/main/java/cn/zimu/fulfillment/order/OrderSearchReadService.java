package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.dto.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import cn.zimu.fulfillment.order.dto.OrderSearchSummaryDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单检索只读服务（MCP {@code search_orders} 专用）：与 {@link OrderQueryService#list} 分开——
 * 后者服务管理台列表（模糊匹配订单号/来源单号/客户名），本服务模糊匹配渠道单号/收件人姓名，
 * 并聚合行数/总件数/最近一次 Shipment 发货进度，管理台列表不需要这些聚合。
 *
 * <p>不返回收货人电话/详细地址；姓名可返回（业务靠姓名认单），与
 * {@code McpDomainReadTools#checkShipmentSourceSync} 的脱敏尺度一致。
 */
@Service
public class OrderSearchReadService {

    private static final String BASE_FROM = """
            FROM app.orders o
            LEFT JOIN app.v_import_batch_effective_source source
              ON source.import_batch_id = o.source_import_batch_id
            """;

    private final JdbcTemplate jdbc;

    public OrderSearchReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSearchSummaryDto> search(OrderSearchQuery query) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE o.data_scope = 'BUSINESS'");
        if (query.query() != null && !query.query().isBlank()) {
            where.append(" AND (o.source_ref ILIKE ? OR o.receiver_name ILIKE ?)");
            String like = "%" + query.query().trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (query.sourceChannel() != null) {
            where.append(" AND COALESCE(source.effective_source_channel, o.source_channel) = ?");
            args.add(query.sourceChannel().name());
        }
        if (query.orderStatus() != null) {
            where.append(" AND o.order_status = ?");
            args.add(query.orderStatus().name());
        }
        if (query.dateFrom() != null) {
            where.append(" AND COALESCE(o.source_ordered_at, o.created_at) >= ?");
            args.add(Timestamp.from(query.dateFrom()));
        }
        if (query.dateTo() != null) {
            where.append(" AND COALESCE(o.source_ordered_at, o.created_at) < ?");
            args.add(Timestamp.from(query.dateTo()));
        }

        long total = jdbc.queryForObject("SELECT count(*) " + BASE_FROM + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add((long) query.page() * query.size());

        List<OrderSearchSummaryDto> items = jdbc.query(
                """
                SELECT o.id, o.order_no,
                       COALESCE(source.effective_source_channel, o.source_channel) AS source_channel,
                       o.source_ref, o.receiver_name, o.order_status,
                       o.source_ordered_at, o.settlement_time,
                       COALESCE(lines.line_count, 0) AS line_count,
                       COALESCE(lines.total_quantity, 0) AS total_quantity,
                       ship.id AS shipment_id, ship.shipment_status,
                       track.tracking_number, track.logistics_company_name
                """
                        + BASE_FROM
                        + """
                LEFT JOIN (
                    SELECT order_id, COUNT(*) AS line_count, SUM(requested_quantity) AS total_quantity
                    FROM app.order_lines
                    GROUP BY order_id
                ) lines ON lines.order_id = o.id
                LEFT JOIN LATERAL (
                    SELECT s.id, s.shipment_status
                    FROM app.shipments s
                    WHERE s.order_id = o.id
                    ORDER BY s.shipment_sequence DESC, s.id DESC
                    LIMIT 1
                ) ship ON true
                LEFT JOIN app.trackings track ON track.shipment_id = ship.id
                """
                        + where
                        + " ORDER BY o.created_at DESC, o.id DESC LIMIT ? OFFSET ?",
                SUMMARY_ROW_MAPPER,
                pageArgs.toArray());
        return new PageResponse<>(
                items, query.page(), query.size(), total, (int) Math.ceil((double) total / query.size()));
    }

    private static final RowMapper<OrderSearchSummaryDto> SUMMARY_ROW_MAPPER = (rs, rowNum) -> new OrderSearchSummaryDto(
            String.valueOf(rs.getLong("id")),
            rs.getString("order_no"),
            rs.getString("source_channel"),
            rs.getString("source_ref"),
            rs.getString("receiver_name"),
            rs.getString("order_status"),
            toInstant(rs, "source_ordered_at"),
            toInstant(rs, "settlement_time"),
            rs.getInt("line_count"),
            rs.getLong("total_quantity"),
            hasValue(rs, "shipment_id"),
            rs.getString("shipment_status"),
            rs.getString("tracking_number"),
            rs.getString("logistics_company_name"));

    private static boolean hasValue(ResultSet rs, String column) throws SQLException {
        rs.getObject(column);
        return !rs.wasNull();
    }

    private static String decimal(Object value) {
        return value == null ? null : new java.math.BigDecimal(value.toString()).toPlainString();
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
