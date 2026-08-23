package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Executes one fenced order-draft card send. */
@Service
public class OrderDraftCardRunner {

    public static final Duration LEASE_EXTENSION = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrderDraftCardStore cards;
    private final AsyncTaskStore tasks;
    private final OrderDraftQueryService drafts;
    private final WecomOutboundGateway gateway;
    private final OrderDraftCardFailureCoordinator failures;

    public OrderDraftCardRunner(
            OrderDraftCardStore cards,
            AsyncTaskStore tasks,
            OrderDraftQueryService drafts,
            WecomOutboundGateway gateway,
            OrderDraftCardFailureCoordinator failures) {
        this.cards = cards;
        this.tasks = tasks;
        this.drafts = drafts;
        this.gateway = gateway;
        this.failures = failures;
    }

    public void execute(AsyncTaskStore.AsyncTask task) {
        long cardId = cardId(task);
        if (!tasks.renewLease(task.id(), task.leaseOwner(), LEASE_EXTENSION)) {
            return;
        }
        OrderDraftCard card = cards.load(cardId);
        CardSendPermit permit = cards.beginSend(cardId);
        if (permit.action() == CardSendAction.SKIP_HANDLED) {
            tasks.succeed(task.id(), task.leaseOwner());
            return;
        }
        if (permit.action() == CardSendAction.SKIP_UNKNOWN) {
            failures.recordDeliveryUnknown(
                    task, cardId, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            return;
        }

        WecomOutboundMessage message;
        try {
            OrderDraftDetailDto currentDraft = drafts.detail(card.orderDraftId());
            if (!"OPEN".equals(currentDraft.status()) || currentDraft.revision() != card.draftRevision()) {
                cards.recordSuperseded(
                        cardId,
                        "OPEN".equals(currentDraft.status())
                                ? "WECOM_ORDER_DRAFT_CARD_REVISION_SUPERSEDED"
                                : "WECOM_ORDER_DRAFT_CARD_DRAFT_CLOSED");
                tasks.succeed(task.id(), task.leaseOwner());
                return;
            }
            ObjectNode payload = card(currentDraft, card.taskId());
            message = WecomOutboundMessage.templateCard(card.chatId(), payload);
        } catch (RuntimeException ex) {
            failures.recordRetryableFailure(
                    task, cardId, "WECOM_ORDER_DRAFT_CARD_BUILD_FAILED");
            return;
        }

        WecomSendResult result;
        try {
            result = gateway.send(message);
        } catch (RuntimeException ex) {
            failures.recordDeliveryUnknown(
                    task, cardId, "WECOM_ORDER_DRAFT_CARD_SEND_EXCEPTION");
            return;
        }
        if (result.status() == WecomSendStatus.SUCCESS) {
            cards.recordSent(cardId, result.requestId(), result.acknowledgedAt());
            tasks.succeed(task.id(), task.leaseOwner());
        } else if (result.retryable()) {
            failures.recordRetryableFailure(task, cardId, stableCode(result));
        } else if (result.status() == WecomSendStatus.FAILED && result.errorCode() != null) {
            // A non-zero platform ACK is a known rejection: the card was not accepted.
            failures.recordKnownFailure(
                    task,
                    cardId,
                    stableCode(result),
                    "WECOM_ORDER_DRAFT_CARD_SEND_FAILED");
        } else {
            // ACK timeout, post-submit connection loss and unclassified transport failures have an
            // unknown external effect. They are fenced from blind resend.
            failures.recordDeliveryUnknown(task, cardId, stableCode(result));
        }
    }

    static ObjectNode card(OrderDraftDetailDto draft, String taskId) {
        ObjectNode card = JSON.createObjectNode();
        card.put("card_type", "button_interaction");
        String readiness = draft.missingFields().isEmpty()
                ? "资料完整，点击确认后生成正式订单"
                : "仍需补充 " + draft.missingFields().size() + " 项资料";
        card.putObject("main_title")
                .put("title", "订单草稿待确认")
                .put("desc", draft.draftNo() + " · " + draft.lines().size() + " 行 · " + readiness);
        ArrayNode buttons = card.putArray("button_list");
        buttons.addObject().put("text", "确认订单").put("key", "confirm_order").put("style", 1);
        buttons.addObject().put("text", "需要补充").put("key", "supplement_order").put("style", 2);
        card.put("task_id", taskId);
        return card;
    }

    private static long cardId(AsyncTaskStore.AsyncTask task) {
        if (!OrderDraftCardEnqueuer.TASK_TYPE.equals(task.taskType())
                || task.payloadRef() == null
                || !task.payloadRef().matches("card:[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid order-draft card task payload");
        }
        return Long.parseLong(task.payloadRef().substring("card:".length()));
    }

    private static String stableCode(WecomSendResult result) {
        String value;
        if (result.errorCode() != null) {
            value = "WECOM_" + result.errorCode();
        } else {
            value = result.errorMessage() == null ? result.status().name() : result.errorMessage();
        }
        return value.substring(0, Math.min(128, value.length()));
    }
}
