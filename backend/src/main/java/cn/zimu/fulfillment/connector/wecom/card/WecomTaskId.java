package cn.zimu.fulfillment.connector.wecom.card;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企微交互卡的逻辑标识与持久化 {@code task_id}。逻辑格式为
 * {@code {domain}_{id}_{marker}}，持久化投递再追加 128-bit 随机授权引用；marker 为
 * {@code v<版本>} 或 {@code g<代际>}。
 *
 * <p>把业务版本编进 task_id 不是为了好看——回调带回的 task_id **本身就是版本断言**：
 * 点旧卡片时版本与当前版本不符即 {@code VERSION_CONFLICT}；随机授权引用同时阻止攻击者
 * 仅凭可猜的实体编号伪造一个从未投递过的 task_id。业务实体与版本仍必须以投递表为准，
 * 不信任回调字符串自述。
 *
 * <p>示例：{@code review_1234_v0}（对齐 review_cases.resolution_version）、
 * {@code alert_5678_v2}（对齐 operational_alerts.lock_version）、
 * {@code export_99_g3}（对齐 V49 的 initial_generation）。下划线符合企微 task_id
 * 只允许数字、字母、下划线、连字符和 @ 的协议约束；冒号必须拒绝。
 */
public record WecomTaskId(
        String domain, long entityId, Marker marker, long version, String authorizationRef) {

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

    private static final Pattern PATTERN = Pattern.compile(
            "^([a-z][a-z-]{0,31})_([0-9]{1,19})_([vg])([0-9]{1,19})(?:_([0-9a-f]{32}))?$");

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
        if (authorizationRef != null && !authorizationRef.matches("^[0-9a-f]{32}$")) {
            throw new IllegalArgumentException("task_id authorizationRef 必须是 128-bit 小写十六进制");
        }
    }

    public static WecomTaskId ofVersion(String domain, long entityId, long version) {
        return new WecomTaskId(domain, entityId, Marker.VERSION, version, null);
    }

    public static WecomTaskId ofGeneration(String domain, long entityId, long generation) {
        return new WecomTaskId(domain, entityId, Marker.GENERATION, generation, null);
    }

    public WecomTaskId authorize(String ref) {
        return new WecomTaskId(domain, entityId, marker, version, ref);
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
                    Long.parseLong(matcher.group(4)),
                    matcher.group(5)));
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
        String value = domain + "_" + entityId + "_" + marker.prefix() + version
                + (authorizationRef == null ? "" : "_" + authorizationRef);
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
