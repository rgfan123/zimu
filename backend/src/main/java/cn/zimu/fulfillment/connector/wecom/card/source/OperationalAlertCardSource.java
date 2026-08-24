package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.OperationalAlertCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 运营告警卡来源：从 {@code app.operational_alerts} 按当前事实渲染。
 *
 * <p>只有 {@code status='OPEN'} 且 {@code lock_version} 未推进才发卡——
 * 已被人 acknowledge 的告警再推一张卡出去，就是在制造重复劳动。
 */
@Service
public class OperationalAlertCardSource implements WecomBusinessCardSource {

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public OperationalAlertCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return OperationalAlertCard.DOMAIN;
    }

    @Override
    public Optional<Route> route(long entityId) {
        return routes.resolve(domain());
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        List<OperationalAlertCard.View> rows = jdbc.query(
                """
                SELECT a.id, a.alert_no, a.severity, a.message, a.lock_version,
                       a.detail->>'business_code' AS business_code,
                       COALESCE(s.shipment_no, o.order_no) AS related_no
                FROM app.operational_alerts a
                LEFT JOIN app.shipments s ON s.id = a.shipment_id
                LEFT JOIN app.orders o ON o.id = a.order_id
                WHERE a.id = ? AND a.status = 'OPEN' AND a.lock_version = ?
                """,
                (rs, rowNum) -> new OperationalAlertCard.View(
                        rs.getLong("id"),
                        rs.getLong("lock_version"),
                        rs.getString("alert_no"),
                        "RED".equals(rs.getString("severity"))
                                ? OperationalAlertCard.Severity.RED
                                : OperationalAlertCard.Severity.YELLOW,
                        rs.getString("message"),
                        rs.getString("related_no"),
                        rs.getString("business_code"),
                        links.of("/workbench/alerts?alert_no=" + rs.getString("alert_no"))),
                entityId,
                entityVersion);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(OperationalAlertCard.render(rows.getFirst()));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT a.id, a.lock_version
                FROM app.operational_alerts a
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'alert'
                      AND c.entity_id = a.id
                      AND c.entity_version = a.lock_version
                WHERE a.status = 'OPEN'
                  AND a.created_at >= ?
                  AND c.id IS NULL
                ORDER BY a.created_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        OperationalAlertCard.DOMAIN, rs.getLong("id"), rs.getLong("lock_version")),
                since,
                limit);
    }
}
