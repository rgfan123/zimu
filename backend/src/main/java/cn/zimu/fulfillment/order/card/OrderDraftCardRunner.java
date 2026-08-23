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
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OrderDraftCardStore cards;
    private final AsyncTaskStore tasks;
    private final OrderDraftQueryService drafts;
    private final WecomOutboundGateway gateway;

    public OrderDraftCardRunner(
            OrderDraftCardStore cards,
            AsyncTaskStore tasks,
            OrderDraftQueryService drafts,
            WecomOutboundGateway gateway) {
        this.cards = cards;
        this.tasks = tasks;
        this.drafts = drafts;
        this.gateway = gateway;
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
            tasks.failTerminal(
                    task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            return;
        }

        boolean externalStarted = false;
        try {
            ObjectNode payload = card(drafts.detail(card.orderDraftId()), card.taskId());
            WecomOutboundMessage message = WecomOutboundMessage.templateCard(card.chatId(), payload);
            externalStarted = true;
            WecomSendResult result = gateway.send(message);
            if (result.status() == WecomSendStatus.SUCCESS) {
                cards.recordSent(cardId, result.requestId(), result.acknowledgedAt());
                tasks.succeed(task.id(), task.leaseOwner());
            } else if (result.retryable()) {
                retry(task, cardId, stableCode(result));
            } else if (result.status() == WecomSendStatus.FAILED && result.errorCode() != null) {
                // A non-zero platform ACK is a known rejection: the card was not accepted.
                cards.recordFailed(cardId, stableCode(result));
                tasks.failTerminal(
                        task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_SEND_FAILED");
            } else {
                // ACK timeout, post-submit connection loss and unclassified transport failures have an
                // unknown external effect. They are fenced from blind resend.
                cards.recordUnknown(cardId, stableCode(result));
                tasks.failTerminal(
                        task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            }
        } catch (RuntimeException ex) {
            if (externalStarted) {
                cards.recordUnknown(cardId, "WECOM_ORDER_DRAFT_CARD_SEND_EXCEPTION");
                tasks.failTerminal(
                        task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            } else {
                retry(task, cardId, "WECOM_ORDER_DRAFT_CARD_BUILD_FAILED");
            }
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

    private void retry(AsyncTaskStore.AsyncTask task, long cardId, String errorCode) {
        cards.recordRetryable(cardId, errorCode);
        boolean terminal = tasks.fail(task.id(), task.leaseOwner(), errorCode, RETRY_BACKOFF);
        if (terminal) {
            cards.recordFailed(cardId, errorCode);
        }
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
