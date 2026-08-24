package cn.zimu.fulfillment.connector.wecom.card;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企微交互卡的 {@code task_id}（wecom-card-review §B）：格式 {@code {domain}:{id}:{marker}}，
 * marker 为 {@code v<版本>} 或 {@code g<代际>}。
 *
 * <p>把业务版本编进 task_id 不是为了好看——回调带回的 task_id **本身就是版本断言**：
 * 点旧卡片时解析出的版本与当前版本不符即 {@code VERSION_CONFLICT}，不需要另造防重放机制，
 * 且与既有 {@code expected_version} 契约天然同构。用随机 UUID 会把这份免费的能力丢掉。
 *
 * <p>示例：{@code review:1234:v0}（对齐 review_cases.resolution_version）、
 * {@code alert:5678:v2}（对齐 operational_alerts.lock_version）、
 * {@code export:99:g3}（对齐 V49 的 initial_generation）。
 */
public record WecomTaskId(String domain, long entityId, Marker marker, long version) {

    /** 版本标记：v = 乐观锁版本，g = 导出代际。 */
    public enum Marker {
        VERSION('v'),
        GENERATION('g');

        private final char prefix;

        Marker(char prefix) {
            this.prefix = prefix;
        }

        char prefix() {
            return prefix;
        }
    }

    /** 企微 task_id 上限 128 字节；域名只允许小写字母与连字符，避免协议侧拒绝。 */
    public static final int MAX_LENGTH = 128;

    private static final Pattern PATTERN = Pattern.compile("^([a-z][a-z-]{0,31}):([0-9]{1,19}):([vg])([0-9]{1,19})$");

    public WecomTaskId {
        if (domain == null || !domain.matches("^[a-z][a-z-]{0,31}$")) {
            throw new IllegalArgumentException("task_id domain 必须匹配 ^[a-z][a-z-]{0,31}$: " + domain);
        }
        if (entityId < 0) {
            throw new IllegalArgumentException("task_id entityId 不能为负: " + entityId);
        }
        if (version < 0) {
            throw new IllegalArgumentException("task_id version 不能为负: " + version);
        }
    }

    public static WecomTaskId ofVersion(String domain, long entityId, long version) {
        return new WecomTaskId(domain, entityId, Marker.VERSION, version);
    }

    public static WecomTaskId ofGeneration(String domain, long entityId, long generation) {
        return new WecomTaskId(domain, entityId, Marker.GENERATION, generation);
    }

    /**
     * 解析回调带回的 task_id。**无法解析一律返回 empty 而不是抛异常**——回调里来的是
     * 外部输入，格式不认识时应走「无法识别的卡片」路径，不能让回调线程炸掉。
     */
    public static Optional<WecomTaskId> parse(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(raw.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WecomTaskId(
                    matcher.group(1),
                    Long.parseLong(matcher.group(2)),
                    "v".equals(matcher.group(3)) ? Marker.VERSION : Marker.GENERATION,
                    Long.parseLong(matcher.group(4))));
        } catch (NumberFormatException ex) {
            // 19 位以内仍可能溢出 long：按无法识别处理，不抛给回调线程
            return Optional.empty();
        }
    }

    /**
     * 版本断言：回调回来的 task_id 是否仍指向当前版本。
     * 不同实体、不同域一律视为不匹配（防止跨实体误判为同一张卡）。
     */
    public boolean matchesCurrent(String domain, long entityId, long currentVersion) {
        return this.domain.equals(domain) && this.entityId == entityId && this.version == currentVersion;
    }

    public String value() {
        String value = domain + ":" + entityId + ":" + marker.prefix() + version;
        if (value.length() > MAX_LENGTH) {
            throw new IllegalStateException("task_id 超出企微 128 字节上限: " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        return value();
    }
}
