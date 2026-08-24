package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.product.ProductWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** 商品档案创建命令：一次提交创建新商品及其首个 SKU。 */
public record ProductWithInitialSkuWrite(
        @NotNull(message = "商品不能为空") @Valid ProductWrite product,
        @NotNull(message = "SKU 不能为空") @Valid InitialSkuWrite sku) {
}
