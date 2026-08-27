package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WecomBusinessCardRunnerTest {

    @Test
    void legacySourceRenderMethodRemainsCompatibleWithRouteAwareCallers() {
        ObjectNode rendered = new ObjectMapper().createObjectNode().put("legacy", true);
        WecomBusinessCardSource legacy = new WecomBusinessCardSource() {
            @Override
            public String domain() {
                return "legacy";
            }

            @Override
            public Optional<ObjectNode> render(long entityId, long entityVersion) {
                return Optional.of(rendered);
            }

            @Override
            public Optional<Route> route(long entityId) {
                return Optional.empty();
            }
        };

        assertThatRouteAwareRenderKeepsLegacyResult(legacy, rendered);
    }

    @Test
    void passesThePersistedRouteIntoRendering() {
        WecomBusinessCardStore cards = mock(WecomBusinessCardStore.class);
        WecomBusinessCardSourceRegistry sources = mock(WecomBusinessCardSourceRegistry.class);
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        WecomOutboundGateway gateway = mock(WecomOutboundGateway.class);
        WecomBusinessCardSource source = mock(WecomBusinessCardSource.class);
        WecomBusinessCardRunner runner = new WecomBusinessCardRunner(cards, sources, tasks, gateway);
        AsyncTaskStore.AsyncTask task = new AsyncTaskStore.AsyncTask(
                7,
                WecomBusinessCardEnqueuer.TASK_TYPE,
                "card:19",
                "RUNNING",
                1,
                3,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60),
                "worker-a",
                null,
                "card-19",
                Instant.EPOCH,
                Instant.EPOCH);
        WecomBusinessCard card = new WecomBusinessCard(
                19, "followup-draft", 41, 3,
                "followup-draft_41_v3_0123456789abcdef0123456789abcdef",
                "GROUP", "wr-group", "PENDING", 0);
        WecomBusinessCardSource.Route route = new WecomBusinessCardSource.Route(
                WecomBusinessCardSource.RouteType.GROUP, "wr-group");

        when(tasks.renewLease(7, "worker-a", WecomBusinessCardRunner.LEASE_EXTENSION)).thenReturn(true);
        when(cards.load(19)).thenReturn(card);
        when(cards.beginSend(19, 1)).thenReturn(new WecomBusinessCardStore.CardSendPermit(
                WecomBusinessCardStore.CardSendAction.SEND, 1));
        when(sources.find("followup-draft")).thenReturn(Optional.of(source));
        when(source.render(41, 3, route)).thenReturn(Optional.of(new ObjectMapper()
                .createObjectNode()
                .put("card_type", "button_interaction")));
        when(gateway.send(any())).thenReturn(new WecomSendResult(
                WecomSendStatus.SUCCESS, "req-1", Instant.EPOCH, null, null, false));

        runner.execute(task);

        verify(source).render(41, 3, route);
        ArgumentCaptor<WecomOutboundMessage> outbound = ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(gateway).send(outbound.capture());
        assertThat(outbound.getValue().templateCard().path("task_id").asText())
                .isEqualTo("followup-draft_41_v3_0123456789abcdef0123456789abcdef");
        verify(cards).recordSent(eq(19L), eq("req-1"), eq(Instant.EPOCH));
    }

    private static void assertThatRouteAwareRenderKeepsLegacyResult(
            WecomBusinessCardSource legacy, ObjectNode expected) {
        assertThat(legacy.render(
                        1,
                        0,
                        new WecomBusinessCardSource.Route(
                                WecomBusinessCardSource.RouteType.GROUP, "wr-group")))
                .contains(expected);
    }
}
