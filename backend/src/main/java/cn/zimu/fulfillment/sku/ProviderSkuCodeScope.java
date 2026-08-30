package cn.zimu.fulfillment.sku;

import java.util.Objects;

/** 区分真实履约方外码与只供子牧系统内部确定性路由的 TP 自映射。 */
public enum ProviderSkuCodeScope {
    INTERNAL_ROUTING,
    PROVIDER_EXTERNAL;

    public static ProviderSkuCodeScope resolve(
            String providerType, String skuCode, String providerSkuCode) {
        return "THIRD_PARTY".equals(providerType)
                        && Objects.equals(skuCode, providerSkuCode)
                ? INTERNAL_ROUTING
                : PROVIDER_EXTERNAL;
    }
}
