package cn.zimu.fulfillment.connector.wecom;

import org.springframework.stereotype.Component;

/**
 * 会话 Agent 自然语言回答的唯一企微出口。
 *
 * <p>#178 只建立出口接缝并复用 {@link WecomOutboundGateway}；会话 reply_mode 是否允许
 * Agent 开口属于 #179，后续应只在本组件增加策略门禁，路由 Worker 无需改写。
 */
@Component
public class WecomChatAgentReplyDispatcher {

    private final WecomOutboundGateway outbound;

    public WecomChatAgentReplyDispatcher(WecomOutboundGateway outbound) {
        this.outbound = outbound;
    }

    public WecomSendResult send(String chatId, String answer) {
        return outbound.send(WecomOutboundMessage.markdown(chatId, answer));
    }
}
