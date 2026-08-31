package cn.zimu.fulfillment.common.dto;

/** OpenAPI 契约中的字符串模式常量。 */
public final class Patterns {

    private Patterns() {}

    // 2026-08-31 商品数量整数化（V99）：数量域一律正整数，十进制数量模式随之退役——
    // 净含量等「含量」字段不属数量域，各自另有校验（见 SkuPackagingIdentity）。

    /** 正整数件数字符串（全部商品数量输入的统一门禁，排除 0 与前导零）。 */
    public static final String POSITIVE_INTEGER_QUANTITY = "^[1-9][0-9]*$";

    /** 非负人民币金额：最多十二位整数、两位小数。 */
    public static final String COMMERCIAL_PRICE = "^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?$";

    /** 标识符：正整数形式的字符串。 */
    public static final String IDENTIFIER = "^[1-9][0-9]*$";
}
