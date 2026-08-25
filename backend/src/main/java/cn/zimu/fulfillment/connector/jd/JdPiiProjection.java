package cn.zimu.fulfillment.connector.jd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 京东只读接口 HTTP 边界的个人信息投影：把响应里的联系人字段整块**剔除**后再出网。
 *
 * <p>收编前这段规则（redactPersonalData / sanitize / personalField，约 55 行）逐字节复制在
 * 6 个 JD controller 里，每份 Javadoc 都写着「6 个 controller 统一口径」——统一的正确拼法是
 * 引用同一个实现，而不是六份手工同步的副本（票 03）。
 *
 * <p>与 {@code common.audit.SecretRedactor} **不是同一条规则**，不可互相替代：
 * SecretRedactor 做的是**掩码**（保留键、值置 {@code ***}），用于审计留痕；本单元做的是
 * **剔除**（整个键不出现），用于 HTTP 响应。两者只在「哪些键算个人信息」的判断上同源。
 */
public final class JdPiiProjection {

    private JdPiiProjection() {}

    /** 保留调用结果的成败与业务码，只对 data 做个人信息剔除。 */
    public static JdResult redactPersonalData(JdResult result) {
        return new JdResult(
                result.success(),
                result.businessCode(),
                result.message(),
                result.requestId(),
                sanitize(result.data()));
    }

    /** 递归剔除：Map 逐键判定，List 逐项下钻，标量原样返回。 */
    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> safe = new LinkedHashMap<>();
            values.forEach((key, item) -> {
                String field = String.valueOf(key);
                if (!personalField(field)) {
                    safe.put(field, sanitize(item));
                }
            });
            return safe;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(JdPiiProjection::sanitize).toList();
        }
        return value;
    }

    /**
     * HTTP 边界 PII 规则：联系人/客户容器键（receiverinfo/senderinfo/consignee/customerinfo 等）
     * 整块剔除；phone/mobile/telephone/email/fax/address 按精确键或后缀匹配剔除，键先归一化为
     * 小写（覆盖 SDK camelCase 如 transporterPhone/backEmail），与 SecretRedactor.isPersonalDataKey
     * 的键判定同源。
     */
    public static boolean personalField(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("customerinfo")
                || normalized.contains("receiverinfo")
                || normalized.contains("senderinfo")
                || normalized.contains("consignee")
                || normalized.contains("contactinfo")
                || normalized.contains("recipientinfo")
                || normalized.equals("phone")
                || normalized.equals("mobile")
                || normalized.equals("telephone")
                || normalized.equals("email")
                || normalized.equals("fax")
                || normalized.equals("address")
                || normalized.endsWith("phone")
                || normalized.endsWith("mobile")
                || normalized.endsWith("telephone")
                || normalized.endsWith("email")
                || normalized.endsWith("fax")
                || normalized.endsWith("address")
                // 个人角色姓名键（transporterName/shipperName/operateName/linkmanName 等）：
                // 以 name 结尾且含个人角色词才剔除；业务实体名（ownerName/shopName/goodsName 等）不受影响
                || (normalized.endsWith("name")
                        && (normalized.contains("transporter")
                                || normalized.contains("shipper")
                                || normalized.contains("operator")
                                || normalized.contains("operate")
                                || normalized.contains("linkman")
                                || normalized.contains("contact")
                                || normalized.contains("receiver")
                                || normalized.contains("sender")));
    }
}
