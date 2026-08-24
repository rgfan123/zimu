package cn.zimu.fulfillment.notification;

import java.time.Instant;

/** Admin-safe trace row explaining one source fact's delivery outcome for one recipient. */
public record WecomNotificationDeliveryDto(
        String deliveryId,
        String batchId,
        String sourceType,
        String sourceId,
        String notificationKind,
        String responsibleTeam,
        String recipientDisplayName,
        String recipientUserid,
        String status,
        int attemptCount,
        String requestId,
        String reasonCode,
        String reasonMessage,
        String alertId,
        String alertKey,
        String alertSeverity,
        Instant windowStart,
        Instant updatedAt) {}
