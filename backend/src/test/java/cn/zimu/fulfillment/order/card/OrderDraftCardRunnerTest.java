package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftLineDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Issue #87 durable card-send seam: useful card, delivery fence and unknown no-resend. */
class OrderDraftCardRunnerTest {

    private OrderDraftCardStore cards;
    private AsyncTaskStore tasks;
    private OrderDraftQueryService drafts;
    private WecomOutboundGateway gateway;
    private OrderDraftCardFailureCoordinator failures;
    private cn.zimu.fulfillment.connector.wecom.WecomChatReplyPolicyService replyPolicies;
    private cn.zimu.fulfillment.connector.wecom.WecomReplyRouteProperties replyRoutes;
    private OrderDraftCardRunner runner;

    @BeforeEach
    void setUp() {
        cards = mock(OrderDraftCardStore.class);
        tasks = mock(AsyncTaskStore.class);
        drafts = mock(OrderDraftQueryService.class);
        gateway = mock(WecomOutboundGateway.class);
        failures = mock(OrderDraftCardFailureCoordinator.class);
        replyPolicies = mock(cn.zimu.fulfillment.connector.wecom.WecomChatReplyPolicyService.class);
        // 缺省 FULL：既有用例全部按「允许对话」跑，静默行为单独立用例
        when(replyPolicies.allowsConversational(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        // 缺省无路由配置 = ORIGIN：既有用例即「不配置零变化」的回归证据
        replyRoutes = new cn.zimu.fulfillment.connector.wecom.WecomReplyRouteProperties();
        runner = new OrderDraftCardRunner(
                cards, tasks, drafts, gateway, failures, replyPolicies, replyRoutes);
    }

    @Test
    void 静默会话不追问_草稿卡落SUPERSEDED不发送() {
        AsyncTaskStore.AsyncTask task = task(1, 1);
        OrderDraftCard card = new OrderDraftCard(
                7L, 41L, 0L, "order-draft:41", "GROUP", "customer-group", "PENDING", 0);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(card);
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(replyPolicies.allowsConversational("customer-group")).thenReturn(false);

        runner.execute(task);

        verify(gateway, never()).send(org.mockito.ArgumentMatchers.any());
        verify(cards).recordSuperseded(7L, "WECOM_CHAT_REPLY_SILENCED");
        verify(tasks).succeed(task.id(), task.leaseOwner());
    }

    @Test
    void sendsPrivacyMinimizedButtonCardAndCompletesOnlyAfterAck() {
        AsyncTaskStore.AsyncTask task = task(1, 1);
        OrderDraftCard card = new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "PENDING", 0);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(card);
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft());
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                WecomSendStatus.SUCCESS,
                "req-card-41",
                Instant.parse("2026-08-23T10:00:00Z"),
                null,
                null,
                false));

        runner.execute(task);

        ArgumentCaptor<WecomOutboundMessage> outbound = ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(gateway).send(outbound.capture());
        assertThat(outbound.getValue().chatId()).isEqualTo("group-41");
        assertThat(outbound.getValue().templateCard().path("card_type").asText())
                .isEqualTo("button_interaction");
        assertThat(outbound.getValue().templateCard().path("task_id").asText())
                .isEqualTo("order-draft_41_v0");
        assertThat(outbound.getValue().templateCard().path("button_list").get(0).path("key").asText())
                .isEqualTo("confirm_order");
        assertThat(outbound.getValue().templateCard().toString())
                .contains("OD-41", "1 行", "资料完整")
                .doesNotContain("13800000000", "上海市测试地址");
        verify(cards).recordSent(7L, "req-card-41", Instant.parse("2026-08-23T10:00:00Z"));
        verify(tasks).succeed(task.id(), task.leaseOwner());
    }

    @Test
    void 配置OVERRIDE后草稿卡改投指定接收者_静默策略按改投目标判定() {
        AsyncTaskStore.AsyncTask task = task(1, 1);
        OrderDraftCard card = new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "SINGLE", "origin-user", "PENDING", 0);
        cn.zimu.fulfillment.connector.wecom.WecomReplyRouteProperties.Route route =
                new cn.zimu.fulfillment.connector.wecom.WecomReplyRouteProperties.Route();
        route.setMode(cn.zimu.fulfillment.connector.wecom.WecomReplyRouteProperties.Mode.OVERRIDE);
        route.setChatId("ops-review-group");
        replyRoutes.setRoutes(Map.of(
                cn.zimu.fulfillment.connector.wecom.WecomReplyRouteProperties.SCENARIO_ORDER_DRAFT_CARD,
                route));
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(card);
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft());
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                WecomSendStatus.SUCCESS,
                "req-card-41",
                Instant.parse("2026-08-23T10:00:00Z"),
                null,
                null,
                false));

        runner.execute(task);

        ArgumentCaptor<WecomOutboundMessage> outbound =
                ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(gateway).send(outbound.capture());
        assertThat(outbound.getValue().chatId()).isEqualTo("ops-review-group");
        // 静默判定应针对实际投递目标而不是原会话
        verify(replyPolicies).allowsConversational("ops-review-group");
        verify(tasks).succeed(task.id(), task.leaseOwner());
    }

    @Test
    void recoveredInFlightCardIsUnknownAndNeverBlindlyResent() {
        AsyncTaskStore.AsyncTask task = task(2, 2);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "SENDING", 1));
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SKIP_UNKNOWN, 1));

        runner.execute(task);

        verify(gateway, never()).send(any());
        verify(failures).recordDeliveryUnknown(task, 7L, "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
    }

    @Test
    void explicitPlatformRejectionIsFailedRatherThanUnknown() {
        AsyncTaskStore.AsyncTask task = task(3, 1);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "PENDING", 0));
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft());
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                WecomSendStatus.FAILED,
                "req-card-rejected",
                null,
                93000,
                "rejected",
                false));

        runner.execute(task);

        verify(failures).recordKnownFailure(
                task, 7L, "WECOM_93000", "WECOM_ORDER_DRAFT_CARD_SEND_FAILED");
    }

    @Test
    void localPreSubmitFailureIsRetriedThroughAtomicCoordinator() {
        AsyncTaskStore.AsyncTask task = task(6, 1);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "PENDING", 0));
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft());
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                WecomSendStatus.FAILED,
                null,
                null,
                null,
                "LOCAL_BACKPRESSURE",
                true));

        runner.execute(task);

        verify(failures).recordRetryableFailure(task, 7L, "LOCAL_BACKPRESSURE");
    }

    @Test
    void exceptionAfterExternalSubmitIsFencedAsUnknown() {
        AsyncTaskStore.AsyncTask task = task(7, 1);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "PENDING", 0));
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft());
        when(gateway.send(any())).thenThrow(new IllegalStateException("connection lost"));

        runner.execute(task);

        verify(failures).recordDeliveryUnknown(
                task, 7L, "WECOM_ORDER_DRAFT_CARD_SEND_EXCEPTION");
    }

    @Test
    void closedDraftSupersedesQueuedCardWithoutSending() {
        AsyncTaskStore.AsyncTask task = task(4, 1);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "PENDING", 0));
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft("CONFIRMED", 0L));

        runner.execute(task);

        verify(cards).recordSuperseded(7L, "WECOM_ORDER_DRAFT_CARD_DRAFT_CLOSED");
        verify(tasks).succeed(task.id(), task.leaseOwner());
        verify(gateway, never()).send(any());
    }

    @Test
    void newerOpenDraftRevisionSupersedesStaleCardWithoutSending() {
        AsyncTaskStore.AsyncTask task = task(5, 1);
        when(tasks.renewLease(task.id(), task.leaseOwner(), OrderDraftCardRunner.LEASE_EXTENSION))
                .thenReturn(true);
        when(cards.load(7L)).thenReturn(new OrderDraftCard(
                7L, 41L, 0L, "order-draft_41_v0", "GROUP", "group-41", "PENDING", 0));
        when(cards.beginSend(7L)).thenReturn(new CardSendPermit(CardSendAction.SEND, 1));
        when(drafts.detail(41L)).thenReturn(draft("OPEN", 1L));

        runner.execute(task);

        verify(cards).recordSuperseded(7L, "WECOM_ORDER_DRAFT_CARD_REVISION_SUPERSEDED");
        verify(tasks).succeed(task.id(), task.leaseOwner());
        verify(gateway, never()).send(any());
    }

    private static AsyncTaskStore.AsyncTask task(long id, int attempts) {
        return new AsyncTaskStore.AsyncTask(
                id,
                OrderDraftCardEnqueuer.TASK_TYPE,
                "card:7",
                "RUNNING",
                attempts,
                3,
                Instant.now(),
                Instant.now().plusSeconds(30),
                "card-worker",
                null,
                "card-task-7",
                Instant.now(),
                Instant.now());
    }

    private static OrderDraftDetailDto draft() {
        return draft("OPEN", 0L);
    }

    private static OrderDraftDetailDto draft(String status, long revision) {
        return new OrderDraftDetailDto(
                "41",
                "OD-41",
                "WECOM-SUB-41",
                "11",
                status,
                revision,
                null,
                null,
                null,
                List.of(Map.of("customer_id", "9", "customer_name", "测试客户")),
                "测试客户",
                "张三",
                "13800000000",
                "上海市测试地址",
                "MONTHLY",
                Instant.parse("2026-08-31T16:00:00Z"),
                List.of(),
                List.of(new OrderDraftLineDto(
                        "51",
                        1,
                        null,
                        null,
                        List.of(Map.of("sku_id", "17", "sku_code", "SKU-17")),
                        "商品",
                        "规格",
                        "件",
                        2)),
                "61",
                0L,
                null,
                null,
                null,
                null,
                Instant.now(),
                Instant.now());
    }
}
