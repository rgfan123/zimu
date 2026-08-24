package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.order.domain.LineType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 订单行输入。 */
public record OrderItemInput(
        @Size(max = 128, message = "来源行标识超长") String sourceLineRef,
        @NotNull(message = "行类型不能为空") LineType lineType,
        @Size(max = 64, message = "SKU 编码超长") String skuCode,
        @Size(max = 128, message = "来源 SKU 标识超长") String sourceSkuRef,
        @NotBlank(message = "商品名称不能为空") @Size(max = 255, message = "商品名称超长") String productName,
        @NotBlank(message = "规格不能为空") @Size(max = 255, message = "规格超长") String specification,
        @NotBlank(message = "单位不能为空") @Size(max = 32, message = "单位超长") String unit,
        @NotBlank(message = "数量不能为空")
                @Pattern(regexp = Patterns.POSITIVE_DECIMAL_QUANTITY, message = "数量必须为正数且最多三位小数")
        String quantity,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "静态礼包标识符无效") String bundleId,
        List<@Valid BundleComponentInput> components) {

    /** 兼容普通商品和当单定制礼包调用方；静态礼包由来源适配器显式传 bundleId。 */
    public OrderItemInput(
            String sourceLineRef,
            LineType lineType,
            String skuCode,
            String sourceSkuRef,
            String productName,
            String specification,
            String unit,
            String quantity,
            List<BundleComponentInput> components) {
        this(sourceLineRef, lineType, skuCode, sourceSkuRef, productName, specification, unit, quantity, null, components);
    }
}
