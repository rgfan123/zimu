package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.PreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 发货前确认卡来源：从 {@code app.orders} 及其行、SKU、履约方编码渲染。
 *
 * <p><b>只进单聊，这是硬门闩。</b>卡面带手机号与详细地址，进群就是把客户 PII 广播给
 * 群里所有人。{@code WecomBusinessCardSource#render} 的签名里没有 Route，渲染时
 * 根本不知道自己要去哪，所以「群聊时记得脱敏」这种约定在这里落不了地——只能在路由层
 * 把群聊整个否掉：配成 GROUP 就当没配，卡不发，落 SUPERSEDED。宁可不发，不可发错地方。
 *
 * <p><b>渲染窗口</b>：只有还没发出去的订单才值得确认。已发货/已取消的订单再推确认卡，
 * 点下去只会得到 VERSION_CONFLICT，白白骚扰人。窗口外一律返回 empty。
 */
@Service
public class PreShipConfirmCardSource implements WecomBusinessCardSource {

    private static final Logger log = LoggerFactory.getLogger(PreShipConfirmCardSource.class);

    /**
     * 可确认窗口。{@code SKU_MAPPED} 是正常触发点（映射完成、尚未建单）；
     * {@code FULFILLING} 一并放行是因为建单可能因阻塞退回，此时确认卡仍然有意义。
     */
    private static final String RENDERABLE_STATUSES = "('SKU_MAPPED', 'FULFILLING')";

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public PreShipConfirmCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return PreShipConfirmCard.DOMAIN;
    }

    /** PII 门闩：非单聊路由一律拒绝，且说清楚为什么，免得被当成「配置没生效」查半天。 */
    @Override
    public Optional<Route> route(long entityId) {
        Optional<Route> configured = routes.resolve(domain());
        if (configured.isPresent() && configured.get().type() != RouteType.SINGLE) {
            log.warn(
                    "发货前确认卡只能发单聊（卡面含收货人手机号与详细地址），"
                            + "当前 app.wecom-business-card.routes.{}.type={}，本张卡不发",
                    domain(),
                    configured.get().type());
            return Optional.empty();
        }
        return configured;
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        List<PreShipConfirmCard.View> rows = jdbc.query(
                """
                SELECT o.id, o.lock_version, o.source_channel, o.source_ref,
                       o.receiver_name, o.receiver_phone, o.receiver_address,
                       g.line_count, g.total_quantity,
                       g.channel_goods, g.jd_goods, g.jd_goods_code
                FROM app.orders o
                JOIN LATERAL (
                    SELECT count(*)                                              AS line_count,
                           COALESCE(sum(x.qty), 0)                               AS total_quantity,
                           string_agg(DISTINCT x.channel_name, '、')             AS channel_goods,
                           string_agg(x.jd_name || ' ×'
                               || trim(to_char(x.qty, 'FM999999990')), '、')     AS jd_goods,
                           min(x.jd_code)                                        AS jd_goods_code
                    FROM (
                        -- 单品行：SKU 直接挂在订单行上
                        SELECT l.product_name_snapshot AS channel_name,
                               p.product_name          AS jd_name,
                               l.requested_quantity    AS qty,
                               (SELECT ps.provider_sku_code
                                  FROM app.provider_skus ps
                                 WHERE ps.sku_id = s.id AND ps.active
                                 ORDER BY ps.id
                                 LIMIT 1)              AS jd_code
                        FROM app.order_lines l
                        JOIN app.skus s     ON s.id = l.sku_id
                        JOIN app.products p ON p.id = s.product_id
                        WHERE l.order_id = o.id
                        UNION ALL
                        -- 礼包行（CUSTOM_BUNDLE）：行上的 sku_id 是 NULL，
                        -- 实发的 SKU 全在 order_line_components 里。
                        -- 只 JOIN skus 会把整张礼包订单滤成零行，渲染判空、落 SUPERSEDED——
                        -- 表现是「大者的确认卡一张都没发出来」，而日志只说事实已变。
                        SELECT l.product_name_snapshot AS channel_name,
                               c.product_name_snapshot AS jd_name,
                               c.total_quantity        AS qty,
                               (SELECT ps.provider_sku_code
                                  FROM app.provider_skus ps
                                 WHERE ps.sku_id = c.sku_id AND ps.active
                                 ORDER BY ps.id
                                 LIMIT 1)              AS jd_code
                        FROM app.order_lines l
                        JOIN app.order_line_components c ON c.order_line_id = l.id
                        WHERE l.order_id = o.id AND l.sku_id IS NULL
                    ) x
                ) g ON TRUE
                WHERE o.id = ?
                  AND o.lock_version = ?
                  AND o.order_status IN """ + RENDERABLE_STATUSES + """
                  AND g.line_count > 0
                """,
                (rs, rowNum) -> new PreShipConfirmCard.View(
                        rs.getLong("id"),
                        rs.getLong("lock_version"),
                        rs.getString("source_channel"),
                        rs.getString("source_ref"),
                        rs.getString("receiver_name"),
                        rs.getString("receiver_phone"),
                        rs.getString("receiver_address"),
                        rs.getInt("line_count"),
                        trimQuantity(rs.getBigDecimal("total_quantity")),
                        rs.getString("channel_goods"),
                        rs.getString("jd_goods"),
                        rs.getString("jd_goods_code"),
                        links.of("/fulfillment/shipments?order_no=" + rs.getString("source_ref"))),
                entityId,
                entityVersion);
        // 订单已发货/已取消/版本已推进：这张卡不该再发
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(PreShipConfirmCard.render(rows.getFirst()));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT o.id, o.lock_version
                FROM app.orders o
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'preship'
                      AND c.entity_id = o.id
                      AND c.entity_version = o.lock_version
                WHERE o.order_status = 'SKU_MAPPED'
                  -- 导入批次的订单走整批确认卡（一批一卡）；单卡只服务无批次的手工单
                  AND o.source_import_batch_id IS NULL
                  AND o.updated_at >= ?
                  AND c.id IS NULL
                  AND EXISTS (SELECT 1 FROM app.order_lines l WHERE l.order_id = o.id)
                ORDER BY o.updated_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        PreShipConfirmCard.DOMAIN, rs.getLong("id"), rs.getLong("lock_version")),
                since,
                limit);
    }

    /** 数量在库里是 numeric(x,3)：卡面上「2 件」比「2.000 件」可读，去掉无意义的小数位。 */
    static String trimQuantity(java.math.BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
