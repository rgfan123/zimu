package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 静态礼包及其当前 BOM 的原子创建输入。 */
public record BundleWrite(
        @NotBlank(message = "礼包编码不能为空") @Size(max = 64, message = "礼包编码超长") String bundleCode,
        @NotBlank(message = "礼包名称不能为空") @Size(max = 200, message = "礼包名称超长") String bundleName,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        @Size(max = 64, message = "条码超长") String barcode,
        String description,
        String taxRate,
        String settlementCost,
        @Pattern(regexp = "DRAFT|ACTIVE|INACTIVE", message = "状态只能是 DRAFT/ACTIVE/INACTIVE") String status,
        @NotNull(message = "组件清单不能为空") @Size(min = 1, message = "礼包至少需要一个组件")
                List<@Valid BundleItemInput> items) {}
