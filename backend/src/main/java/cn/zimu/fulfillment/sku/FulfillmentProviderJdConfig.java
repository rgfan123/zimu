package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 履约方京东标识配置契约（jd-real-sdk-switch 01）。
 *
 * <p>建单所需标识一律来自 {@code fulfillment_providers.config}（预览侧
 * {@code ShipmentJdOutboundService} 缺失即阻断）；本类维护允许写入的键清单、
 * 形状校验与对外状态投影。{@code customerCode} 为青龙业主号（010K 开头，
 * 京东 addSoOrder 的 customerInfo.customerCode），真实建单 2026-08-18 裁决：
 * 京东按事业部维护单一青龙业主号，非客户级编码，故回到配置面（02 票原客户级
 * 决策已被真实契约推翻，客户档案字段降级为回退源）。
 * {@code pin} 为敏感值：状态投影与审计负载只标记存在性，永不回显明文。
 * {@code outboundMode}（05 票）：来源批次确认后京东履约的建单路由，显式配置
 * {@code SDK} 才走 SDK 直连，缺省/显式 {@code FILE} 保持既有导单文件路径（可回退）。
 */
public final class FulfillmentProviderJdConfig {

    public static final List<String> KNOWN_KEYS = List.of(
            "sourceNo", "warehouseNo", "pin", "erpShopNo", "salesPlatformSource",
            "ownerNo", "shopNo", "carrierNo", "townRequired", "outboundMode", "customerCode",
            "addressAnalysis");

    /** 建单路由模式：SDK 直连或导单文件；缺省为 FILE（不改变历史批次处置方式）。 */
    public static final String OUTBOUND_MODE_SDK = "SDK";
    public static final String OUTBOUND_MODE_FILE = "FILE";

    /**
     * 收货地址解析模式（京东 addSoOrder 的 receiverInfo.addressAnalysis）。
     *
     * <p>官方枚举（docs/research/jdl-api-367/json/1596-addSoOrder.json）：
     * 0 不解析、1 通过客户门店编码解析、2 通过收件人地址解析四级地址。
     * 本系统只放行 0 与 2——1 依赖客户门店编码体系，我们不维护该体系。
     *
     * <p><b>缺省不配 = 不启用</b>：保持既有「四级地址人工确认」路径一字不变。
     * 配成 2 时改为把完整原始地址交给京东解析，省/市/区/镇不再由我方必填。
     * 这不是放松管控而是换了权威源：此前是我方词典猜（且猜得不好），现在是京东自己解析。
     */
    public static final String ADDRESS_ANALYSIS_NONE = "0";
    public static final String ADDRESS_ANALYSIS_BY_RECEIVER_ADDRESS = "2";

    private static final Set<String> SECRET_KEYS = Set.of("pin");
    private static final String TOWN_REQUIRED = "townRequired";
    private static final String OUTBOUND_MODE = "outboundMode";
    private static final String ADDRESS_ANALYSIS = "addressAnalysis";

    private FulfillmentProviderJdConfig() {}

    /** 校验 patch 中的京东标识：未知键或形状非法直接拒绝；null 值表示清除该键。 */
    public static Map<String, Object> validate(Map<String, Object> patch) {
        List<String> unknown = patch.keySet().stream().filter(key -> !KNOWN_KEYS.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.unprocessable(
                    "FULFILLMENT_PROVIDER_CONFIG_KEY_UNKNOWN",
                    "未知的京东配置键: " + String.join(", ", unknown)
                            + "；支持的键: " + String.join(", ", KNOWN_KEYS)
                            + "；企微群 chatid 请使用 " + FulfillmentProviderWecomConfig.GROUP_CHAT_ID_KEY);
        }
        Map<String, Object> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                validated.put(key, null);
                continue;
            }
            if (TOWN_REQUIRED.equals(key)) {
                if (!(value instanceof Boolean)) {
                    throw BusinessException.unprocessable(
                            "FULFILLMENT_PROVIDER_CONFIG_TOWN_REQUIRED_NOT_BOOLEAN",
                            "townRequired 只接受 JSON 布尔值；系统不猜测京东要求");
                }
                validated.put(key, value);
                continue;
            }
            if (!(value instanceof String text) || text.isBlank()) {
                throw BusinessException.unprocessable(
                        "FULFILLMENT_PROVIDER_CONFIG_VALUE_INVALID",
                        "京东标识 " + key + " 必须是非空字符串");
            }
            if (OUTBOUND_MODE.equals(key)
                    && !OUTBOUND_MODE_SDK.equals(text) && !OUTBOUND_MODE_FILE.equals(text)) {
                throw BusinessException.unprocessable(
                        "FULFILLMENT_PROVIDER_CONFIG_OUTBOUND_MODE_INVALID",
                        "outboundMode 只接受 SDK 或 FILE；缺省为 FILE（保持导单文件路径）");
            }
            if (ADDRESS_ANALYSIS.equals(key)
                    && !ADDRESS_ANALYSIS_NONE.equals(text)
                    && !ADDRESS_ANALYSIS_BY_RECEIVER_ADDRESS.equals(text)) {
                throw BusinessException.unprocessable(
                        "FULFILLMENT_PROVIDER_CONFIG_ADDRESS_ANALYSIS_INVALID",
                        "addressAnalysis 只接受 0（不解析）或 2（京东按收件人地址解析四级地址）；"
                                + "枚举值 1 依赖客户门店编码体系，本系统不维护");
            }
            validated.put(key, text);
        }
        return validated;
    }

    /** 审计负载投影：敏感键以存在性标记替代，不出现明文。 */
    public static Map<String, Object> auditSafe(Map<String, Object> validated) {
        Map<String, Object> safe = new LinkedHashMap<>();
        validated.forEach((key, value) -> safe.put(key, SECRET_KEYS.contains(key) ? "***" : value));
        return safe;
    }

    /** 对外状态投影：每个已知键给出 present 与（非敏感）值，直接对应预览里的阻塞路径。 */
    public static Map<String, Object> status(Map<String, Object> config) {
        Map<String, Object> status = new LinkedHashMap<>();
        for (String key : KNOWN_KEYS) {
            Object value = config == null ? null : config.get(key);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("present", value != null);
            if (value != null && !SECRET_KEYS.contains(key)) {
                entry.put("value", value);
            }
            status.put(key, entry);
        }
        return status;
    }
}
