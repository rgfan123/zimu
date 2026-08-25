package cn.zimu.fulfillment.sku;

import java.util.List;

/**
 * 京东商品名称比对内核：归一化与相互包含判定的唯一实现。
 *
 * <p>映射核对（{@link JdSkuMappingCheckService}，advisory）与出库门禁
 * （{@link ShipmentJdSkuMappingGateService}，fail-closed）此前各持一份逐字节相同的
 * normalize/包含判定，而参照字段集与「无参照名」的裁决方向相反，操作员在核对页与
 * 提交时会得到矛盾答案（设计收敛票 01）。内核归一后比对规则只此一处；参照名取哪些
 * 字段、{@link Verdict#NO_REFERENCE} 如何执法仍由调用方按各自语义决定，但必须对
 * 三值裁决显式表态，不允许把「没有参照名」吞成比对结论。
 */
final class JdGoodsNameMatch {

    /** 三值裁决：命中 / 有参照但全不命中 / 没有任何非空参照名可比。 */
    enum Verdict { MATCHED, MISMATCHED, NO_REFERENCE }

    private JdGoodsNameMatch() {}

    /**
     * 与历史实现逐语义等价：去空白归一后 equals 或双向 contains 任一参照命中即
     * MATCHED；空白参照跳过不计入「有参照」。远端名为空白时保持历史 contains("")
     * 语义（命中任意参照）。
     */
    static Verdict verdict(String remoteName, List<String> references) {
        String normalizedRemote = normalize(remoteName);
        boolean hasReference = false;
        for (String reference : references) {
            String normalized = normalize(reference);
            if (normalized.isEmpty()) {
                continue;
            }
            hasReference = true;
            if (normalizedRemote.equals(normalized)
                    || normalizedRemote.contains(normalized)
                    || normalized.contains(normalizedRemote)) {
                return Verdict.MATCHED;
            }
        }
        return hasReference ? Verdict.MISMATCHED : Verdict.NO_REFERENCE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }
}
