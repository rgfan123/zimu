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
 * 形状校验与对外状态投影。{@code customerCode} 不在本清单（见 02 票：改为客户级字段）。
 * {@code pin} 为敏感值：状态投影与审计负载只标记存在性，永不回显明文。
 * {@code outboundMode}（05 票）：来源批次确认后京东履约的建单路由，显式配置
 * {@code SDK} 才走 SDK 直连，缺省/显式 {@code FILE} 保持既有导单文件路径（可回退）。
 */
public final class FulfillmentProviderJdConfig {

    public static final List<String> KNOWN_KEYS = List.of(
            "sourceNo", "warehouseNo", "pin", "erpShopNo", "salesPlatformSource",
            "ownerNo", "shopNo", "carrierNo", "townRequired", "outboundMode");

    /** 建单路由模式：SDK 直连或导单文件；缺省为 FILE（不改变历史批次处置方式）。 */
    public static final String OUTBOUND_MODE_SDK = "SDK";
    public static final String OUTBOUND_MODE_FILE = "FILE";

    private static final Set<String> SECRET_KEYS = Set.of("pin");
    private static final String TOWN_REQUIRED = "townRequired";
    private static final String OUTBOUND_MODE = "outboundMode";

    private FulfillmentProviderJdConfig() {}

    /** 校验 patch 中的京东标识：未知键或形状非法直接拒绝；null 值表示清除该键。 */
    public static Map<String, Object> validate(Map<String, Object> patch) {
        List<String> unknown = patch.keySet().stream().filter(key -> !KNOWN_KEYS.contains(key)).toList();
        if (!unknown.isEmpty()) {
            throw BusinessException.unprocessable(
                    "FULFILLMENT_PROVIDER_CONFIG_KEY_UNKNOWN",
                    "未知的京东配置键: " + String.join(", ", unknown)
                            + "；支持的键: " + String.join(", ", KNOWN_KEYS));
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
