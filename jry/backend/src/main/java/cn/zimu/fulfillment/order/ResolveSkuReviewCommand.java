package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 人工确认既有 SKU 主数据与来源映射。 */
public record ResolveSkuReviewCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @JsonProperty("sku_id") @NotBlank @Pattern(regexp = "^[1-9][0-9]*$") String skuId,
        @JsonProperty("source_channel") @NotNull SourceChannel sourceChannel,
        @JsonProperty("source_sku_ref") @NotBlank @Size(max = 128) String sourceSkuRef,
        @JsonProperty("quantity_multiplier") @NotBlank @DecimalMin(value = "0", inclusive = false)
                @Digits(integer = 15, fraction = 3) String quantityMultiplier,
        @Size(max = 1000) String remark) {}
