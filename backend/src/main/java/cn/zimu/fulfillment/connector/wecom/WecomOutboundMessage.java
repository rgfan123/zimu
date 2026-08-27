package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * 企业微信主动消息；chatId 对单聊是 userid，对群聊是群 chatid。
 *
 * <p>{@code FILE} 为文件消息（Issue #84）：协议体为 {@code msgtype=file} +
 * {@code file.media_id}（官方 path/101463），不携带 content 文本；text/markdown 保持
 * {@code content} 语义不变。主动 text 不在当前协议联合类型内，构造时必须拒绝。
 * media_id 是 3 天有效的临时引用，调用方应立即使用、不得持久化明文。
 */
public record WecomOutboundMessage(
        String chatId,
        Type type,
        String content,
        String mediaId,
        JsonNode templateCard) {

    public enum Type {
        MARKDOWN("markdown"),
        FILE("file"),
        IMAGE("image"),
        TEMPLATE_CARD("template_card");

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
            case FILE, IMAGE -> {
                mediaId = requireText(mediaId, "mediaId");
                if (content != null || templateCard != null) {
                    throw new IllegalArgumentException("file/image message must carry only a media id");
                }
            }
            case TEMPLATE_CARD -> {
                if (templateCard == null || !templateCard.isObject()) {
                    throw new IllegalArgumentException("templateCard must be a JSON object");
                }
                requireText(templateCard.path("card_type").asText(null), "templateCard.card_type");
                templateCard = templateCard.deepCopy();
                if (content != null || mediaId != null) {
                    throw new IllegalArgumentException("TEMPLATE_CARD must not carry text or a media id");
                }
            }
            case MARKDOWN -> {
                content = requireText(content, "content");
                if (mediaId != null || templateCard != null) {
                    throw new IllegalArgumentException("text/markdown message must carry only content");
                }
            }
        }
    }

    public static WecomOutboundMessage text(String chatId, String content) {
        throw new IllegalArgumentException("active WeCom text messages are not supported; use markdown");
    }

    public static WecomOutboundMessage markdown(String chatId, String content) {
        return new WecomOutboundMessage(chatId, Type.MARKDOWN, content, null, null);
    }

    public static WecomOutboundMessage file(String chatId, String mediaId) {
        return new WecomOutboundMessage(chatId, Type.FILE, null, mediaId, null);
    }

    /** 图片消息（msgtype=image + image.media_id）：清单表格图等随卡视觉件。 */
    public static WecomOutboundMessage image(String chatId, String mediaId) {
        return new WecomOutboundMessage(chatId, Type.IMAGE, null, mediaId, null);
    }

    public static WecomOutboundMessage templateCard(String chatId, JsonNode templateCard) {
        return new WecomOutboundMessage(chatId, Type.TEMPLATE_CARD, null, null, templateCard);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
