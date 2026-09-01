package cn.zimu.fulfillment.fulfillment;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 多包裹来源回传的唯一就绪判定，供 Tracking 和采购取消两个事实入口复用。
 */
@Service
public class SourceFollowupProgressService {

    private final JdbcTemplate jdbc;

    public SourceFollowupProgressService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 若存在开放的多包裹来源跟进，按累计量与实际 Tracking 刷新行/订单阶段。 */
    public boolean refresh(long fulfillmentId) {
        Context context = jdbc.query(
                """
                SELECT f.requested_quantity, f.cumulative_shipped_quantity, f.cancelled_quantity,
                       ol.id order_line_id, ol.order_id
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                WHERE f.id=? AND EXISTS (
                    SELECT 1 FROM app.review_cases rc
                    WHERE rc.fulfillment_id=f.id AND rc.status='OPEN'
                      AND rc.reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP'
                )
                FOR UPDATE OF f, ol, o
                """,
                rs -> rs.next() ? new Context(
                        rs.getInt("requested_quantity"),
                        rs.getInt("cumulative_shipped_quantity"),
                        rs.getInt("cancelled_quantity"),
                        rs.getLong("order_line_id"),
                        rs.getLong("order_id")) : null,
                fulfillmentId);
        if (context == null) {
            return false;
        }

        Map<String, Object> shipments = jdbc.queryForMap(
                """
                SELECT count(DISTINCT s.id) FILTER (WHERE si.shipped_quantity>0) actual,
                       count(DISTINCT s.id) FILTER (
                           WHERE si.shipped_quantity>0 AND t.id IS NOT NULL
                             AND s.shipment_status IN ('SHIPPED','DELIVERED')) ready
                FROM app.shipment_items si
                JOIN app.shipments s ON s.id=si.shipment_id
                LEFT JOIN app.trackings t ON t.shipment_id=s.id
                WHERE si.fulfillment_id=?
                """,
                fulfillmentId);
        long actual = ((Number) shipments.get("actual")).longValue();
        long ready = ((Number) shipments.get("ready")).longValue();
        boolean terminalQuantity = context.shipped() + context.cancelled() == context.requested();
        boolean followupReady = terminalQuantity && actual > 0 && ready == actual;
        if (followupReady) {
            jdbc.update(
                    """
                    UPDATE app.order_lines
                    SET processing_stage='NEED_REVIEW', exception_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP',
                        exception_reason='来源商品行存在多个发货批次，需人工完成来源平台后续回传',
                        updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """,
                    context.orderLineId());
            jdbc.update(
                    "UPDATE app.orders SET order_status='NEED_REVIEW', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    context.orderId());
        } else {
            jdbc.update(
                    """
                    UPDATE app.order_lines SET processing_stage=CASE
                        WHEN processing_stage='PROCUREMENT_IN_PROGRESS' THEN 'PROCUREMENT_IN_PROGRESS'
                        ELSE 'WAITING_PROVIDER'
                    END, updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """,
                    context.orderLineId());
        }
        return followupReady;
    }

    private record Context(
            int requested,
            int shipped,
            int cancelled,
            long orderLineId,
            long orderId) {
    }
}
