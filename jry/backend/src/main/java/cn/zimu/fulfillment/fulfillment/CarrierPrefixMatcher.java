package cn.zimu.fulfillment.fulfillment;

import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Carrier 确定性解析：消息明示物流公司按主数据（内部代码/名称）解析，运单号按前缀映射解析。
 *
 * <p>两种来源都归一化后只取恰好命中一个启用 Carrier 的结果；未命中、多命中或两个来源冲突时
 * 返回空候选，由人工选择。本类不含任何隐式权重（转发人、群聊、昵称、时间、近似文本都不参与）。
 */
@Component
public class CarrierPrefixMatcher {

    /** 一次解析的结果：命中的唯一候选，或空。 */
    public record Carrier(String code, String name) {}

    private final CarrierPrefixProperties properties;
    private final JdbcTemplate jdbc;

    public CarrierPrefixMatcher(CarrierPrefixProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /**
     * 消息明示物流公司 → 确定性主数据解析。归一化后与启用 Carrier 的内部代码或名称精确匹配，
     * 恰好命中一个时才返回候选。
     */
    public Optional<Carrier> resolveStated(String stated) {
        if (stated == null || stated.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(stated);
        Set<Carrier> matched = new LinkedHashSet<>();
        for (Map.Entry<String, CarrierPrefixProperties.CarrierEntry> entry : properties.getCarriers().entrySet()) {
            if (!entry.getValue().isEnabled()) {
                continue;
            }
            String code = normalize(entry.getKey());
            String name = entry.getValue().getName() == null ? null : normalize(entry.getValue().getName());
            if (normalized.equals(code) || (name != null && normalized.equals(name))) {
                matched.add(new Carrier(entry.getKey(), entry.getValue().getName()));
            }
        }
        return matched.size() == 1 ? matched.stream().findFirst() : Optional.empty();
    }

    /**
     * 运单号前缀 → 前缀映射 → 恰好一个启用 Carrier 时形成候选。
     *
     * <p>规则：取归一化后运单号开头的连续大写字母作为前缀，收集所有以该前缀开头的映射键指向的
     * 启用 Carrier；恰好一个不同 Carrier 时返回，零命中返回空，多命中（多个映射键指向不同
     * Carrier）也返回空并交由人工选择。
     */
    public Optional<Carrier> resolvePrefix(String trackingNo) {
        List<Carrier> matched = matchesFromPrefix(trackingNo);
        return matched.size() == 1 ? Optional.of(matched.getFirst()) : Optional.empty();
    }

    /** 运单号前缀命中的全部启用 Carrier（零/一/多），供调用方区分未命中与多命中。 */
    public List<Carrier> matchesFromPrefix(String trackingNo) {
        String prefix = extractPrefix(trackingNo);
        if (prefix.isEmpty()) {
            return List.of();
        }
        Set<Carrier> matched = new LinkedHashSet<>();
        for (Map.Entry<String, String> mapping : prefixMappings().entrySet()) {
            String key = normalize(mapping.getKey());
            if (key.isEmpty() || !prefix.startsWith(key)) {
                continue;
            }
            carrier(mapping.getValue()).ifPresent(matched::add);
        }
        return List.copyOf(matched);
    }

    /** Runtime reads always use the audited V21 database authority; environment mappings are not a fallback. */
    private Map<String, String> prefixMappings() {
        Map<String, String> mappings = new java.util.LinkedHashMap<>();
        List<Map.Entry<String, String>> rows = jdbc.query(
                "SELECT prefix, carrier_code FROM app.carrier_prefix_mappings ORDER BY prefix, carrier_code",
                (resultSet, rowNum) -> Map.entry(
                        resultSet.getString("prefix"), resultSet.getString("carrier_code")));
        rows.forEach(mapping -> mappings.put(mapping.getKey(), mapping.getValue()));
        return mappings;
    }

    /** 按内部代码取启用 Carrier 主数据；停用或未知代码返回空。 */
    public Optional<Carrier> carrier(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        CarrierPrefixProperties.CarrierEntry entry = properties.getCarriers().get(code);
        if (entry == null || !entry.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new Carrier(code, entry.getName()));
    }

    /** 复核页允许人工选择的全部启用 Carrier 主数据，与前缀候选分离。 */
    public List<Carrier> enabledCarriers() {
        return properties.getCarriers().entrySet().stream()
                .filter(entry -> entry.getValue().isEnabled())
                .map(entry -> new Carrier(entry.getKey(), entry.getValue().getName()))
                .sorted(Comparator.comparing(Carrier::code))
                .toList();
    }

    /** 取运单号开头的连续字母前缀（大写）：JDVAFX001 → JDVAFX；123456 → 空。 */
    static String extractPrefix(String trackingNo) {
        if (trackingNo == null || trackingNo.isBlank()) {
            return "";
        }
        int index = 0;
        String value = trackingNo.trim();
        while (index < value.length() && Character.isLetter(value.charAt(index))) {
            index++;
        }
        return value.substring(0, index).toUpperCase(Locale.ROOT);
    }

    /** 归一化：去首尾空白并转大写（运单号与中文名称的大写转换是幂等的）。 */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
