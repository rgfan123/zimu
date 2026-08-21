package cn.zimu.fulfillment.connector.wecom;

import java.util.Objects;

/**
 * 企业微信主动消息；chatId 对单聊是 userid，对群聊是群 chatid。
 *
 * <p>{@code FILE} 为文件消息（Issue #84）：协议体为 {@code msgtype=file} +
 * {@code file.media_id}（官方 path/101463），不携带 content 文本；text/markdown 保持
 * {@code content} 语义不变。media_id 是 3 天有效的临时引用，调用方应立即使用、不得持久化明文。
 */
public record WecomOutboundMessage(String chatId, Type type, String content, String mediaId) {

    public enum Type {
        TEXT("text"),
        MARKDOWN("markdown"),
        FILE("file");

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
        switch (type) {
            case FILE -> {
                mediaId = requireText(mediaId, "mediaId");
                if (content != null) {
                    throw new IllegalArgumentException("FILE message must not carry text content");
                }
            }
            case TEXT, MARKDOWN -> {
                content = requireText(content, "content");
                if (mediaId != null) {
                    throw new IllegalArgumentException("text/markdown message must not carry a media id");
                }
            }
        }
    }

    public static WecomOutboundMessage text(String chatId, String content) {
        return new WecomOutboundMessage(chatId, Type.TEXT, content, null);
    }

    public static WecomOutboundMessage markdown(String chatId, String content) {
        return new WecomOutboundMessage(chatId, Type.MARKDOWN, content, null);
    }

    public static WecomOutboundMessage file(String chatId, String mediaId) {
        return new WecomOutboundMessage(chatId, Type.FILE, null, mediaId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
