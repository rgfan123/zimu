package cn.zimu.fulfillment.order.domain;

/** 结账方式。 */
public enum SettlementMethod {
    /** 来源文件明确不提供结账信息；仅真实万齐 52 列导入内部使用。 */
    UNSPECIFIED,
    MONTHLY,
    IMMEDIATE,
    CREDIT_TERM,
    PREPAID,
    COD,
    OTHER
}
