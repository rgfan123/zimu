package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 商品创建输入；价格沿用 decimal-string 契约，日期为 YYYY-MM-DD 字符串。 */
public record ProductWrite(
        /**
         * 商品编码；**留空即由系统发号**（V86 的 {@code app.fill_product_code()} 触发器，
         * 形如 {@code PROD-000123}），与 {@code sku_code} 同一套做法。
         *
         * <p>2026-08-30 之前这里是 {@code @NotBlank}，人必须手填，当天就出了事故：新建
         * 一个商品填成 {@code PROD-QFDY-RICE-5KG}，而库里其余 87 个全是 {@code PROD-LOCAL-Rxxx}
         * ——一次手输就破了整张表的命名规矩，而且没有任何东西拦得住。
         *
         * <p>仍然<b>允许</b>显式传值：历史数据与外部约定的编码必须能存活，不能因为系统
         * 会发号就把「我就要用这个编码」变成不可能。
         */
        @Size(max = 64, message = "商品编码超长") String productCode,
        @NotBlank(message = "商品名称不能为空") @Size(max = 128, message = "商品名称超长") String productName,
        @NotNull(message = "品类不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        @Size(max = 1000, message = "原料描述超长") String ingredients,
        @Size(max = 10, message = "商品标签最多 10 个")
        List<@NotBlank(message = "商品标签不能为空") @Size(max = 32, message = "单个标签超长") String> tags,
        String listedFrom,
        String listedUntil,
        @Min(value = 1, message = "发货时效必须为正整数") Integer leadTimeHours,
        Object purchasePrice,
        Object retailPrice,
        Object otherCost,
        @Size(max = 512, message = "主图引用超长") String mainImageRef,
        Boolean active) {
}
