package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.Patterns;
import com.fasterxml.jackson.annotation.JsonProperty;
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
        // V99 数量整数化后服务层按 Integer 解析；这里必须与 SourceSkuMappingWrite 同款收紧，
        // 否则 "3.000" 会穿透校验在服务层炸成 500（2026-08-31 生产实证，复核抽屉连败 4 次）。
        @JsonProperty("quantity_multiplier") @NotBlank
                @Pattern(regexp = Patterns.POSITIVE_INTEGER_QUANTITY, message = "数量乘数必须为正整数")
                String quantityMultiplier,
        @Size(max = 1000) String remark) {}
