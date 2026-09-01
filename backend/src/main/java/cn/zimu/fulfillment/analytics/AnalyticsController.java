package cn.zimu.fulfillment.analytics;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BUSINESS-only daily analytics backed by the authoritative analytics views. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AnalyticsController(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/channels")
    public List<Map<String, Object>> channels(
            @RequestParam(name = "date_from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateTo,
            @RequestParam(name = "source_channel", required = false) SourceChannel sourceChannel) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT metric_date::text AS metric_date, source_channel, order_count, order_line_count,
                       actual_shipped_quantity AS actual_shipped_quantity,
                       actual_shipped_quantity AS canonical_quantity,
                       actual_shipped_quantity AS shipped_quantity,
                       shipment_count, exception_order_count, out_of_stock_order_count, sync_failed_count
                FROM analytics.v_channel_daily
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        addDateFilters(sql, args, dateFrom, dateTo);
        addSourceChannelFilter(sql, args, "source_channel", sourceChannel);
        sql.append(" ORDER BY metric_date, source_channel");
        return normalizeHistoricalSourceChannel(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(
            @RequestParam(name = "date_from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateTo,
            @RequestParam(name = "source_channel", required = false) SourceChannel sourceChannel,
            @RequestParam(name = "product_id", required = false) Long productId,
            @RequestParam(name = "sku_id", required = false) Long skuId,
            @RequestParam(name = "category_id", required = false) Long categoryId) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT v.metric_date::text AS metric_date, v.source_channel,
                       v.category_id::text AS category_id, v.category_code, v.category_name,
                       v.product_id::text AS product_id, v.product_code, v.product_name,
                       v.sku_id::text AS sku_id, v.sku_code, v.product_name AS sku_name,
                       v.order_count, v.shipment_count,
                       v.actual_shipped_quantity AS actual_shipped_quantity,
                       v.actual_shipped_quantity AS canonical_quantity,
                       v.actual_shipped_quantity AS shipped_quantity,
                       COALESCE(sm.source_mappings, '[]'::jsonb)::text AS source_mappings_json,
                       COALESCE(jd.jd_sku_codes, '[]'::jsonb)::text AS jd_sku_codes_json
                FROM analytics.v_product_daily v
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(
                               jsonb_build_object(
                                   'source_sku_ref', scs.source_sku_ref,
                                   'source_product_name', scs.source_product_name,
                                   'source_specification', scs.source_specification,
                                   'quantity_multiplier', scs.quantity_multiplier
                               ) ORDER BY scs.source_sku_ref
                           ) AS source_mappings
                    FROM app.source_channel_skus scs
                    WHERE scs.source_channel = v.source_channel
                      AND scs.sku_id = v.sku_id
                      AND scs.active
                ) sm ON TRUE
                LEFT JOIN LATERAL (
                    SELECT jsonb_agg(ps.provider_sku_code ORDER BY ps.provider_sku_code) AS jd_sku_codes
                    FROM app.provider_skus ps
                    JOIN app.fulfillment_providers fp
                      ON fp.id = ps.fulfillment_provider_id
                     AND fp.provider_type = 'JD_WAREHOUSE'
                    WHERE ps.sku_id = v.sku_id
                      AND ps.active
                      AND fp.active
                ) jd ON TRUE
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        addDateFilters(sql, args, dateFrom, dateTo);
        addSourceChannelFilter(sql, args, "v.source_channel", sourceChannel);
        addEqualFilter(sql, args, "product_id", productId);
        addEqualFilter(sql, args, "sku_id", skuId);
        addEqualFilter(sql, args, "category_id", categoryId);
        sql.append(" ORDER BY metric_date, source_channel, product_id, sku_id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        rows.forEach(row -> {
            normalizeHistoricalSourceChannel(row);
            row.put("source_mappings", parseJsonArray((String) row.remove("source_mappings_json")));
            row.put("jd_sku_codes", parseJsonArray((String) row.remove("jd_sku_codes_json")));
        });
        return rows;
    }

    @GetMapping("/fulfillments")
    public List<Map<String, Object>> fulfillments(
            @RequestParam(name = "date_from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate dateTo,
            @RequestParam(name = "source_channel", required = false) SourceChannel sourceChannel,
            @RequestParam(name = "provider_id", required = false) Long providerId) {
        StringBuilder sql = new StringBuilder(
                """
                WITH tracking_metrics AS (
                    SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai')::date AS metric_date,
                           o.source_channel,
                           s.fulfillment_provider_id,
                           count(DISTINCT t.shipment_id)::bigint AS tracking_received_count,
                           COALESCE(avg(GREATEST(
                               extract(epoch FROM (t.received_at - s.shipped_at)) / 3600.0,
                               0
                           )), 0)::double precision AS average_tracking_hours
                    FROM app.shipments s
                    JOIN app.orders o ON o.id = s.order_id AND o.data_scope = 'BUSINESS'
                    JOIN app.trackings t ON t.shipment_id = s.id
                    WHERE s.shipped_at IS NOT NULL
                    GROUP BY 1, 2, 3
                )
                SELECT v.metric_date::text AS metric_date, v.source_channel,
                       fp.id::text AS provider_id,
                       v.provider_code, v.provider_name, v.provider_type,
                       v.fulfillment_count, v.fulfilled_quantity AS fulfilled_quantity,
                       v.not_shipped_count, v.partially_shipped_count, v.fully_shipped_count,
                       v.procurement_ticket_count, v.out_of_stock_fulfillment_count,
                       v.awaiting_shipment_count, v.shipped_shipment_count,
                       v.awaiting_tracking_count, v.awaiting_sync_count, v.sync_failed_count,
                       v.synced_count,
                       v.shipped_shipment_count AS shipment_count,
                       v.fulfilled_quantity AS shipped_quantity,
                       COALESCE(tm.tracking_received_count, 0) AS tracking_received_count,
                       COALESCE(tm.average_tracking_hours, 0.0) AS average_tracking_hours
                FROM analytics.v_fulfillment_channel_daily v
                JOIN app.fulfillment_providers fp ON fp.provider_code = v.provider_code
                LEFT JOIN tracking_metrics tm
                 ON tm.metric_date = v.metric_date
                 AND tm.source_channel = v.source_channel
                 AND tm.fulfillment_provider_id = fp.id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        addDateFilters(sql, args, dateFrom, dateTo, "v.metric_date");
        addSourceChannelFilter(sql, args, "v.source_channel", sourceChannel);
        if (providerId != null) {
            sql.append(" AND fp.id = ?");
            args.add(providerId);
        }
        sql.append(" ORDER BY v.metric_date, v.source_channel, v.provider_code");
        return normalizeHistoricalSourceChannel(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    private static void addSourceChannelFilter(
            StringBuilder sql, List<Object> args, String column, SourceChannel sourceChannel) {
        if (sourceChannel == null) {
            return;
        }
        if (sourceChannel == SourceChannel.DAZHE) {
            sql.append(" AND ").append(column).append(" IN (?, ?)");
            args.add(SourceChannel.DAZHE.name());
            args.add(SourceChannel.WANGQI.name());
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        args.add(sourceChannel.name());
    }

    private static List<Map<String, Object>> normalizeHistoricalSourceChannel(List<Map<String, Object>> rows) {
        rows.forEach(AnalyticsController::normalizeHistoricalSourceChannel);
        return rows;
    }

    private static void normalizeHistoricalSourceChannel(Map<String, Object> row) {
        if (SourceChannel.WANGQI.name().equals(row.get("source_channel"))) {
            row.put("source_channel", SourceChannel.DAZHE.name());
        }
    }

    private static void addDateFilters(
            StringBuilder sql, List<Object> args, LocalDate dateFrom, LocalDate dateTo) {
        addDateFilters(sql, args, dateFrom, dateTo, "metric_date");
    }

    private static void addDateFilters(
            StringBuilder sql, List<Object> args, LocalDate dateFrom, LocalDate dateTo, String column) {
        if (dateFrom != null) {
            sql.append(" AND ").append(column).append(" >= ?");
            args.add(dateFrom);
        }
        if (dateTo != null) {
            sql.append(" AND ").append(column).append(" <= ?");
            args.add(dateTo);
        }
    }

    private static void addEqualFilter(StringBuilder sql, List<Object> args, String column, Long value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private List<Object> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("analytics JSON projection is invalid", exception);
        }
    }
}
