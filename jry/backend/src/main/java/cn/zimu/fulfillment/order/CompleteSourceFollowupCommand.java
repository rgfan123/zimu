package cn.zimu.fulfillment.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 人工确认多 Shipment 的来源平台后续回传已完成。 */
public record CompleteSourceFollowupCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @NotBlank @Size(max = 1000) String note) {}
