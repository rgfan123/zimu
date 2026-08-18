package cn.zimu.fulfillment.message;

import java.time.Instant;

public record ChannelMessageSummaryDto(
        String id,
        String corpId,
        String connectionId,
        String botId,
        String messageId,
        String chatId,
        String chatType,
        String senderUserId,
        String messageType,
        String contentPreview,
        Instant receivedAt) {}
