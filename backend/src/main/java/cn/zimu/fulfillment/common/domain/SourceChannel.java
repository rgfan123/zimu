package cn.zimu.fulfillment.common.domain;

/** 订单来源渠道，与履约方分离。 */
public enum SourceChannel {
    CAISHIXIAN,
    JUFUBAO,
    FEIXIANG,
    ZHONGHUI,
    /** 大者来源；V41 来源归因纠正后的技术值。 */
    DAZHE,
    /** 历史技术值：对应大者 15 列来源文件。 */
    WANGQI,
    /** 万齐订单管理导出 52 列来源文件。 */
    WANQI,
    WECOM
}
