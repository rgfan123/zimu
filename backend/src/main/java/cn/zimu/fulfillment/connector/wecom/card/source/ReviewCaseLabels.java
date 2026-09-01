package cn.zimu.fulfillment.connector.wecom.card.source;

import java.util.Map;

/**
 * 复核事项枚举 → 人话。
 *
 * <p><b>为什么是「尽力而为」而不是穷举</b>：{@code review_cases.case_type} 与
 * {@code reason_code} 在库里都是自由文本（V1 只约束非空），代码里的创建点也在增加。
 * 想靠一张表穷举，结果必然是新增一个码就在卡片上显示成空白——那比显示英文枚举更糟。
 * 所以未登记的码**原样返回**，读卡的人至少还能拿它去后台搜。
 *
 * <p><b>真正该上卡的是 {@code detail->>'message'}</b>：那是创建复核事项时人写的句子
 * （例如「运单文件下载或解密失败，请重新单聊发送原文件」），比任何枚举翻译都准。
 * 本类只在没有 message 时兜底。
 */
final class ReviewCaseLabels {

    private static final Map<String, String> CASE_TYPES = Map.ofEntries(
            Map.entry("WECOM_FILE", "企微文件"),
            Map.entry("SKU_MAPPING", "商品映射"),
            Map.entry("JD_SKU_MAPPING", "京东商品映射"),
            Map.entry("JD_STOCK", "京东库存"),
            Map.entry("JD_TRACKING", "京东运单"),
            Map.entry("JD_OUTBOUND_PREVIEW", "京东建单预检"),
            Map.entry("CUSTOMER_MATCH", "客户匹配"),
            Map.entry("SKU_MATCH", "商品匹配"),
            Map.entry("WECOM_ORDER_CHANGE", "企微改单"),
            Map.entry("WECOM_ORDER_CANCEL", "企微取消"));

    private static final Map<String, String> TEAMS = Map.of(
            "ORDER_OPS", "订单运营",
            "FULFILLMENT_OPS", "履约运营",
            "SKU_OPS", "商品运营");

    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("CUSTOMER_NOT_MATCHED", "客户在主数据里零命中"),
            Map.entry("CUSTOMER_AMBIGUOUS", "客户在主数据里多命中"),
            Map.entry("SKU_NOT_MATCHED", "商品在主数据里零命中"),
            Map.entry("SKU_AMBIGUOUS", "商品在主数据里多命中"),
            Map.entry("SKU_MAPPING_REQUIRED", "商品还没建映射"),
            Map.entry("STOCK_INSUFFICIENT", "库存不足"),
            Map.entry("ADDRESS_INCOMPLETE", "收货信息不完整"),
            Map.entry("PRICE_MISSING", "缺少价格"),
            Map.entry("TRACKING_MISMATCH", "运单与发货单对不上"),
            Map.entry("RECONCILIATION_REQUIRED", "内外事实不一致，需人工对账"),
            Map.entry("WECOM_TRACKING_FILE_REVIEW", "企微运单文件没能入库"),
            Map.entry("JD_SKU_MAPPING_BLOCKED", "京东商品映射缺失，建单被拦"),
            Map.entry("JD_STOCK_BLOCKED", "京东库存不足，建单被拦"),
            Map.entry("JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED", "京东建单预检未通过"),
            Map.entry("JD_TRACKING_TERMINAL_EXCEPTION", "京东出库单进入取消/拉回/拒收等异常终态"),
            Map.entry("JD_TRACKING_CARGO_MISMATCH", "京东货品与建单快照不一致，需人工核对"),
            Map.entry("JD_TRACKING_BACKFILLED_PENDING_REVIEW", "运单已回填但需人工确认"));

    private ReviewCaseLabels() {}

    static String caseType(String code) {
        return lookup(CASE_TYPES, code);
    }

    static String team(String code) {
        return lookup(TEAMS, code);
    }

    static String reason(String code) {
        return lookup(REASONS, code);
    }

    /** 未登记一律原样返回：读卡的人还能拿这个码去后台搜，显示空白就什么都不剩了。 */
    private static String lookup(Map<String, String> table, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return table.getOrDefault(code, code);
    }
}
