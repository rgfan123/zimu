package cn.zimu.fulfillment.customer;

import cn.zimu.fulfillment.common.text.ReceiverFactsNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 来源订单客户身份：只由规范化姓名与手机号二元组确定。 */
public record ImportedCustomerIdentity(
        String normalizedName,
        String normalizedPhone,
        String sourceCustomerRef,
        boolean complete) {

    public static ImportedCustomerIdentity from(String rawName, String rawPhone) {
        String name = ReceiverFactsNormalizer.normalizeName(rawName);
        String phone = ReceiverFactsNormalizer.normalizePhone(rawPhone);
        return fromNormalized(name, phone);
    }

    /** 仅用于升级时回查已落库的旧 CONTACT-* 身份，不用于创建新身份。 */
    public static ImportedCustomerIdentity legacyFrom(String rawName, String rawPhone) {
        String name = ReceiverFactsNormalizer.normalizeName(rawName);
        String phone = ReceiverFactsNormalizer.normalizeLegacyPhone(rawPhone);
        return fromNormalized(name, phone);
    }

    /**
     * 升级期读候选：当前无国家码身份、按本次原文计算的旧身份，以及可能已落库的 +86 旧身份。
     * 顺序稳定且按 source ref 去重；调用方只为第一个当前身份写新别名。
     */
    public static List<ImportedCustomerIdentity> lookupCandidates(String rawName, String rawPhone) {
        ImportedCustomerIdentity current = from(rawName, rawPhone);
        if (!current.complete()) return List.of(current);
        Map<String, ImportedCustomerIdentity> candidates = new LinkedHashMap<>();
        candidates.put(current.sourceCustomerRef(), current);
        ImportedCustomerIdentity rawLegacy = legacyFrom(rawName, rawPhone);
        candidates.put(rawLegacy.sourceCustomerRef(), rawLegacy);
        if (current.normalizedPhone().matches("1\\d{10}")) {
            ImportedCustomerIdentity countryCodeLegacy = fromNormalized(
                    current.normalizedName(), "+86" + current.normalizedPhone());
            candidates.put(countryCodeLegacy.sourceCustomerRef(), countryCodeLegacy);
        }
        return List.copyOf(candidates.values());
    }

    /** 与 2026-08-25 前 master 完全一致的完整小写 SHA-256。 */
    public String identityHash() {
        if (!complete) return "";
        return sha256(normalizedName + "\u001f" + normalizedPhone);
    }

    /** 滚动部署期间必须与旧实例争用同一个 advisory lock。 */
    public String advisoryLockKey() {
        if (!complete) throw new IllegalStateException("incomplete imported customer identity");
        return "import-customer:" + identityHash();
    }

    private static ImportedCustomerIdentity fromNormalized(String name, String phone) {
        if (name.isBlank() || phone.isBlank()) {
            return new ImportedCustomerIdentity(name, phone, "UNRESOLVED", false);
        }
        String identityHash = sha256(name + "\u001f" + phone);
        return new ImportedCustomerIdentity(
                name,
                phone,
                "CONTACT-" + identityHash.substring(0, 32).toUpperCase(),
                true);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
