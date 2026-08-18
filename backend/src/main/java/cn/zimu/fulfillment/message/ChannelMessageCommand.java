package cn.zimu.fulfillment.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Canonical immutable input produced by the channel adapter before any business interpretation.
 *
 * <p>05 票：`senderIdentityType`/`senderAccessType` 由消息入口在提供真实客户渠道身份时显式声明；
 * 未声明按普通传输身份（EMPLOYEE）处理，绝不建立客户绑定。
 */
public record ChannelMessageCommand(
        String corpId,
        String connectionId,
        String botId,
        String messageId,
        String chatId,
        String chatType,
        String senderUserId,
        String messageType,
        String content,
        String quoteType,
        String quoteContent,
        JsonNode rawPayload,
        String senderIdentityType,
        String senderAccessType) {

    /**
     * 兼容既有入口（普通微信群/群机器人）：不携带渠道身份分类，按 EMPLOYEE 传输身份落库。
     * 提供真实客户渠道身份的入口必须使用完整构造器并显式声明 {@code senderIdentityType="CUSTOMER"}。
     */
    public ChannelMessageCommand(
            String corpId,
            String connectionId,
            String botId,
            String messageId,
            String chatId,
            String chatType,
            String senderUserId,
            String messageType,
            String content,
            String quoteType,
            String quoteContent,
            JsonNode rawPayload) {
        this(
                corpId,
                connectionId,
                botId,
                messageId,
                chatId,
                chatType,
                senderUserId,
                messageType,
                content,
                quoteType,
                quoteContent,
                rawPayload,
                null,
                null);
    }
}
