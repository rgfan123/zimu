package cn.zimu.fulfillment.connector.wecom;

import java.util.Objects;

/** 企业微信主动文本消息；chatId 对单聊是 userid，对群聊是群 chatid。 */
public record WecomOutboundMessage(String chatId, Type type, String content) {

    public enum Type {
        TEXT("text"),
        MARKDOWN("markdown");

        private final String protocolValue;

        Type(String protocolValue) {
            this.protocolValue = protocolValue;
        }

        String protocolValue() {
            return protocolValue;
        }
    }

    public WecomOutboundMessage {
        chatId = requireText(chatId, "chatId");
        type = Objects.requireNonNull(type, "type");
        content = requireText(content, "content");
    }

    public static WecomOutboundMessage text(String chatId, String content) {
        return new WecomOutboundMessage(chatId, Type.TEXT, content);
    }

    public static WecomOutboundMessage markdown(String chatId, String content) {
        return new WecomOutboundMessage(chatId, Type.MARKDOWN, content);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
