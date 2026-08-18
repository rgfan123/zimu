package cn.zimu.fulfillment.common.dto;

/** OpenAPI 契约中的字符串模式常量。 */
public final class Patterns {

    private Patterns() {}

    /** 非负十进制字符串，最多三位小数。 */
    public static final String DECIMAL_QUANTITY = "^(0|[1-9][0-9]*)(\\.[0-9]{1,3})?$";

    /** 正十进制字符串，最多三位小数，排除 0。 */
    public static final String POSITIVE_DECIMAL_QUANTITY = "^(?!0(?:\\.0{1,3})?$)(0|[1-9][0-9]*)(\\.[0-9]{1,3})?$";

    /** 正整数件数字符串（京东件数换算，排除 0 与前导零）。 */
    public static final String POSITIVE_INTEGER_QUANTITY = "^[1-9][0-9]*$";

    /** 非负人民币金额：最多十二位整数、两位小数。 */
    public static final String COMMERCIAL_PRICE = "^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?$";

    /** 标识符：正整数形式的字符串。 */
    public static final String IDENTIFIER = "^[1-9][0-9]*$";
}
