package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.card.BusinessFollowUpDraftCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 只从已持久化的当前 READY 草稿投影 +1 审批卡。 */
@Service
public class BusinessFollowUpDraftCardSource implements WecomBusinessCardSource {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;

    public BusinessFollowUpDraftCardSource(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            WecomBusinessCardRouteProperties routes,
            CardDeepLinks links) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.routes = routes;
        this.links = links;
    }

    @Override
    public String domain() {
        return BusinessFollowUpDraftCard.DOMAIN;
    }

    @Override
    public Optional<Route> route(long entityId) {
        if (!links.configured()) {
            return Optional.empty();
        }
        Optional<String> designatedUserid = jdbc.query(
                        """
                        SELECT op.wecom_userid
                        FROM app.business_followups bf
                        JOIN app.internal_operators op
                          ON op.id = bf.designated_reviewer_operator_id
                        WHERE bf.id = ? AND op.active = TRUE
                          AND op.wecom_userid IS NOT NULL
                          AND btrim(op.wecom_userid) <> ''
                        """,
                        (rs, row) -> rs.getString("wecom_userid"),
                        entityId)
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
    public Optional<ObjectNode> render(long entityId, long entityVersion, Route route) {
        if (!links.configured()) {
            return Optional.empty();
        }
        List<BusinessFollowUpDraftCard.View> rows = jdbc.query(
                """
                SELECT bf.id, bf.followup_no, d.version, d.status AS draft_status,
                       d.content::text AS content, c.task_id
                FROM app.business_followups bf
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id = bf.id AND d.version = bf.current_draft_version
                JOIN app.wecom_business_cards c
                  ON c.card_domain='followup-draft'
                 AND c.entity_id=bf.id AND c.entity_version=d.version
                WHERE bf.id = ? AND d.version = ?
                  AND ((bf.stage = 'PENDING_APPROVAL' AND d.status = 'READY')
                    OR (bf.stage = 'NEEDS_INPUT' AND d.status = 'NEEDS_INPUT'))
                """,
                (rs, row) -> view(
                        rs.getLong("id"), rs.getLong("version"), rs.getString("followup_no"),
                        rs.getString("draft_status"), rs.getString("content"), rs.getString("task_id")),
                entityId,
                entityVersion);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BusinessFollowUpDraftCard.View view = rows.getFirst();
        return Optional.of(route.type() == RouteType.GROUP
                ? BusinessFollowUpDraftCard.renderGroup(view)
                : BusinessFollowUpDraftCard.render(view));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        if (!links.configured()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT bf.id, d.version
                FROM app.business_followups bf
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id = bf.id AND d.version = bf.current_draft_version
                LEFT JOIN app.wecom_business_cards c
                  ON c.card_domain = 'followup-draft'
                 AND c.entity_id = bf.id AND c.entity_version = d.version
                WHERE ((bf.stage = 'PENDING_APPROVAL' AND d.status = 'READY')
                    OR (bf.stage = 'NEEDS_INPUT' AND d.status = 'NEEDS_INPUT'))
                  AND d.created_at >= ? AND c.id IS NULL
                ORDER BY d.created_at, bf.id
                LIMIT ?
                """,
                (rs, row) -> WecomTaskId.ofVersion(
                        BusinessFollowUpDraftCard.DOMAIN,
                        rs.getLong("id"),
                        rs.getLong("version")),
                since,
                limit);
    }

    private BusinessFollowUpDraftCard.View view(
            long followupId,
            long version,
            String followupNo,
            String draftStatus,
            String rawContent,
            String capability) {
        try {
            JsonNode content = mapper.readTree(rawContent);
            JsonNode order = content.path("order_snapshot");
            String receiverName = textOrNull(order, "receiver_name");
            String receiverPhone = textOrNull(order, "receiver_phone");
            String receiverAddress = textOrNull(order, "receiver_address");
            String itemSummary = itemSummary(order.path("items"));
            String settlement = textOrNull(order, "settlement_method");
            return new BusinessFollowUpDraftCard.View(
                    followupId,
                    version,
                    followupNo,
                    "READY".equals(draftStatus) && completeOrderSnapshot(
                            receiverName, receiverPhone, receiverAddress,
                            itemSummary, settlement, order.path("missing_fields"), order),
                    firstFact(content, "客户名称", "公司名称", "客户编号"),
                    text(content, "summary"),
                    joined(content.path("questions")),
                    joined(content.path("risks")),
                    joined(content.path("recommended_actions")),
                    valueOrMissing(receiverName),
                    valueOrMissing(receiverPhone),
                    valueOrMissing(receiverAddress),
                    valueOrMissing(itemSummary),
                    valueOrMissing(settlement),
                    detailUrl(followupId, version, authorizationRef(capability)));
        } catch (Exception ex) {
            throw new IllegalStateException("Business Follow-up READY 草稿缺少可投影内容", ex);
        }
    }

    private String detailUrl(long followupId, long version, String capability) {
        return links.of("/workbench/business-followups?followup_id=" + followupId
                + "&expected_draft_version=" + version)
                + "#capability=" + capability;
    }

    private static String firstFact(JsonNode content, String... labels) {
        for (String label : labels) {
            for (JsonNode fact : content.path("facts")) {
                if (label.equals(fact.path("label").asText()) && fact.path("value").isTextual()) {
                    return fact.path("value").asText();
                }
            }
        }
        return "未投影";
    }

    private static String text(JsonNode content, String field) {
        return content.path(field).isTextual() ? content.path(field).asText() : "未投影";
    }

    private static String joined(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return "无";
        }
        StringBuilder result = new StringBuilder();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("；");
            }
            result.append(value.asText());
        }
        return result.isEmpty() ? "无" : result.toString();
    }

    private static String authorizationRef(String taskId) {
        return WecomTaskId.parse(taskId)
                .filter(value -> BusinessFollowUpDraftCard.DOMAIN.equals(value.domain()))
                .map(WecomTaskId::authorizationRef)
                .filter(value -> value != null && !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("客户跟进卡缺少不可猜的授权引用"));
    }

    private boolean completeOrderSnapshot(
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String itemSummary,
            String settlement,
            JsonNode missingFields,
            JsonNode order) {
        return present(receiverName)
                && present(receiverPhone)
                && present(receiverAddress)
                && present(itemSummary)
                && present(settlement)
                && order.path("order_draft_id").isTextual()
                && order.path("revision").canConvertToLong()
                && "OPEN".equals(order.path("status").asText())
                && missingFields.isArray()
                && missingFields.isEmpty();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String valueOrMissing(String value) {
        return present(value) ? value : "待补充";
    }

    private static String textOrNull(JsonNode value, String field) {
        return value.path(field).isTextual() ? value.path(field).asText() : null;
    }

    private static String itemSummary(JsonNode items) {
        if (!items.isArray() || items.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        for (JsonNode item : items) {
            if (!result.isEmpty()) result.append("；");
            result.append(item.path("product_name").asText("未命名商品"));
            if (item.path("spec").isTextual()) result.append(' ').append(item.path("spec").asText());
            if (item.path("quantity").isNumber()) result.append(" ×").append(item.path("quantity").decimalValue().stripTrailingZeros().toPlainString());
            if (item.path("unit").isTextual()) result.append(item.path("unit").asText());
        }
        return result.toString();
    }
}
