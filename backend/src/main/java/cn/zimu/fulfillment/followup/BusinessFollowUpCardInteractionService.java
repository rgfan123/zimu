package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardStore;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.operator.InternalOperator;
import cn.zimu.fulfillment.operator.InternalOperatorRepository;
import cn.zimu.fulfillment.order.card.CardFallbackStatus;
import cn.zimu.fulfillment.order.card.CardUpdateStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Claims and accepts one follow-up card decision without performing its asynchronous projection. */
@Service
public class BusinessFollowUpCardInteractionService {

    public static final String CARD_DOMAIN = "followup-draft";

    private static final Map<String, BusinessFollowUpApprovalDecision> DECISIONS = Map.of(
            "confirm_followup", BusinessFollowUpApprovalDecision.CONFIRM);

    private final BusinessFollowUpCardEventStore events;
    private final WecomBusinessCardStore cards;
    private final InternalOperatorRepository operators;
    private final AsyncTaskStore tasks;
    private final JdbcTemplate jdbc;
    private final AuditLogService audits;

    public BusinessFollowUpCardInteractionService(
            BusinessFollowUpCardEventStore events,
            WecomBusinessCardStore cards,
            InternalOperatorRepository operators,
            AsyncTaskStore tasks,
            JdbcTemplate jdbc,
            AuditLogService audits) {
        this.events = events;
        this.cards = cards;
        this.operators = operators;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.audits = audits;
    }

    @Transactional
    public BusinessFollowUpCardInteractionOutcome handle(JsonNode frame) {
        BusinessFollowUpCardEventStore.Input parsed = input(frame);
        if (parsed.messageId().isBlank() || parsed.messageId().length() > 128) {
            throw BusinessException.badRequest(
                    "WECOM_CARD_EVENT_MSGID_INVALID", "企微卡片事件 msgid 缺失或超长");
        }
        BusinessFollowUpCardEventStore.Input input = verifiedLinkage(parsed);
        serializeDraftDecision(input.followupId());
        BusinessFollowUpCardEventStore.Claim claim = events.claim(input);
        if (!claim.process()) {
            return claim.outcome();
        }

        String status;
        String code;
        Long approvalId = null;
        String followupNo = "客户跟进";
        try {
            Accepted accepted = accept(input, claim.eventId());
            status = "ACCEPTED";
            code = "FOLLOWUP_APPROVAL_ACCEPTED";
            approvalId = accepted.approvalId();
            followupNo = accepted.followupNo();
        } catch (BusinessException ex) {
            status = "REJECTED";
            code = ex.getBusinessCode();
        } catch (RuntimeException ex) {
            throw ex;
        }
        events.complete(input, claim.claimToken(), status, code, approvalId);
        return new BusinessFollowUpCardInteractionOutcome(
                input.messageId(),
                input.requestId(),
                input.taskId(),
                input.replyTarget(),
                claim.claimToken(),
                false,
                status,
                code,
                followupNo,
                input.actorUserid(),
                approvalId);
    }

    public void recordUpdateOutcome(
            String messageId,
            String claimToken,
            CardUpdateStatus updateStatus,
            CardFallbackStatus fallbackStatus,
            int latencyMs,
            String updateErrorCode,
            String fallbackErrorCode) {
        events.recordUpdateOutcome(
                messageId,
                claimToken,
                updateStatus,
                fallbackStatus,
                latencyMs,
                updateErrorCode,
                fallbackErrorCode);
    }

    private Accepted accept(BusinessFollowUpCardEventStore.Input input, long eventId) {
        BusinessFollowUpApprovalDecision decision = DECISIONS.get(input.eventKey());
        if (decision == null) {
            throw BusinessException.unprocessable(
                    "FOLLOWUP_CARD_ACTION_UNSUPPORTED", "不支持的客户跟进卡片动作");
        }
        WecomBusinessCard card = cards.findSentByTaskId(input.taskId())
                .filter(value -> CARD_DOMAIN.equals(value.cardDomain()))
                .orElseThrow(() -> BusinessException.unprocessable(
                        "FOLLOWUP_CARD_NOT_SENT", "客户跟进卡片未确认送达"));
        if (!matchesRoute(card, input)) {
            throw BusinessException.unprocessable(
                    "FOLLOWUP_CARD_ROUTE_MISMATCH", "客户跟进卡片回调路由不匹配");
        }
        InternalOperator actor = operators.findByWecomUseridAndActiveTrue(input.actorUserid())
                .orElseThrow(() -> BusinessException.unprocessable(
                        "FOLLOWUP_CARD_ACTOR_UNAUTHORIZED", "点击者不是已启用的内部运营人员"));
        CurrentDraft current = jdbc.query(
                        """
                        SELECT bf.followup_no, bf.current_draft_version,
                               bf.designated_reviewer_operator_id, d.status,
                               CASE WHEN d.content #>> '{order_snapshot,order_draft_id}' ~ '^[1-9][0-9]*$'
                                    THEN (d.content #>> '{order_snapshot,order_draft_id}')::bigint END AS order_draft_id,
                               CASE WHEN d.content #>> '{order_snapshot,revision}' ~ '^[0-9]+$'
                                    THEN (d.content #>> '{order_snapshot,revision}')::bigint END AS order_draft_revision,
                               d.content #>> '{order_snapshot,status}' AS order_draft_status
                        FROM app.business_followups bf
                        JOIN app.business_followup_draft_versions d
                          ON d.followup_id=bf.id AND d.version=bf.current_draft_version
                        WHERE bf.id=?
                        FOR UPDATE OF bf, d
                        """,
                        (rs, row) -> new CurrentDraft(
                                rs.getString("followup_no"),
                                rs.getObject("current_draft_version", Integer.class),
                                rs.getObject("designated_reviewer_operator_id", Long.class),
                                rs.getString("status"),
                                rs.getObject("order_draft_id", Long.class),
                                rs.getObject("order_draft_revision", Long.class),
                                rs.getString("order_draft_status")),
                        card.entityId())
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.unprocessable(
                        "FOLLOWUP_CARD_FACTS_MISSING", "客户跟进当前草稿不存在"));
        if (current.version() == null
                || current.version() != card.entityVersion()
                || !"READY".equals(current.status())) {
            throw BusinessException.conflict(
                    "FOLLOWUP_CARD_STALE", "该客户跟进草稿版本已被取代");
        }
        if (current.designatedReviewerId() == null
                || !current.designatedReviewerId().equals(actor.getId())) {
            throw BusinessException.unprocessable(
                    "FOLLOWUP_CARD_ACTOR_NOT_DESIGNATED", "点击者不是当前指定 +1");
        }
        OrderState order = lockOrderState(current.orderDraftId());
        if (!current.orderSnapshotCurrent(order)) {
            throw BusinessException.conflict(
                    "FOLLOWUP_ORDER_SNAPSHOT_STALE", "卡片展示的 OrderDraft 事实已变化或不完整");
        }
        Long decided = jdbc.query(
                        """
                        SELECT id FROM app.business_followup_approvals
                        WHERE followup_id=? AND draft_version=?
                        """,
                        (rs, row) -> rs.getLong(1),
                        card.entityId(),
                        card.entityVersion())
                .stream()
                .findFirst()
                .orElse(null);
        if (decided != null) {
            throw BusinessException.conflict(
                    "FOLLOWUP_DRAFT_ALREADY_DECIDED", "该客户跟进草稿已经处理");
        }
        String fingerprint = digest(input.eventKey() + "\n" + card.taskId() + "\n" + actor.getId());
        long approvalId = jdbc.query(
                        """
                        INSERT INTO app.business_followup_approvals
                            (followup_id, draft_version, designated_reviewer_operator_id,
                             order_draft_id, order_draft_revision,
                             decided_by_operator_id, decision, source_kind, source_event_id, request_id,
                             idempotency_key, request_fingerprint, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'WECOM_CARD', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        RETURNING id
                        """,
                        (rs, row) -> rs.getLong(1),
                        card.entityId(),
                        card.entityVersion(),
                        current.designatedReviewerId(),
                        current.orderDraftId(),
                        current.orderDraftRevision(),
                        actor.getId(),
                        decision.name(),
                        eventId,
                        input.requestId(),
                        "wecom-followup-approval:" + input.messageId(),
                        fingerprint)
                .getFirst();
        tasks.enqueue(
                BusinessFollowUpApprovalApplication.TASK_TYPE,
                "followup-approval:" + approvalId,
                "followup-approval:" + approvalId,
                3);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(input.requestId())
                .traceId(input.requestId())
                .operator("wecom:" + input.actorUserid())
                .actorType(AuditActorType.HUMAN)
                .service("business-followup")
                .operation("business_followup.approval.accept")
                .requestPayload(Map.of(
                        "followup_id", card.entityId(),
                        "draft_version", card.entityVersion(),
                        "approval_id", approvalId,
                        "decision", decision.name(),
                        "operator_id", actor.getId()))
                .responsePayload(Map.of(
                        "approval_id", approvalId,
                        "status", "ACCEPTED"))
                .httpStatus(202)
                .businessCode("FOLLOWUP_APPROVAL_ACCEPTED"));
        return new Accepted(approvalId, current.followupNo());
    }

    /**
     * Serialize callbacks before inserting their event rows. The event foreign key otherwise takes
     * a shared lock on the follow-up before the later {@code FOR UPDATE}; two distinct msgids can
     * then deadlock while upgrading those locks. The transaction-scoped advisory lock also covers
     * valid-looking references to a row that has already been removed.
     */
    private void serializeDraftDecision(Long followupId) {
        if (followupId == null) {
            return;
        }
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(?)",
                (rs, row) -> Boolean.TRUE,
                followupId);
    }

    /**
     * A callback task id is untrusted text. Keep it as the event's raw task_id evidence, but only
     * populate foreign-key linkage when the referenced immutable draft version really exists.
     * This turns forged, valid-looking identifiers into stable rejections instead of FK failures.
     */
    private BusinessFollowUpCardEventStore.Input verifiedLinkage(
            BusinessFollowUpCardEventStore.Input input) {
        WecomBusinessCard delivery = cards.findByTaskId(input.taskId())
                .filter(card -> CARD_DOMAIN.equals(card.cardDomain()))
                .orElse(null);
        return new BusinessFollowUpCardEventStore.Input(
                input.messageId(),
                input.requestId(),
                input.botId(),
                input.chatId(),
                input.chatType(),
                input.actorUserid(),
                input.createTime(),
                input.eventKey(),
                input.taskId(),
                delivery == null ? null : delivery.entityId(),
                delivery == null ? null : Math.toIntExact(delivery.entityVersion()),
                input.replyTarget());
    }

    private static boolean matchesRoute(
            WecomBusinessCard card, BusinessFollowUpCardEventStore.Input input) {
        return switch (card.routeType()) {
            case "SINGLE" -> "single".equals(input.chatType())
                    && input.chatId().isBlank()
                    && card.chatId().equals(input.actorUserid());
            case "GROUP" -> "group".equals(input.chatType())
                    && !input.chatId().isBlank()
                    && card.chatId().equals(input.chatId());
            default -> false;
        };
    }

    private static BusinessFollowUpCardEventStore.Input input(JsonNode frame) {
        JsonNode body = frame.path("body");
        JsonNode event = body.path("event");
        JsonNode callback = event.path("template_card_event");
        String eventKey = callback.path("event_key").asText(event.path("event_key").asText(""));
        String rawTaskId = callback.path("task_id").asText(event.path("task_id").asText(""));
        WecomTaskId parsed = WecomTaskId.parse(rawTaskId)
                .filter(value -> CARD_DOMAIN.equals(value.domain()))
                .filter(value -> value.version() <= Integer.MAX_VALUE)
                .orElse(null);
        String actor = bounded(body.path("from").path("userid").asText(""), 255);
        String chatId = bounded(body.path("chatid").asText(""), 255);
        return new BusinessFollowUpCardEventStore.Input(
                bounded(body.path("msgid").asText(""), 128),
                bounded(frame.path("headers").path("req_id").asText(""), 128),
                bounded(body.path("aibotid").asText(""), 128),
                chatId,
                bounded(body.path("chattype").asText(""), 32),
                actor,
                body.path("create_time").isNumber() ? body.path("create_time").asLong() : null,
                bounded(eventKey, 64),
                bounded(rawTaskId, 128),
                parsed == null ? null : parsed.entityId(),
                parsed == null ? null : Math.toIntExact(parsed.version()),
                chatId.isBlank() ? actor : chatId);
    }

    private static String bounded(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(max, value.length()));
    }

    private OrderState lockOrderState(Long orderDraftId) {
        if (orderDraftId == null) {
            return null;
        }
        return jdbc.query(
                        "SELECT revision, status FROM app.order_drafts WHERE id=? FOR UPDATE",
                        (rs, row) -> new OrderState(rs.getLong("revision"), rs.getString("status")),
                        orderDraftId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record CurrentDraft(
            String followupNo,
            Integer version,
            Long designatedReviewerId,
            String status,
            Long orderDraftId,
            Long orderDraftRevision,
            String orderDraftStatus) {
        boolean orderSnapshotCurrent(OrderState current) {
            return orderDraftId != null
                    && orderDraftRevision != null
                    && current != null
                    && current.revision() == orderDraftRevision
                    && "OPEN".equals(orderDraftStatus)
                    && "OPEN".equals(current.status());
        }
    }

    private record OrderState(long revision, String status) {}

    private record Accepted(long approvalId, String followupNo) {}
}
