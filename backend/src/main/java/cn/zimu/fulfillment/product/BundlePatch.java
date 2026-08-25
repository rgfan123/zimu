package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 静态礼包更新输入；组件清单非空时整表替换。 */
public record BundlePatch(
        @Min(0) long expectedVersion,
        @Size(max = 200, message = "礼包名称超长") String bundleName,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        @Size(max = 64, message = "条码超长") String barcode,
        String description,
        Object taxRate,
        Object settlementCost,
        @Pattern(regexp = "DRAFT|ACTIVE|INACTIVE", message = "状态只能是 DRAFT/ACTIVE/INACTIVE") String status,
        List<@Valid BundleItemInput> items) {}
