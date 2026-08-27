package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WecomChatAgentReplyDispatcherTest {

    @Test
    void agentAnswerUsesTheExistingOutboundGatewayAsMarkdown() {
        WecomOutboundGateway outbound = mock(WecomOutboundGateway.class);
        WecomSendResult expected = new WecomSendResult(
                WecomSendStatus.SUCCESS,
                "req-178",
                Instant.parse("2026-08-27T00:00:00Z"),
                null,
                null,
                false);
        when(outbound.send(any())).thenReturn(expected);
        WecomChatAgentReplyDispatcher dispatcher = new WecomChatAgentReplyDispatcher(outbound);

        WecomSendResult actual = dispatcher.send("chat-178", "商品 M5 当前库存 18 件");

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<WecomOutboundMessage> message =
                ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(outbound).send(message.capture());
        assertThat(message.getValue().chatId()).isEqualTo("chat-178");
        assertThat(message.getValue().type()).isEqualTo(WecomOutboundMessage.Type.MARKDOWN);
        assertThat(message.getValue().content()).isEqualTo("商品 M5 当前库存 18 件");
    }
}
