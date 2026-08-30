package cn.zimu.fulfillment.sku;

import java.util.Map;
import java.util.Objects;

/** 由 REAL 京东 queryGoodsInfo 写入的内部核验凭证；普通主数据接口不能伪造该字段。 */
final class JdGoodsVerificationEvidence {

    static final String KEY = "jd_goods_verification";
    static final String SOURCE = "JD_QUERY_GOODS_INFO";

    private JdGoodsVerificationEvidence() {}

    static boolean canRecord(
            String clientMode,
            String expectedGoodsNo,
            JdGoodsReadOnlyVerifier.Verification verification) {
        return "REAL".equals(clientMode)
                && verification != null
                && verification.querySucceeded()
                && verification.found()
                && Objects.equals(expectedGoodsNo, verification.goodsNo())
                && Integer.valueOf(2).equals(verification.enableFlag());
    }

    static boolean isCurrent(ProviderSku mapping) {
        if (mapping == null || !mapping.isActive() || mapping.getExternalCodes() == null) return false;
        Object raw = mapping.getExternalCodes().get(KEY);
        if (!(raw instanceof Map<?, ?> evidence)) return false;
        return Objects.equals(mapping.getProviderSkuCode(), text(evidence.get("goods_no")))
                && SOURCE.equals(text(evidence.get("source")))
                && "REAL".equals(text(evidence.get("client_mode")))
                && Integer.valueOf(2).equals(integer(evidence.get("enable_flag")))
                && text(evidence.get("verified_at")) != null;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        String text = text(value);
        if (text == null) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
