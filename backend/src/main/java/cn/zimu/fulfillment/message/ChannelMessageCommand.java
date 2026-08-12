package cn.zimu.fulfillment.message;

import com.fasterxml.jackson.databind.JsonNode;

/** Canonical immutable input produced by the channel adapter before any business interpretation. */
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
        JsonNode rawPayload) {}
