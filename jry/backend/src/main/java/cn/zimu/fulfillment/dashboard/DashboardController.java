package cn.zimu.fulfillment.dashboard;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工作台只读投影：今日 KPI、七日趋势与当前人工介入原因。 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public DashboardSummary summary(
            @RequestParam(name = "business_date", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate businessDate) {
        LocalDate effectiveDate = businessDate == null ? LocalDate.now(SHANGHAI) : businessDate;
        List<TrendPoint> trend = trend(effectiveDate);
        TrendPoint today = trend.get(trend.size() - 1);
        Long pendingReviewCount = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM app.review_cases rc
                JOIN app.orders o ON o.id = rc.order_id AND o.data_scope = 'BUSINESS'
                WHERE rc.status = 'OPEN'
                """,
                Long.class);
        return new DashboardSummary(
                effectiveDate,
                today.orderCount(),
                today.shippedOrderCount(),
                pendingReviewCount == null ? 0 : pendingReviewCount,
                trend,
                attention());
    }

    private List<TrendPoint> trend(LocalDate businessDate) {
        return jdbc.query(
                """
                WITH dates AS (
                    SELECT generate_series(
                        CAST(? AS date) - INTERVAL '6 days',
                        CAST(? AS date),
                        INTERVAL '1 day'
                    )::date AS business_date
                ), order_counts AS (
                    SELECT (o.created_at AT TIME ZONE 'Asia/Shanghai')::date AS business_date,
                           count(*)::bigint AS order_count
                    FROM app.orders o
                    WHERE o.data_scope = 'BUSINESS'
                      AND (o.created_at AT TIME ZONE 'Asia/Shanghai')::date
                          BETWEEN CAST(? AS date) - 6 AND CAST(? AS date)
                    GROUP BY 1
                ), shipment_counts AS (
                    SELECT (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::date AS business_date,
                           count(DISTINCT s.order_id)::bigint AS shipped_order_count
                    FROM app.shipments s
                    JOIN app.orders o ON o.id = s.order_id AND o.data_scope = 'BUSINESS'
                    WHERE s.shipment_status IN ('SHIPPED', 'DELIVERED')
                      AND (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::date
                          BETWEEN CAST(? AS date) - 6 AND CAST(? AS date)
                    GROUP BY 1
                )
                SELECT d.business_date::text,
                       COALESCE(oc.order_count, 0),
                       COALESCE(sc.shipped_order_count, 0)
                FROM dates d
                LEFT JOIN order_counts oc USING (business_date)
                LEFT JOIN shipment_counts sc USING (business_date)
                ORDER BY d.business_date
                """,
                (rs, rowNum) -> new TrendPoint(
                        LocalDate.parse(rs.getString(1)), rs.getLong(2), rs.getLong(3)),
                businessDate,
                businessDate,
                businessDate,
                businessDate,
                businessDate,
                businessDate);
    }

    private List<AttentionItem> attention() {
        return jdbc.query(
                """
                WITH attention_items AS (
                    SELECT rc.reason_code, rc.order_id, 'RED'::text AS severity
                    FROM app.review_cases rc
                    JOIN app.orders o ON o.id = rc.order_id AND o.data_scope = 'BUSINESS'
                    WHERE rc.status = 'OPEN'
                    UNION ALL
                    SELECT oa.alert_type AS reason_code, oa.order_id, oa.severity
                    FROM app.operational_alerts oa
                    JOIN app.orders o ON o.id = oa.order_id AND o.data_scope = 'BUSINESS'
                    WHERE oa.status IN ('OPEN', 'ACKNOWLEDGED')
                )
                SELECT reason_code,
                       count(DISTINCT order_id)::bigint,
                       CASE WHEN bool_or(severity = 'RED') THEN 'RED' ELSE 'YELLOW' END
                FROM attention_items
                GROUP BY reason_code
                ORDER BY bool_or(severity = 'RED') DESC, count(DISTINCT order_id) DESC, reason_code
                """,
                (rs, rowNum) -> new AttentionItem(rs.getString(1), rs.getLong(2), rs.getString(3)));
    }

    public record DashboardSummary(
            LocalDate businessDate,
            long orderCount,
            long shippedOrderCount,
            long pendingReviewCount,
            List<TrendPoint> trend,
            List<AttentionItem> attention) {}

    public record TrendPoint(LocalDate businessDate, long orderCount, long shippedOrderCount) {}

    public record AttentionItem(String reasonCode, long count, String severity) {}
}
