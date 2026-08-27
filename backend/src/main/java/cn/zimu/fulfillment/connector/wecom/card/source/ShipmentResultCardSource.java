package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.PreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.ShipmentResultCard;
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
 * 发货结果卡来源：订单 → 发货批次 → 京东出库单 → 运单。
 *
 * <p>路由跟随 {@code preship}：确认卡发给谁，结果就回给谁。分开配两个会话只会制造
 * 「在这边确认、到那边找回执」的割裂，而这两张卡本来就是同一次对话的一问一答。
 * 卡面不含手机号与详细地址，但仍随 preship 走单聊——收货人姓名同样是客户信息。
 */
@Service
public class ShipmentResultCardSource implements WecomBusinessCardSource {

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public ShipmentResultCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
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
}
