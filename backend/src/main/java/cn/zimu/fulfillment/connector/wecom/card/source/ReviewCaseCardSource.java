package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.ReviewCaseCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 复核事项卡来源：从 {@code app.review_cases} 按当前事实渲染。
 *
 * <p>只有 {@code status='OPEN'} 且 {@code resolution_version} 未推进的事项才发卡。
 * 已处置的事项发卡出去，等于把人叫到一件已经做完的事上——比不发更糟。
 */
@Service
public class ReviewCaseCardSource implements WecomBusinessCardSource {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("MM-dd HH:mm");


    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public ReviewCaseCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return ReviewCaseCard.DOMAIN;
    }

    @Override
    public Optional<Route> route(long entityId) {
        return routes.resolve(domain());
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        List<ReviewCaseCard.View> rows = jdbc.query(
                """
                SELECT rc.id, rc.case_no, rc.case_type, rc.reason_code, rc.responsible_team,
                       rc.resolution_version, rc.created_at,
                       rc.detail->>'message' AS detail_message,
                       COALESCE(o.order_no, s.shipment_no, ib.batch_no) AS related_no
                FROM app.review_cases rc
                LEFT JOIN app.orders o ON o.id = rc.order_id
                LEFT JOIN app.shipments s ON s.id = rc.shipment_id
                LEFT JOIN app.import_batches ib ON ib.id = rc.import_batch_id
                WHERE rc.id = ? AND rc.status = 'OPEN' AND rc.resolution_version = ?
                """,
                (rs, rowNum) -> new ReviewCaseCard.View(
                        rs.getLong("id"),
                        rs.getLong("resolution_version"),
                        rs.getString("case_no"),
                        ReviewCaseLabels.caseType(rs.getString("case_type")),
                        // detail.message 是建事项时人写的整句（「运单文件下载或解密失败，
                        // 请重新单聊发送原文件」），比任何枚举翻译都准；没有才退回原因码
                        headline(rs.getString("detail_message"), rs.getString("reason_code")),
                        ReviewCaseLabels.team(rs.getString("responsible_team")),
                        rs.getString("related_no"),
                        createdAtLabel(rs.getObject("created_at", OffsetDateTime.class)),
                        links.of("/workbench/review-inbox?case_no=" + rs.getString("case_no"))),
                entityId,
                entityVersion);
        // 零行 = 已处置或版本已推进：这张卡不该再发
        return rows.isEmpty() ? Optional.empty() : Optional.of(ReviewCaseCard.render(rows.getFirst()));
    }

    private static String headline(String detailMessage, String reasonCode) {
        return detailMessage != null && !detailMessage.isBlank()
                ? detailMessage
                : ReviewCaseLabels.reason(reasonCode);
    }

    private static String createdAtLabel(OffsetDateTime createdAt) {
        return createdAt == null ? null : CREATED_AT.format(createdAt.atZoneSameInstant(SHANGHAI));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT rc.id, rc.resolution_version
                FROM app.review_cases rc
                LEFT JOIN app.wecom_business_cards c
                       ON c.card_domain = 'review'
                      AND c.entity_id = rc.id
                      AND c.entity_version = rc.resolution_version
                WHERE rc.status = 'OPEN'
                  AND rc.created_at >= ?
                  AND c.id IS NULL
                ORDER BY rc.created_at
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        ReviewCaseCard.DOMAIN, rs.getLong("id"), rs.getLong("resolution_version")),
                since,
                limit);
    }
}
