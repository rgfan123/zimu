package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 人工确认既有 SKU 主数据与来源映射。 */
public record ResolveSkuReviewCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @JsonProperty("sku_id") @NotBlank @Pattern(regexp = "^[1-9][0-9]*$") String skuId,
        @JsonProperty("source_channel") @NotNull SourceChannel sourceChannel,
        @JsonProperty("source_sku_ref") @NotBlank @Size(max = 128) String sourceSkuRef,
        // 远端 59708d7a 已把旧字符串契约收紧为正整数；这里进一步令传输类型与领域类型一致，
        // 由专用反序列化器拒绝 3.000/小数/越界值并返回字段级 400，而不是留到服务层解析。
        @JsonProperty("quantity_multiplier") @NotNull @Positive
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer quantityMultiplier,
        @Size(max = 1000) String remark) {}
