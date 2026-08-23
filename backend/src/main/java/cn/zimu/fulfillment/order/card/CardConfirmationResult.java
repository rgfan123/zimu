package cn.zimu.fulfillment.order.card;

import java.time.Instant;
import java.util.List;

/** Business outcome used by the card event and update-card fast path. */
public record CardConfirmationResult(
        CardConfirmationStatus status,
        String draftNo,
        List<String> missingFields,
        String businessCode,
        String confirmedBy,
        Instant processedAt) {

    public CardConfirmationResult {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }
}
