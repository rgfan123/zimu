package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.BatchConfirmedCard;
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
 * 整批确认播报卡来源：从 {@code app.import_batches} 及其派生事实渲染。
 *
 * <p>播报卡的数字全部由 SQL 现算而不是入队时快照——播报的是「确认之后实际发生了什么」，
 * 用快照会把「以为会发生」当成事实播出去。
 *
 * <p>两条出库通道分别报数（京东 SDK 建单 / 第三方导出），合并成一个数字就看不出
 * 哪条通道没走通——那正是收卡的人最想知道的事。
 */
@Service
public class BatchConfirmedCardSource implements WecomBusinessCardSource {

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public BatchConfirmedCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return BatchConfirmedCard.DOMAIN;
    }

    @Override
    public Optional<Route> route(long entityId) {
        if (!links.textNoticeAvailable(domain(), entityId)) {
            return Optional.empty();
        }
        return routes.resolve(domain());
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        if (!links.textNoticeAvailable(domain(), entityId)) {
            return Optional.empty();
        }
        List<BatchConfirmedCard.View> rows = jdbc.query(
                """
                SELECT b.id, b.batch_no, b.revision_no,
                       COALESCE(b.source_channel, '未标注') AS source_channel,
                       b.uploaded_by,
                       (SELECT count(*) FROM app.orders o
                         WHERE o.source_import_batch_id = b.id)                AS order_count,
                       (SELECT count(*) FROM app.order_lines l
                         JOIN app.orders o ON o.id = l.order_id
                        WHERE o.source_import_batch_id = b.id)                 AS line_count,
                       (SELECT count(*) FROM app.shipment_jd_outbounds jo
                         JOIN app.shipments s ON s.id = jo.shipment_id
                         JOIN app.orders o2 ON o2.id = s.order_id
                        WHERE o2.source_import_batch_id = b.id
                          AND jo.sync_status = 'SUBMITTED')                    AS jd_shipment_count,
                       (SELECT count(*) FROM app.shipments s2
                         JOIN app.orders o3 ON o3.id = s2.order_id
                    LEFT JOIN app.shipment_jd_outbounds jo2 ON jo2.shipment_id = s2.id
                        WHERE o3.source_import_batch_id = b.id
                          AND jo2.shipment_id IS NULL)                         AS third_party_count
                FROM app.import_batches b
                WHERE b.id = ? AND b.revision_no = ?
                  AND b.status IN ('COMPLETED', 'COMPLETED_WITH_REVIEW')
                """,
                (rs, rowNum) -> new BatchConfirmedCard.View(
                        rs.getLong("id"),
                        rs.getLong("revision_no"),
                        rs.getString("batch_no"),
                        rs.getString("source_channel"),
                        rs.getInt("order_count"),
                        rs.getInt("line_count"),
                        rs.getInt("jd_shipment_count"),
                        rs.getInt("third_party_count"),
                        rs.getString("uploaded_by"),
                        links.of("/fulfillment/shipments?batch_no=" + rs.getString("batch_no"))),
                entityId,
                entityVersion);
        // 批次未收口（仍在 RECEIVED/PROCESSING/FAILED）：还没到播报的时候
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(BatchConfirmedCard.render(rows.getFirst()));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        if (!links.configured()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT b.id, b.revision_no
                FROM app.import_batches b
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'batch'
                      AND c.entity_id = b.id
                      AND c.entity_version = b.revision_no
                WHERE b.status IN ('COMPLETED', 'COMPLETED_WITH_REVIEW')
                  AND b.processed_at >= ?
                  AND c.id IS NULL
                ORDER BY b.processed_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        BatchConfirmedCard.DOMAIN, rs.getLong("id"), rs.getLong("revision_no")),
                since,
                limit);
    }
}
