package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically reconciles one order-draft card with its owning async task after failure. */
@Service
public class OrderDraftCardFailureCoordinator {

    static final Duration RETRY_BACKOFF = Duration.ofSeconds(10);

    private final OrderDraftCardStore cards;
    private final AsyncTaskStore tasks;

    public OrderDraftCardFailureCoordinator(OrderDraftCardStore cards, AsyncTaskStore tasks) {
        this.cards = cards;
        this.tasks = tasks;
    }

    /** Records a failure that is known not to have delivered an external card. */
    @Transactional
    public void recordRetryableFailure(
            AsyncTaskStore.AsyncTask task, long cardId, String errorCode) {
        OrderDraftCard card = cards.lock(cardId);
        if ("SENT".equals(card.status()) || "SUPERSEDED".equals(card.status())) {
            tasks.succeedOwned(task.id(), task.leaseOwner());
            return;
        }
        if ("UNKNOWN".equals(card.status())) {
            terminalize(task, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            return;
        }
        if ("FAILED".equals(card.status())) {
            terminalize(task, errorCode);
            return;
        }
        if (!"PENDING".equals(card.status()) && !"SENDING".equals(card.status())) {
            throw new IllegalStateException(
                    "unknown order-draft card status: " + card.status());
        }
        if ("FINALIZING".equals(task.status())) {
            cards.recordFailed(cardId, errorCode);
            tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), errorCode);
            return;
        }
        AsyncTaskStore.FailureTransition transition = tasks.recordFailureOwned(
                task.id(), task.leaseOwner(), errorCode, RETRY_BACKOFF);
        if (transition == AsyncTaskStore.FailureTransition.RETRY_SCHEDULED) {
            if ("SENDING".equals(card.status())) {
                cards.recordRetryable(cardId, errorCode);
            }
            return;
        }
        cards.recordFailed(cardId, errorCode);
        tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), errorCode);
    }

    /** Reconciles an unexpected runner crash from durable card state without blind resend. */
    @Transactional
    public void recoverUnhandledFailure(AsyncTaskStore.AsyncTask task, String errorCode) {
        long cardId = cardId(task);
        OrderDraftCard card = cards.lock(cardId);
        switch (card.status()) {
            case "SENT", "SUPERSEDED" -> tasks.succeedOwned(task.id(), task.leaseOwner());
            case "SENDING" -> {
                cards.recordUnknown(cardId, errorCode);
                terminalize(task, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            }
            case "UNKNOWN" -> terminalize(task, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            case "FAILED" -> terminalize(task, errorCode);
            case "PENDING" -> recoverPending(task, cardId, errorCode);
            default -> throw new IllegalStateException(
                    "unknown order-draft card status: " + card.status());
        }
    }

    /** Records a platform rejection whose external effect is known to be absent. */
    @Transactional
    public void recordKnownFailure(
            AsyncTaskStore.AsyncTask task,
            long cardId,
            String cardErrorCode,
            String taskErrorCode) {
        OrderDraftCard card = cards.lock(cardId);
        switch (card.status()) {
            case "SENT", "SUPERSEDED" -> tasks.succeedOwned(task.id(), task.leaseOwner());
            case "PENDING", "SENDING" -> {
                cards.recordFailed(cardId, cardErrorCode);
                terminalize(task, taskErrorCode);
            }
            case "UNKNOWN" -> terminalize(task, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            case "FAILED" -> terminalize(task, taskErrorCode);
            default -> throw new IllegalStateException(
                    "unknown order-draft card status: " + card.status());
        }
    }

    /** Records an external call whose delivery result cannot be proven. */
    @Transactional
    public void recordDeliveryUnknown(
            AsyncTaskStore.AsyncTask task, long cardId, String errorCode) {
        OrderDraftCard card = cards.lock(cardId);
        switch (card.status()) {
            case "SENT", "SUPERSEDED" -> tasks.succeedOwned(task.id(), task.leaseOwner());
            case "SENDING" -> {
                cards.recordUnknown(cardId, errorCode);
                terminalize(task, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            }
            case "UNKNOWN", "FAILED" ->
                    terminalize(task, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
            case "PENDING" -> throw new IllegalStateException(
                    "delivery cannot be unknown before card send starts: " + cardId);
            default -> throw new IllegalStateException(
                    "unknown order-draft card status: " + card.status());
        }
    }

    private void recoverPending(
            AsyncTaskStore.AsyncTask task, long cardId, String errorCode) {
        if ("FINALIZING".equals(task.status())) {
            cards.recordFailed(cardId, errorCode);
            tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), errorCode);
            return;
        }
        AsyncTaskStore.FailureTransition transition = tasks.recordFailureOwned(
                task.id(), task.leaseOwner(), errorCode, RETRY_BACKOFF);
        if (transition == AsyncTaskStore.FailureTransition.FINALIZING) {
            cards.recordFailed(cardId, errorCode);
            tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), errorCode);
        }
    }

    private void terminalize(AsyncTaskStore.AsyncTask task, String errorCode) {
        if ("FINALIZING".equals(task.status())) {
            tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), errorCode);
            return;
        }
        tasks.failTerminal(task.id(), task.leaseOwner(), errorCode);
    }

    private static long cardId(AsyncTaskStore.AsyncTask task) {
        if (!OrderDraftCardEnqueuer.TASK_TYPE.equals(task.taskType())
                || task.payloadRef() == null
                || !task.payloadRef().matches("card:[1-9][0-9]*")) {
            throw new IllegalArgumentException("invalid order-draft card task payload");
        }
        return Long.parseLong(task.payloadRef().substring("card:".length()));
    }
}
