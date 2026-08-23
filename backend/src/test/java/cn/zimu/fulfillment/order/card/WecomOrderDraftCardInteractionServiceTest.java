package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.order.OrderDraftQueryService;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WecomOrderDraftCardInteractionServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private WecomOrderDraftCardEventStore events;
    private OrderDraftCardConfirmationService confirmations;
    private OrderDraftQueryService drafts;
    private OrderDraftCardStore cards;
    private WecomOrderDraftCardInteractionService service;

    @BeforeEach
    void setUp() {
        events = mock(WecomOrderDraftCardEventStore.class);
        confirmations = mock(OrderDraftCardConfirmationService.class);
        drafts = mock(OrderDraftQueryService.class);
        cards = mock(OrderDraftCardStore.class);
        service = new WecomOrderDraftCardInteractionService(events, confirmations, drafts, cards);
        when(events.claim(any())).thenReturn(CardEventClaim.claimed("claim-test", 1));
    }

    @Test
    void nestedOfficialEventUsesCallbackUseridAndStableTaskId() throws Exception {
        CardConfirmationResult confirmed = result(CardConfirmationStatus.CONFIRMED);
        when(cards.findSentByTaskId("order-draft:42"))
                .thenReturn(Optional.of(card(42L, 7L, "chat-42")));
        when(confirmations.confirm(42L, 7L, "EVT-42", "REQ-42", "actor-42"))
                .thenReturn(confirmed);
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-42"},"body":{"msgid":"EVT-42","aibotid":"bot",
                 "chatid":"chat-42","chattype":"group","from":{"userid":"actor-42"},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"confirm_order","task_id":"order-draft:42"}}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.result()).isEqualTo(confirmed);
        assertThat(outcome.replyTarget()).isEqualTo("chat-42");
        verify(confirmations).confirm(42L, 7L, "EVT-42", "REQ-42", "actor-42");
        ArgumentCaptor<CardEventInput> input = ArgumentCaptor.forClass(CardEventInput.class);
        verify(events).complete(input.capture(), any(), any());
        assertThat(input.getValue().eventKey()).isEqualTo("confirm_order");
        assertThat(input.getValue().taskId()).isEqualTo("order-draft:42");
        assertThat(input.getValue().orderDraftId()).isEqualTo(42L);
    }

    @Test
    void flatOfficialExampleFieldsRemainCompatibleForSingleChat() throws Exception {
        when(cards.findSentByTaskId("order-draft:43"))
                .thenReturn(Optional.of(card(43L, 8L, "actor-43")));
        when(confirmations.confirm(43L, 8L, "EVT-43", "REQ-43", "actor-43"))
                .thenReturn(result(CardConfirmationStatus.CONFIRMED));
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-43"},"body":{"msgid":"EVT-43","aibotid":"bot",
                 "chattype":"single","from":{"userid":"actor-43"},
                 "event":{"eventtype":"template_card_event","event_key":"confirm_order",
                   "task_id":"order-draft:43"}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.replyTarget()).isEqualTo("actor-43");
        verify(confirmations).confirm(43L, 8L, "EVT-43", "REQ-43", "actor-43");
    }

    @Test
    void missingActorFailsClosedAndNeverInvokesConfirmation() throws Exception {
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-44"},"body":{"msgid":"EVT-44","aibotid":"bot",
                 "chatid":"chat-44","chattype":"group","from":{},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"confirm_order","task_id":"order-draft:44"}}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.result().status()).isEqualTo(CardConfirmationStatus.REJECTED);
        assertThat(outcome.result().businessCode()).isEqualTo("WECOM_CARD_ACTOR_REQUIRED");
        verify(confirmations, never()).confirm(anyLong(), anyLong(), any(), any(), any());
        verify(events).complete(any(), any(), any());
    }

    @Test
    void duplicateClaimReturnsPersistedOutcomeWithoutRepeatingBusinessUseCase() throws Exception {
        CardInteractionOutcome duplicate = new CardInteractionOutcome(
                "EVT-45",
                "REQ-45",
                "order-draft:45",
                45L,
                "chat-45",
                result(CardConfirmationStatus.ALREADY_CONFIRMED),
                true,
                "claim-duplicate",
                1);
        when(events.claim(any())).thenReturn(CardEventClaim.duplicate(duplicate));
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-45"},"body":{"msgid":"EVT-45","aibotid":"bot",
                 "chatid":"chat-45","chattype":"group","from":{"userid":"actor-45"},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"confirm_order","task_id":"order-draft:45"}}}}
                """);

        assertThat(service.handle(frame)).isEqualTo(duplicate);
        verify(confirmations, never()).confirm(anyLong(), anyLong(), any(), any(), any());
        verify(events, never()).complete(any(), any(), any());
    }

    @Test
    void supplementClickCannotOverwriteAnAlreadyConfirmedCardWithMissingInformation() throws Exception {
        OrderDraftDetailDto draft = mock(OrderDraftDetailDto.class);
        when(draft.status()).thenReturn("CONFIRMED");
        when(draft.draftNo()).thenReturn("OD-46");
        when(draft.revision()).thenReturn(9L);
        when(drafts.detail(46L)).thenReturn(draft);
        when(cards.findSentByTaskId("order-draft:46"))
                .thenReturn(Optional.of(card(46L, 9L, "chat-46")));
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-46"},"body":{"msgid":"EVT-46","aibotid":"bot",
                 "chatid":"chat-46","chattype":"group","from":{"userid":"actor-46"},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"supplement_order","task_id":"order-draft:46"}}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.result().status()).isEqualTo(CardConfirmationStatus.ALREADY_CONFIRMED);
        assertThat(outcome.result().businessCode()).isEqualTo("ORDER_DRAFT_ALREADY_CONFIRMED");
        verify(confirmations, never()).confirm(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void recoveryDoesNotPersistFailureWhileOriginalBusinessLeaseIsStillActive() throws Exception {
        when(events.claim(any())).thenReturn(CardEventClaim.claimed("claim-recovered", 2));
        when(cards.findSentByTaskId("order-draft:47"))
                .thenReturn(Optional.of(card(47L, 10L, "chat-47")));
        when(confirmations.confirm(47L, 10L, "EVT-47", "REQ-47", "actor-47"))
                .thenThrow(BusinessException.conflict("IDEMPOTENCY_CONFLICT", "仍在执行"));
        when(events.hasActiveBusinessLease(any())).thenReturn(true);
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-47"},"body":{"msgid":"EVT-47","aibotid":"bot",
                 "chatid":"chat-47","chattype":"group","from":{"userid":"actor-47"},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"confirm_order","task_id":"order-draft:47"}}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.duplicate()).isTrue();
        assertThat(outcome.result().businessCode()).isEqualTo("ORDER_DRAFT_CARD_EVENT_IN_PROGRESS");
        assertThat(outcome.claimToken()).isEqualTo("claim-recovered");
        verify(events, never()).complete(any(), any(), any());
    }

    @Test
    void unknownOrUnacknowledgedCardCannotAuthorizeAConfirmation() throws Exception {
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-48"},"body":{"msgid":"EVT-48","aibotid":"bot",
                 "chatid":"chat-48","chattype":"group","from":{"userid":"actor-48"},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"confirm_order","task_id":"order-draft:48"}}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.result().status()).isEqualTo(CardConfirmationStatus.REJECTED);
        assertThat(outcome.result().businessCode()).isEqualTo("WECOM_ORDER_DRAFT_CARD_NOT_SENT");
        verify(confirmations, never()).confirm(anyLong(), anyLong(), any(), any(), any());
        verify(events).complete(any(), any(), any());
    }

    @Test
    void cardCallbackMustReturnThroughThePersistedOutboundRoute() throws Exception {
        when(cards.findSentByTaskId("order-draft:49"))
                .thenReturn(Optional.of(card(49L, 11L, "chat-original")));
        JsonNode frame = json.readTree(
                """
                {"headers":{"req_id":"REQ-49"},"body":{"msgid":"EVT-49","aibotid":"bot",
                 "chatid":"chat-attacker","chattype":"group","from":{"userid":"actor-49"},
                 "event":{"eventtype":"template_card_event","template_card_event":{
                   "event_key":"confirm_order","task_id":"order-draft:49"}}}}
                """);

        CardInteractionOutcome outcome = service.handle(frame);

        assertThat(outcome.result().status()).isEqualTo(CardConfirmationStatus.REJECTED);
        assertThat(outcome.result().businessCode()).isEqualTo("WECOM_ORDER_DRAFT_CARD_ROUTE_MISMATCH");
        verify(confirmations, never()).confirm(anyLong(), anyLong(), any(), any(), any());
    }

    private static OrderDraftCard card(long draftId, long revision, String target) {
        return new OrderDraftCard(
                draftId + 1000,
                draftId,
                revision,
                "order-draft:" + draftId,
                target,
                "SENT",
                1);
    }

    private static CardConfirmationResult result(CardConfirmationStatus status) {
        return new CardConfirmationResult(
                status,
                "OD-TEST",
                List.of(),
                status.name(),
                "wecom:actor",
                Instant.parse("2026-08-23T00:00:00Z"));
    }
}
