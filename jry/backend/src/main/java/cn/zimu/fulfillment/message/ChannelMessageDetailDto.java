package cn.zimu.fulfillment.message;

import java.time.Instant;
import java.util.List;

/** Whitelisted evidence projection. The raw JSON stays behind its controlled reference. */
public record ChannelMessageDetailDto(
        String id,
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
        String rawPayloadRef,
        String submissionId,
        Instant receivedAt,
        List<ChannelMediaEvidenceDto> mediaRefs) {}
