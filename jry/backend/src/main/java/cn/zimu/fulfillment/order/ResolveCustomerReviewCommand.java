package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 人工确认既有客户主数据并解决客户匹配复核。 */
public record ResolveCustomerReviewCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @JsonProperty("customer_id") @NotBlank @Pattern(regexp = "^[1-9][0-9]*$") String customerId,
        @JsonProperty("source_channel") @NotNull SourceChannel sourceChannel,
        @JsonProperty("source_customer_ref") @NotBlank @Size(max = 128) String sourceCustomerRef,
        @Size(max = 1000) String remark) {}
