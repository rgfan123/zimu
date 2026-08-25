package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Parses, claims and executes one order-draft template-card callback. */
@Service
public class WecomOrderDraftCardInteractionService {

    private static final Logger log = LoggerFactory.getLogger(WecomOrderDraftCardInteractionService.class);
    private static final String TASK_DOMAIN = "order-draft";

    private final WecomOrderDraftCardEventStore events;
    private final OrderDraftCardConfirmationService confirmations;
    private final OrderDraftQueryService drafts;
    private final OrderDraftCardStore cards;

    public WecomOrderDraftCardInteractionService(
            WecomOrderDraftCardEventStore events,
            OrderDraftCardConfirmationService confirmations,
            OrderDraftQueryService drafts,
            OrderDraftCardStore cards) {
        this.events = events;
        this.confirmations = confirmations;
        this.drafts = drafts;
        this.cards = cards;
    }

    public CardInteractionOutcome handle(JsonNode frame) {
        CardEventInput input = verifiedLinkage(input(frame));
        if (input.messageId().isBlank()) {
            throw BusinessException.badRequest("WECOM_CARD_EVENT_MSGID_REQUIRED", "企微卡片事件缺少 msgid");
        }
        if (input.messageId().length() > 128) {
            throw BusinessException.badRequest("WECOM_CARD_EVENT_MSGID_INVALID", "企微卡片事件 msgid 超长");
        }
        CardEventClaim claim = events.claim(input);
        if (!claim.process()) {
            return claim.outcome();
        }

        CardConfirmationResult result;
        try {
            result = process(input);
        } catch (BusinessException ex) {
            if ("IDEMPOTENCY_CONFLICT".equals(ex.getBusinessCode())) {
                if (events.hasActiveBusinessLease(input)) {
                    return inProgress(input, claim);
                }
                CardConfirmationResult reconciled = confirmedAfterConflict(input);
                if (reconciled != null) {
                    result = reconciled;
                } else {
                    result = result(
                            CardConfirmationStatus.FAILED,
                            input.orderDraftId(),
                            ex.getBusinessCode(),
                            input.actorUserid(),
                            List.of());
                }
            } else {
                result = result(
                        CardConfirmationStatus.FAILED,
                        input.orderDraftId(),
                        ex.getBusinessCode(),
                        input.actorUserid(),
                        List.of());
            }
        } catch (RuntimeException ex) {
            log.error("企微订单草稿卡片处理失败 msgid={}", input.messageId(), ex);
            result = result(
                    CardConfirmationStatus.FAILED,
                    input.orderDraftId(),
                    "WECOM_CARD_INTERACTION_FAILED",
                    input.actorUserid(),
                    List.of());
        }
        events.complete(input, claim.claimToken(), result);
        return new CardInteractionOutcome(
                input.messageId(),
                input.requestId(),
                input.taskId(),
                input.orderDraftId(),
                input.replyTarget(),
                result,
                false,
                claim.claimToken(),
                claim.attempt());
    }

    private CardEventInput verifiedLinkage(CardEventInput input) {
        OrderDraftCard delivery = cards.findByTaskId(input.taskId())
                .or(() -> cards.findSentByTaskId(input.taskId()))
                .orElse(null);
        return new CardEventInput(
                input.messageId(), input.requestId(), input.botId(), input.chatId(), input.chatType(),
                input.actorUserid(), input.createTime(), input.eventKey(), input.taskId(),
                delivery == null ? null : delivery.orderDraftId(), input.rawPayload(), input.replyTarget());
    }

    private CardInteractionOutcome inProgress(CardEventInput input, CardEventClaim claim) {
        CardConfirmationResult result = result(
                CardConfirmationStatus.FAILED,
                input.orderDraftId(),
                "ORDER_DRAFT_CARD_EVENT_IN_PROGRESS",
                input.actorUserid(),
                List.of());
        return new CardInteractionOutcome(
                input.messageId(),
                input.requestId(),
                input.taskId(),
                input.orderDraftId(),
                input.replyTarget(),
                result,
                true,
                claim.claimToken(),
                claim.attempt());
    }

    private CardConfirmationResult confirmedAfterConflict(CardEventInput input) {
        if (input.orderDraftId() == null) {
            return null;
        }
        try {
            OrderDraftDetailDto latest = drafts.detail(input.orderDraftId());
            if ("CONFIRMED".equals(latest.status())) {
                return new CardConfirmationResult(
                        CardConfirmationStatus.ALREADY_CONFIRMED,
                        latest.draftNo(),
                        List.of(),
                        "ORDER_DRAFT_ALREADY_CONFIRMED",
                        "wecom:" + input.actorUserid(),
                        Instant.now());
            }
        } catch (RuntimeException ignored) {
            // Preserve the original idempotency conflict when the draft cannot be re-read.
        }
        return null;
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

    private CardConfirmationResult process(CardEventInput input) {
        if (input.actorUserid().isBlank() || input.actorUserid().length() > 120) {
            return result(
                    CardConfirmationStatus.REJECTED,
                    input.orderDraftId(),
                    input.actorUserid().isBlank() ? "WECOM_CARD_ACTOR_REQUIRED" : "WECOM_CARD_ACTOR_INVALID",
                    "",
                    List.of());
        }
        if (input.orderDraftId() == null) {
            boolean looksLikePersistedDelivery = WecomTaskId.parse(input.taskId())
                    .filter(value -> TASK_DOMAIN.equals(value.domain()))
                    .filter(value -> value.authorizationRef() != null)
                    .isPresent();
            return result(
                    CardConfirmationStatus.REJECTED,
                    null,
                    looksLikePersistedDelivery
                            ? "WECOM_ORDER_DRAFT_CARD_NOT_SENT"
                            : "WECOM_CARD_TASK_ID_INVALID",
                    input.actorUserid(),
                    List.of());
        }
        OrderDraftCard card = cards.findSentByTaskId(input.taskId()).orElse(null);
        if (card == null || card.orderDraftId() != input.orderDraftId()) {
            return result(
                    CardConfirmationStatus.REJECTED,
                    null,
                    "WECOM_ORDER_DRAFT_CARD_NOT_SENT",
                    input.actorUserid(),
                    List.of());
        }
        if (!matchesRoute(card, input)) {
            return result(
                    CardConfirmationStatus.REJECTED,
                    null,
                    "WECOM_ORDER_DRAFT_CARD_ROUTE_MISMATCH",
                    input.actorUserid(),
                    List.of());
        }
        return switch (input.eventKey()) {
            case "confirm_order" -> confirmations.confirm(
                    input.orderDraftId(),
                    card.draftRevision(),
                    input.messageId(),
                    input.requestId(),
                    input.actorUserid());
            case "supplement_order" -> supplement(input, card.draftRevision());
            default -> result(
                    CardConfirmationStatus.REJECTED,
                    input.orderDraftId(),
                    "WECOM_CARD_EVENT_KEY_UNSUPPORTED",
                    input.actorUserid(),
                    List.of());
        };
    }

    private static boolean matchesRoute(OrderDraftCard card, CardEventInput input) {
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

    private CardConfirmationResult supplement(CardEventInput input, long cardDraftRevision) {
        OrderDraftDetailDto draft = drafts.detail(input.orderDraftId());
        if ("CONFIRMED".equals(draft.status())) {
            return new CardConfirmationResult(
                    CardConfirmationStatus.ALREADY_CONFIRMED,
                    draft.draftNo(),
                    List.of(),
                    "ORDER_DRAFT_ALREADY_CONFIRMED",
                    "wecom:" + input.actorUserid(),
                    Instant.now());
        }
        if (!"OPEN".equals(draft.status())) {
            return new CardConfirmationResult(
                    CardConfirmationStatus.REJECTED,
                    draft.draftNo(),
                    List.of(),
                    "ORDER_DRAFT_NOT_OPEN",
                    "wecom:" + input.actorUserid(),
                    Instant.now());
        }
        if (draft.revision() != cardDraftRevision) {
            return new CardConfirmationResult(
                    CardConfirmationStatus.REJECTED,
                    draft.draftNo(),
                    List.of(),
                    "ORDER_DRAFT_CARD_STALE",
                    "wecom:" + input.actorUserid(),
                    Instant.now());
        }
        return new CardConfirmationResult(
                CardConfirmationStatus.MISSING_INFORMATION,
                draft.draftNo(),
                draft.missingFields(),
                "ORDER_DRAFT_CARD_SUPPLEMENT_REQUESTED",
                "wecom:" + input.actorUserid(),
                Instant.now());
    }

    private CardConfirmationResult result(
            CardConfirmationStatus status,
            Long draftId,
            String businessCode,
            String actorUserid,
            List<String> missingFields) {
        String draftNo = "订单草稿";
        if (draftId != null) {
            try {
                draftNo = drafts.detail(draftId).draftNo();
            } catch (RuntimeException ignored) {
                // Stable rejection evidence must still be persisted for malformed or deleted references.
            }
        }
        String actor = actorUserid == null || actorUserid.isBlank() ? "wecom:unknown" : "wecom:" + actorUserid;
        return new CardConfirmationResult(status, draftNo, missingFields, businessCode, actor, Instant.now());
    }

    private static CardEventInput input(JsonNode frame) {
        JsonNode body = frame.path("body");
        JsonNode event = body.path("event");
        JsonNode card = event.path("template_card_event");
        String eventKey = card.path("event_key").asText("");
        String taskId = card.path("task_id").asText("");
        // The official SDK types use template_card_event, while older callback examples expose flat keys.
        if (eventKey.isBlank()) {
            eventKey = event.path("event_key").asText("");
        }
        if (taskId.isBlank()) {
            taskId = event.path("task_id").asText("");
        }
        String actor = body.path("from").path("userid").asText("");
        String chatId = body.path("chatid").asText("");
        String replyTarget = chatId.isBlank() ? actor : chatId;
        return new CardEventInput(
                body.path("msgid").asText(""),
                frame.path("headers").path("req_id").asText(""),
                bounded(body.path("aibotid").asText(""), 128),
                bounded(chatId, 255),
                bounded(body.path("chattype").asText(""), 32),
                bounded(actor, 255),
                body.path("create_time").asLong(0),
                bounded(eventKey, 64),
                bounded(taskId, 128),
                draftId(taskId),
                frame.toString(),
                replyTarget);
    }

    private static Long draftId(String taskId) {
        return WecomTaskId.parse(taskId)
                .filter(parsed -> TASK_DOMAIN.equals(parsed.domain()))
                .map(WecomTaskId::entityId)
                .filter(id -> id > 0)
                .orElse(null);
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
