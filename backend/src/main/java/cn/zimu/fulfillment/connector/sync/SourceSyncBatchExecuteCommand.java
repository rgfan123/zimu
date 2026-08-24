package cn.zimu.fulfillment.connector.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 批量来源回传：每个 Shipment 独立携带检查哈希与幂等键。 */
public record SourceSyncBatchExecuteCommand(
        @NotEmpty @Size(max = 100) List<@Valid Item> items) {

    public SourceSyncBatchExecuteCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(
            @Min(1) long shipmentId,
            @NotBlank
            @Pattern(regexp = "^[0-9a-f]{64}$", message = "expected_check_hash 必须是 SHA-256")
            String expectedCheckHash,
            @NotBlank @Size(min = 8, max = 255) String idempotencyKey) {}
}
