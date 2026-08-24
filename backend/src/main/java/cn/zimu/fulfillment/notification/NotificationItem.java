package cn.zimu.fulfillment.notification;

import java.util.Map;
import java.util.Objects;

/** One privacy-minimized business fact captured for a WeCom digest. */
public record NotificationItem(
        long id,
        String sourceType,
        long sourceId,
        String notificationKind,
        Map<String, Object> summary) {

    public NotificationItem {
        sourceType = requireText(sourceType, "sourceType");
        notificationKind = requireText(notificationKind, "notificationKind");
        summary = Map.copyOf(Objects.requireNonNull(summary, "summary"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
