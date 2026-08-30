package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 商品创建输入；日期为 YYYY-MM-DD 字符串。 */
public record ProductWrite(
        @NotBlank(message = "商品编码不能为空") @Size(max = 64, message = "商品编码超长") String productCode,
        @NotBlank(message = "商品名称不能为空") @Size(max = 128, message = "商品名称超长") String productName,
        @NotNull(message = "品类不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        @Size(max = 1000, message = "原料描述超长") String ingredients,
        @Size(max = 10, message = "商品标签最多 10 个")
        List<@NotBlank(message = "商品标签不能为空") @Size(max = 32, message = "单个标签超长") String> tags,
        String listedFrom,
        String listedUntil,
        @Min(value = 1, message = "发货时效必须为正整数") Integer leadTimeHours,
        @Size(max = 512, message = "主图引用超长") String mainImageRef,
        Boolean active) {
}
