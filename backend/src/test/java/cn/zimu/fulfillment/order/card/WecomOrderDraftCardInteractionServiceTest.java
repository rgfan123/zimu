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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WecomOrderDraftCardInteractionServiceTest {

    private final ObjectMapper json = new ObjectMapper();
    private WecomOrderDraftCardEventStore events;
    private OrderDraftCardConfirmationService confirmations;
    private OrderDraftQueryService drafts;
    private WecomOrderDraftCardInteractionService service;

    @BeforeEach
    void setUp() {
        events = mock(WecomOrderDraftCardEventStore.class);
        confirmations = mock(OrderDraftCardConfirmationService.class);
        drafts = mock(OrderDraftQueryService.class);
        service = new WecomOrderDraftCardInteractionService(events, confirmations, drafts);
        when(events.claim(any())).thenReturn(CardEventClaim.claimed("claim-test", 1));
    }

    @Test
    void nestedOfficialEventUsesCallbackUseridAndStableTaskId() throws Exception {
        CardConfirmationResult confirmed = result(CardConfirmationStatus.CONFIRMED);
        when(confirmations.confirm(42L, "EVT-42", "REQ-42", "actor-42"))
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
        verify(confirmations).confirm(42L, "EVT-42", "REQ-42", "actor-42");
        ArgumentCaptor<CardEventInput> input = ArgumentCaptor.forClass(CardEventInput.class);
        verify(events).complete(input.capture(), any(), any());
        assertThat(input.getValue().eventKey()).isEqualTo("confirm_order");
        assertThat(input.getValue().taskId()).isEqualTo("order-draft:42");
        assertThat(input.getValue().orderDraftId()).isEqualTo(42L);
    }

    @Test
    void flatOfficialExampleFieldsRemainCompatibleForSingleChat() throws Exception {
        when(confirmations.confirm(43L, "EVT-43", "REQ-43", "actor-43"))
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
        verify(confirmations).confirm(43L, "EVT-43", "REQ-43", "actor-43");
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
        verify(confirmations, never()).confirm(anyLong(), any(), any(), any());
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
        verify(confirmations, never()).confirm(anyLong(), any(), any(), any());
        verify(events, never()).complete(any(), any(), any());
    }

    @Test
    void supplementClickCannotOverwriteAnAlreadyConfirmedCardWithMissingInformation() throws Exception {
        OrderDraftDetailDto draft = mock(OrderDraftDetailDto.class);
        when(draft.status()).thenReturn("CONFIRMED");
        when(draft.draftNo()).thenReturn("OD-46");
        when(drafts.detail(46L)).thenReturn(draft);
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
        verify(confirmations, never()).confirm(anyLong(), any(), any(), any());
    }

    @Test
    void recoveryDoesNotPersistFailureWhileOriginalBusinessLeaseIsStillActive() throws Exception {
        when(events.claim(any())).thenReturn(CardEventClaim.claimed("claim-recovered", 2));
        when(confirmations.confirm(47L, "EVT-47", "REQ-47", "actor-47"))
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
