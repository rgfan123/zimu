package cn.zimu.fulfillment.notification;

import java.time.Instant;
import java.util.List;

/** Persisted 5-minute notification digest claimed by one worker lease. */
public record NotificationBatch(
        long id,
        String responsibleTeam,
        Instant windowStart,
        List<NotificationItem> items) {

    public NotificationBatch {
        if (responsibleTeam == null || responsibleTeam.isBlank()) {
            throw new IllegalArgumentException("responsibleTeam must not be blank");
        }
        if (windowStart == null) {
            throw new IllegalArgumentException("windowStart must not be null");
        }
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}
