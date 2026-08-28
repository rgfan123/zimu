package cn.zimu.fulfillment.connector.schedule;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动发货问题的归类：把一堆阻断码翻译成「人该做什么」的四类。
 *
 * <p><b>存在的唯一理由是不许笼统报「失败」</b>。生产上真正发生过的事：运营收到
 * 「京东 SKU 映射门禁未通过」，以为是缺货去催补货，实际是某个 SKU 的换算系数没配；
 * 也发生过反过来的——报「未配置映射」，而映射好好配着（见
 * {@link #MAPPING_FALSE_POSITIVE_PRONE}）。两种误读的代价都是几个小时白跑。
 *
 * <p><b>最关键的一条判据</b>：{@code JD_SKU_MAPPING_GATE_BLOCKED} 与缺货**毫无关系**。
 * {@code ShipmentJdStockCheckService} 在映射门禁未过时直接短路返回，
 * 根本没有向京东发出库存查询（该类 112-120 行的 guard 在 131 行的 queryStock 之前）。
 * 也就是说门禁阻断时「有没有货」这个问题从来没被问过。把它播报成缺货，
 * 14 种原因会错 14 次。
 *
 * <p>同理 {@code JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED} 也不是缺货：
 * 京东响应里压根没有目标仓那一行，而「缺行」不能被解释成 0 库存——
 * 这正是 {@code ShipmentJdStockCheckService} 拒绝那么解释的原因。
 *
 * <p>本类是纯函数，不碰数据库，因此判据可以被单元测试钉死。
 */
final class AutoShipReasons {

    private AutoShipReasons() {}

    /** 真缺货，且只有它是真缺货。 */
    static final String STOCK_INSUFFICIENT_CODE = "JD_STOCK_INSUFFICIENT";

    /** 映射门禁的外层码；真正的原因在 blocker 的 {@code mapping_issue_code} 里，共 14 种。 */
    static final String MAPPING_GATE_CODE = "JD_SKU_MAPPING_GATE_BLOCKED";

    /**
     * 京东给了答复但答复不可用——不是缺货，是「不知道有没有货」，默认阻断。
     * 把这几个混进缺货会让人去补根本不缺的货。
     */
    private static final Set<String> JD_ANSWER_UNUSABLE_CODES = Set.of(
            "JD_STOCK_QUERY_FAILED",
            "JD_STOCK_RESPONSE_INVALID",
            "JD_STOCK_RESPONSE_AMBIGUOUS",
            "JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED");

    /**
     * 已知会误报的映射原因码：生产上出现过「报未配置映射、但映射其实好好配着」。
     * 播报时要额外提示「先核对映射再动手」，避免运营按字面意思去重配一遍已有的映射。
     */
    private static final Set<String> MAPPING_FALSE_POSITIVE_PRONE = Set.of(
            "MAPPING_MISSING", "INTERNAL_SKU_MISSING");

    /**
     * 「确认了却根本没建成京东单」——最危险的一种，故排在最前。
     *
     * <p>典型成因是 {@code system:scheduled-pull} 不在
     * {@code app.jd.outbound-authorized-operators} 白名单里：
     * {@code requireAuthorized} 是 {@code submit} 的第一行，抛在
     * {@code persistSubmitIntent} 之前，因此 {@code shipment_jd_outbounds} 里
     * **一行痕迹都不会留下**。只看失败表的话，这种情况和「一切正常」长得一模一样。
     */
    static final String NOT_SUBMITTED_CODE = "JD_OUTBOUND_NOT_SUBMITTED";

    /** 问题大类。顺序即播报优先级：越靠前越需要人立刻动手。 */
    enum Category {
        /** 批次确认了，但京东单没建成，且失败表里没有痕迹。货没发出去。 */
        NOT_SUBMITTED("未建单"),
        /** 真缺货：目标仓可用库存 < 需求件数。补货或换货。 */
        STOCK_INSUFFICIENT("缺货"),
        /** 商品映射/校验未通过：主数据问题，与库存无关。 */
        SKU_MAPPING("映射校验"),
        /** 京东未给出可用答复：查询失败/响应无效/缺目标仓行。重试或找京东。 */
        JD_ANSWER_UNUSABLE("京东无答复"),
        /** 来源批次自身有阻断行（缺 SKU、数据问题），根本没到京东那一步。 */
        SOURCE_BLOCKED("批次阻断"),
        /** 拉取失败：登录/网络/平台报错。 */
        PULL_FAILED("拉取失败"),
        /** 归不了类的：如实说不知道，别硬塞进上面任何一类。 */
        OTHER("其它");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * 归类一个京东侧阻断码。
     *
     * @param blockerCode blocker 的外层 {@code code}
     */
    static Category categorize(String blockerCode) {
        if (blockerCode == null || blockerCode.isBlank()) {
            return Category.OTHER;
        }
        if (NOT_SUBMITTED_CODE.equals(blockerCode)) {
            return Category.NOT_SUBMITTED;
        }
        if (STOCK_INSUFFICIENT_CODE.equals(blockerCode)) {
            return Category.STOCK_INSUFFICIENT;
        }
        if (MAPPING_GATE_CODE.equals(blockerCode)) {
            return Category.SKU_MAPPING;
        }
        if (JD_ANSWER_UNUSABLE_CODES.contains(blockerCode)) {
            return Category.JD_ANSWER_UNUSABLE;
        }
        return Category.OTHER;
    }

    /**
     * 一个阻断码的最具体表述。
     *
     * <p>映射门禁一律返回内层的 {@code mapping_issue_code}——外层码对所有 14 种原因
     * 都是同一个字符串，只播报它等于什么都没说。内层缺失时退回外层，
     * 但退回本身也是信息：说明门禁判了阻断却没给出逐项明细。
     */
    static String specificCode(String blockerCode, String mappingIssueCode) {
        if (MAPPING_GATE_CODE.equals(blockerCode)
                && mappingIssueCode != null
                && !mappingIssueCode.isBlank()) {
            return mappingIssueCode;
        }
        return blockerCode == null || blockerCode.isBlank() ? "UNKNOWN" : blockerCode;
    }

    /** 这个映射原因码是否属于「已知会误报」，播报时要提示先核对。 */
    static boolean falsePositiveProne(String specificCode) {
        return MAPPING_FALSE_POSITIVE_PRONE.contains(specificCode);
    }

    /**
     * 把一批阻断码汇总成播报用的「类别 → 具体码列表」。
     *
     * <p>返回 {@link LinkedHashMap}，键按 {@link Category} 声明顺序排列——
     * 卡面字段只有 6 行，先说缺货再说映射，读者第一眼看到的必须是最需要动手的那件事。
     *
     * @param blockers 每项 {@code {code, mapping_issue_code}}，两个键都可能缺
     */
    static Map<Category, List<String>> summarize(List<Map<String, String>> blockers) {
        Map<Category, Set<String>> grouped = new LinkedHashMap<>();
        for (Map<String, String> blocker : blockers) {
            String code = blocker.get("code");
            Category category = categorize(code);
            String specific = specificCode(code, blocker.get("mapping_issue_code"));
            grouped.computeIfAbsent(category, ignored -> new LinkedHashSet<>()).add(specific);
        }
        Map<Category, List<String>> ordered = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            Set<String> codes = grouped.get(category);
            if (codes != null && !codes.isEmpty()) {
                ordered.put(category, List.copyOf(codes));
            }
        }
        return Map.copyOf(ordered).isEmpty() ? Map.of() : ordered;
    }

    /**
     * 一行人可读的归类摘要，例如
     * {@code 缺货: JD_STOCK_INSUFFICIENT; 映射校验: MAPPING_MISSING(疑似误报), UNIT_CONVERSION_MISSING}。
     *
     * <p>只由受控词表拼成，不含任何自由文本——本串会被渲染进企微卡片。
     */
    static String describe(Map<Category, List<String>> summary) {
        if (summary.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        summary.forEach((category, codes) -> {
            if (!text.isEmpty()) {
                text.append("; ");
            }
            text.append(category.label()).append(": ");
            for (int index = 0; index < codes.size(); index++) {
                if (index > 0) {
                    text.append(", ");
                }
                text.append(codes.get(index));
                if (falsePositiveProne(codes.get(index))) {
                    text.append("(疑似误报)");
                }
            }
        });
        return text.toString();
    }
}
