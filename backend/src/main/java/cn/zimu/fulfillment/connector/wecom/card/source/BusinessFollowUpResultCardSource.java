package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.BusinessFollowUpResultCard;
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

/** 从已应用的 Approval 事实投影终态播报卡。 */
@Service
public class BusinessFollowUpResultCardSource implements WecomBusinessCardSource {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DECIDED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public BusinessFollowUpResultCardSource(
            JdbcTemplate jdbc, WecomBusinessCardRouteProperties routes, CardDeepLinks links) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return BusinessFollowUpResultCard.DOMAIN;
    }

    @Override
    public Optional<Route> route(long approvalId) {
        Optional<String> designatedUserid = jdbc.query(
                        """
                        SELECT op.wecom_userid
                        FROM app.business_followup_approvals a
                        JOIN app.internal_operators op
                          ON op.id = a.designated_reviewer_operator_id
                        WHERE a.id = ? AND op.active = TRUE
                          AND op.wecom_userid IS NOT NULL
                          AND btrim(op.wecom_userid) <> ''
                        """,
                        (rs, row) -> rs.getString("wecom_userid"),
                        approvalId)
                .stream()
                .findFirst();
        if (designatedUserid.isEmpty()) {
            return Optional.empty();
        }
        return routes.resolve(domain())
                .filter(route -> route.type() == RouteType.GROUP)
                .or(() -> Optional.of(new Route(RouteType.SINGLE, designatedUserid.get())));
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        return route(entityId).flatMap(route -> render(entityId, entityVersion, route));
    }

    @Override
    public Optional<ObjectNode> render(long approvalId, long draftVersion, Route route) {
        List<BusinessFollowUpResultCard.View> rows = jdbc.query(
                """
                SELECT a.id, a.followup_id, a.draft_version, a.decision,
                       a.application_status, a.application_failure_code, a.decided_at,
                       bf.followup_no, actor.display_name AS decided_by
                FROM app.business_followup_approvals a
                JOIN app.business_followups bf ON bf.id = a.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id = a.followup_id AND d.version = a.draft_version
                JOIN app.internal_operators actor ON actor.id = a.decided_by_operator_id
                WHERE a.id = ? AND a.draft_version = ?
                  AND ((a.application_status = 'FAILED'
                        AND a.application_failure_code IS NOT NULL)
                    OR (a.application_status = 'SUPERSEDED' AND a.applied_at IS NOT NULL)
                    OR (a.application_status = 'APPLIED' AND a.applied_at IS NOT NULL
                      AND ((a.decision = 'CONFIRM' AND d.status = 'CONFIRMED')
                        OR (a.decision = 'REDO' AND d.status = 'SUPERSEDED')
                        OR (a.decision = 'NEEDS_INPUT' AND d.status = 'NEEDS_INPUT')
                        OR (a.decision = 'PAUSE' AND d.status = 'PAUSED'))))
                """,
                (rs, row) -> new BusinessFollowUpResultCard.View(
                        rs.getLong("id"),
                        rs.getLong("followup_id"),
                        rs.getLong("draft_version"),
                        rs.getString("followup_no"),
                        rs.getString("decision"),
                        rs.getString("application_status"),
                        rs.getString("application_failure_code"),
                        rs.getString("decided_by"),
                        decidedAt(rs.getObject("decided_at", OffsetDateTime.class)),
                        links.of("/workbench/business-followups?followup_id="
                                + rs.getLong("followup_id"))),
                approvalId,
                draftVersion);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BusinessFollowUpResultCard.View view = rows.getFirst();
        return Optional.of(route.type() == RouteType.GROUP
                ? BusinessFollowUpResultCard.renderGroup(view)
                : BusinessFollowUpResultCard.render(view));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT a.id, a.draft_version
                FROM app.business_followup_approvals a
                JOIN app.business_followups bf ON bf.id = a.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id = a.followup_id AND d.version = a.draft_version
                LEFT JOIN app.wecom_business_cards c
                  ON c.card_domain = 'followup-result'
                 AND c.entity_id = a.id AND c.entity_version = a.draft_version
                WHERE a.decided_at >= ? AND c.id IS NULL
                  AND ((a.application_status = 'FAILED'
                        AND a.application_failure_code IS NOT NULL)
                    OR (a.application_status = 'SUPERSEDED' AND a.applied_at IS NOT NULL)
                    OR (a.application_status = 'APPLIED' AND a.applied_at IS NOT NULL
                      AND ((a.decision = 'CONFIRM' AND d.status = 'CONFIRMED')
                        OR (a.decision = 'REDO' AND d.status = 'SUPERSEDED')
                        OR (a.decision = 'NEEDS_INPUT' AND d.status = 'NEEDS_INPUT')
                        OR (a.decision = 'PAUSE' AND d.status = 'PAUSED'))))
                ORDER BY a.decided_at, a.id
                LIMIT ?
                """,
                (rs, row) -> WecomTaskId.ofVersion(
                        BusinessFollowUpResultCard.DOMAIN,
                        rs.getLong("id"),
                        rs.getLong("draft_version")),
                since,
                limit);
    }

    private static String decidedAt(OffsetDateTime value) {
        return value == null ? null : DECIDED_AT.format(value.atZoneSameInstant(SHANGHAI));
    }
}
