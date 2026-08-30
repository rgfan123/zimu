package cn.zimu.fulfillment.connector.schedule;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个来源渠道的拉取时间表：早、晚各一次，外加「拉完推不推企微」。
 *
 * <p>存在 {@code app.connector_configs.config -> 'pull_schedule'}（jsonb）里。那一列本来就是
 * 渠道私有配置（凭据、承运商映射都在里面），另开一张表反而多一处要对齐。
 *
 * <h2>空值语义（本特性最容易埋雷的地方）</h2>
 *
 * <p><b>读不到 = 按全局默认拉，绝不等于「不拉」。</b> 要停掉某一档，必须是那一档的
 * {@code enabled} 显式为 {@code false}。
 *
 * <p>理由不是洁癖：如果采用「空值 = 不拉」，那么配置读取出问题、字段被写空、jsonb 结构
 * 被改坏时，系统会**安静地停止拉取**，而界面上看不出任何异常——单子不进来，没人报错，
 * 等到有人发现已经是几天以后。这个仓已经因为同类问题丢过单
 * （{@code connector_configs.last_pull_at} 至今 8 个渠道全是 NULL）。
 * 反过来，「读不懂就按默认拉」最坏的后果只是多拉一次，而拉取本身是幂等的
 * （导入批次按内容哈希去重）。两种错误的代价不对称，所以只能往「照常拉」的方向倒。
 *
 * <p>{@link Parsed#fallbackReasons()} 把每一次回落的原因带出来，调用方**必须**记警告：
 * 回落是安全的，但静悄悄地回落不是——运营改了一个字段却没生效，应该在日志里查得到。
 *
 * @param morning      早班
 * @param evening      晚班
 * @param notifyWecom  本渠道拉完后是否允许发企微播报卡
 */
public record ChannelPullSchedule(Slot morning, Slot evening, boolean notifyWecom) {

    /** jsonb 里的键名。运营可能直接改库，键名一旦定了就不要改。 */
    public static final String CONFIG_KEY = "pull_schedule";

    static final String KEY_MORNING = "morning";
    static final String KEY_EVENING = "evening";
    static final String KEY_NOTIFY_WECOM = "notify_wecom";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_AT = "at";

    /** 只认 HH:mm。界面给的是时间选择框，不给用户填 cron，也不需要秒。 */
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * 一档拉取。
     *
     * @param enabled 这一档要不要跑；停用必须是显式的 false
     * @param at      本地时间（Asia/Shanghai），精确到分
     */
    public record Slot(boolean enabled, LocalTime at) {

        public String atText() {
            return at.format(TIME_FORMAT);
        }
    }

    /**
     * 解析结果。
     *
     * <p>{@code schedule} 永远可用——解析不出来就是默认值，不会是 null，也不会是「不拉」。
     * {@code fallbackReasons} 非空表示有字段没读懂并已回落，调用方应当记警告。
     */
    public record Parsed(ChannelPullSchedule schedule, List<String> fallbackReasons) {

        public boolean degraded() {
            return !fallbackReasons.isEmpty();
        }
    }

    /** 全局默认：与本特性上线前的固定 09:00 / 18:00 全量拉、推企微完全一致。 */
    public static ChannelPullSchedule defaults(LocalTime morningAt, LocalTime eveningAt) {
        return new ChannelPullSchedule(new Slot(true, morningAt), new Slot(true, eveningAt), true);
    }

    /**
     * 从 {@code config} 整份 map 里读出时间表。
     *
     * <p>逐字段回落而不是整体回落：只把 {@code evening.at} 写坏了，不该把早班的显式停用也一起
     * 忘掉——那会让「我明明关了早班」变成「它又自己跑起来了」。
     *
     * @param config    connector_configs.config 反序列化后的 map，可为 null
     * @param fallback  读不到时用的默认值，见 {@link #defaults}
     */
    public static Parsed parse(Map<String, Object> config, ChannelPullSchedule fallback) {
        List<String> reasons = new ArrayList<>();
        Object raw = config == null ? null : config.get(CONFIG_KEY);
        if (raw == null) {
            // 没配过：这是绝大多数渠道的常态，不是降级，不记警告。
            return new Parsed(fallback, List.of());
        }
        if (!(raw instanceof Map<?, ?> node)) {
            reasons.add("PULL_SCHEDULE_NOT_OBJECT");
            return new Parsed(fallback, List.copyOf(reasons));
        }
        Slot morning = slot(node.get(KEY_MORNING), fallback.morning(), KEY_MORNING, reasons);
        Slot evening = slot(node.get(KEY_EVENING), fallback.evening(), KEY_EVENING, reasons);
        boolean notify = bool(
                node.get(KEY_NOTIFY_WECOM), fallback.notifyWecom(), KEY_NOTIFY_WECOM, reasons);
        return new Parsed(new ChannelPullSchedule(morning, evening, notify), List.copyOf(reasons));
    }

    private static Slot slot(Object raw, Slot fallback, String key, List<String> reasons) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Map<?, ?> node)) {
            reasons.add(key.toUpperCase() + "_NOT_OBJECT");
            return fallback;
        }
        boolean enabled = bool(node.get(KEY_ENABLED), fallback.enabled(), key + "." + KEY_ENABLED, reasons);
        LocalTime at = time(node.get(KEY_AT), fallback.at(), key + "." + KEY_AT, reasons);
        return new Slot(enabled, at);
    }

    private static boolean bool(Object raw, boolean fallback, String key, List<String> reasons) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        // 刻意不接受 "true"/"1" 之类的字符串：布尔字段被写成字符串通常意味着写入方式不对，
        // 悄悄兼容会让那个 bug 一直活着。回落 + 警告，让人看得到。
        reasons.add(normalize(key) + "_NOT_BOOLEAN");
        return fallback;
    }

    private static LocalTime time(Object raw, LocalTime fallback, String key, List<String> reasons) {
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            reasons.add(normalize(key) + "_NOT_TIME");
            return fallback;
        }
        try {
            return LocalTime.parse(text.trim(), TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            reasons.add(normalize(key) + "_NOT_TIME");
            return fallback;
        }
    }

    private static String normalize(String key) {
        return key.replace('.', '_').toUpperCase();
    }

    /** 写回 {@code config} 时用的表示。键名与 {@link #parse} 一一对应。 */
    public Map<String, Object> toConfigValue() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_MORNING, slotValue(morning));
        out.put(KEY_EVENING, slotValue(evening));
        out.put(KEY_NOTIFY_WECOM, notifyWecom);
        return out;
    }

    private static Map<String, Object> slotValue(Slot slot) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(KEY_ENABLED, slot.enabled());
        out.put(KEY_AT, slot.atText());
        return out;
    }
}
