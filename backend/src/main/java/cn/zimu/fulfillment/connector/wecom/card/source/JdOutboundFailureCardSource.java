package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.JdOutboundFailureCard;
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
 * 京东出库失败卡来源：从 {@code app.shipment_jd_outbounds} 渲染。
 *
 * <p>{@code shipment_jd_outbounds} 没有乐观锁列，本域用 {@code retry_count} 当版本——
 * 语义上恰好合适：每次重试都会 +1，因此重试之后旧卡上的 task_id 自动失配，
 * 点旧卡片得到「已被处理」而不是重复提交一次外部建单。
 *
 * <p>只有 {@code sync_status='SYNC_FAILED'} 才发卡：已经提交成功的单子不该出现在
 * 失败通知里。
 */
@Service
public class JdOutboundFailureCardSource implements WecomBusinessCardSource {

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public JdOutboundFailureCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return JdOutboundFailureCard.DOMAIN;
    }

    @Override
    public Optional<Route> route(long entityId) {
        return routes.resolve(domain());
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        List<JdOutboundFailureCard.View> rows = jdbc.query(
                """
                SELECT shipment_id, erp_delivery_no, failure_phase, last_error_code, retry_count
                FROM app.shipment_jd_outbounds
                WHERE shipment_id = ? AND sync_status = 'SYNC_FAILED' AND retry_count = ?
                """,
                (rs, rowNum) -> new JdOutboundFailureCard.View(
                        rs.getLong("shipment_id"),
                        rs.getLong("retry_count"),
                        rs.getString("erp_delivery_no"),
                        rs.getString("failure_phase"),
                        rs.getString("last_error_code"),
                        rs.getInt("retry_count"),
                        links.of("/fulfillment/outbound-recon?erp_delivery_no="
                                + rs.getString("erp_delivery_no"))),
                entityId,
                entityVersion);
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(JdOutboundFailureCard.render(rows.getFirst()));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        // retry_count 兼作版本：重试一次即 +1，旧卡自动失配，不会重复提交外部建单
        return jdbc.query(
                """
                SELECT jo.shipment_id, jo.retry_count
                FROM app.shipment_jd_outbounds jo
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'jd-outbound'
                      AND c.entity_id = jo.shipment_id
                      AND c.entity_version = jo.retry_count
                WHERE jo.sync_status = 'SYNC_FAILED'
                  AND jo.updated_at >= ?
                  AND c.id IS NULL
                ORDER BY jo.updated_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        JdOutboundFailureCard.DOMAIN,
                        rs.getLong("shipment_id"),
                        rs.getLong("retry_count")),
                since,
                limit);
    }
}
