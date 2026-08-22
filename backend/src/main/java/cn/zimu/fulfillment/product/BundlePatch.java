package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 静态礼包更新输入；items 非 null 时整表替换组件。 */
public record BundlePatch(
        @NotNull(message = "期望版本不能为空") @Min(0) Long expectedVersion,
        @Size(max = 200, message = "礼包名称超长") String bundleName,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        @Size(max = 64, message = "条码超长") String barcode,
        String description,
        String taxRate,
        String settlementCost,
        @Pattern(regexp = "DRAFT|ACTIVE|INACTIVE", message = "状态只能是 DRAFT/ACTIVE/INACTIVE") String status,
        @Size(min = 1, message = "礼包至少需要一个组件") List<@Valid BundleItemInput> items) {}
