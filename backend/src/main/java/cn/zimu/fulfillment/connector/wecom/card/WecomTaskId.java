package cn.zimu.fulfillment.connector.wecom.card;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企微交互卡的 {@code task_id}（wecom-card-review §B）：格式 {@code {domain}_{id}_{marker}}，
 * marker 为 {@code v<版本>} 或 {@code g<代际>}。

 * <p><b>分隔符为什么是下划线而不是冒号</b>：aibot 官方参数表规定 task_id
 * 「只能由数字、字母和 {@code _-@} 组成，最长 128 字节」。此前用冒号，生产表现是
 * {@code text_notice} 卡照发不误（该卡型不校验 task_id），而所有 {@code button_interaction}
 * 卡一律被平台以 {@code errcode 42014「卡片消息的task_id不合法」} 拒收——
 * 「发送成功」的假象只属于播报卡，带按钮的卡从来没被接受过，
 * 这正是 {@code template_card_event} 至今零命中的根因。
 * 域名只含 {@code [a-z-]}，故下划线做分隔符不会与域名歧义（{@code jd-outbound_12_v3}）。
 *
 * <p>把业务版本编进 task_id 不是为了好看——回调带回的 task_id **本身就是版本断言**：
 * 点旧卡片时解析出的版本与当前版本不符即 {@code VERSION_CONFLICT}，不需要另造防重放机制，
 * 且与既有 {@code expected_version} 契约天然同构。用随机 UUID 会把这份免费的能力丢掉。
 *
 * <p>示例：{@code review_1234_v0}（对齐 review_cases.resolution_version）、
 * {@code alert_5678_v2}（对齐 operational_alerts.lock_version）、
 * {@code export_99_g3}（对齐 V49 的 initial_generation）。
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

    /** aibot 官方字符集：数字、字母、{@code _ - @}。越界即 errcode 42014。 */
    private static final Pattern LEGAL_CHARSET = Pattern.compile("^[0-9A-Za-z_\\-@]+$");

    private static final Pattern PATTERN = Pattern.compile("^([a-z][a-z-]{0,31})_([0-9]{1,19})_([vg])([0-9]{1,19})$");

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
        String value = domain + "_" + entityId + "_" + marker.prefix() + version;
        if (value.length() > MAX_LENGTH) {
            throw new IllegalStateException("task_id 超出企微 128 字节上限: " + value);
        }
        if (!LEGAL_CHARSET.matcher(value).matches()) {
            // 平台侧只会回 42014，看不出哪个字符越界；在本地先炸掉，错误信息里带上原值
            throw new IllegalStateException(
                    "task_id 含 aibot 非法字符（只允许数字、字母与 _-@）: " + value);
        }
        return value;
    }

    @Override
    public String toString() {
        return value();
    }
}
