package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates the durable card row and worker task in the draft transaction. */
@Service
public class OrderDraftCardEnqueuer {

    public static final String TASK_TYPE = "WECOM_ORDER_DRAFT_CARD";

    private final OrderDraftCardStore cards;
    private final AsyncTaskStore tasks;

    public OrderDraftCardEnqueuer(OrderDraftCardStore cards, AsyncTaskStore tasks) {
        this.cards = cards;
        this.tasks = tasks;
    }

    @Transactional
    public void enqueue(long draftId, long draftRevision) {
        OrderDraftCard card = cards.create(draftId, draftRevision);
        tasks.enqueue(TASK_TYPE, "card:" + card.id(), "wecom-order-draft-card:" + card.id(), 3);
    }
}
