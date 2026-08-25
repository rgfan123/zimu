package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.text.ReceiverFactsNormalizer;

/** 来源回传收货事实的保守格式归一；不做姓名或地址模糊匹配。 */
public final class SourceReceiverNormalizer {

    private SourceReceiverNormalizer() {}

    public static boolean sameName(String internal, String platform) {
        return ReceiverFactsNormalizer.normalizeName(internal)
                .equals(ReceiverFactsNormalizer.normalizeName(platform));
    }

    public static boolean samePhone(String internal, String platform) {
        return ReceiverFactsNormalizer.normalizePhone(internal)
                .equals(ReceiverFactsNormalizer.normalizePhone(platform));
    }

    public static boolean sameAddress(String internal, String platform) {
        return ReceiverFactsNormalizer.normalizeAddress(internal)
                .equals(ReceiverFactsNormalizer.normalizeAddress(platform));
    }
}
