package cn.zimu.fulfillment.connector.schedule;

import cn.zimu.fulfillment.connector.PlatformOrderRefreshService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从 {@code app.connector_configs.config} 读各渠道的拉取时间表。
 *
 * <p><b>本类只做一件事，而且只往一个方向失败</b>：任何读不出来的情况——SQL 炸了、jsonb
 * 解析失败、字段类型不对——都回落成全局默认时间表（09:00 / 18:00 全开、推企微），
 * 并记一条警告。绝不返回空、绝不返回「不拉」。原因写在
 * {@link ChannelPullSchedule} 的类注释里：这两种错误的代价严重不对称。
 *
 * <p>覆盖的渠道集合直接取 {@link PlatformOrderRefreshService#DEFAULT_CHANNELS}——
 * 就是今天定时拉取实际会拉的那三个。名单只有一份，接新平台时不会漏改。
 * 名单之外的渠道（中汇、大者、万齐、企微等）不参与定时拉取，行为与今天完全一致。
 */
@Component
public class ChannelPullScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ChannelPullScheduleStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ChannelPullSchedule defaults;

    ChannelPullScheduleStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${app.scheduled-pull.default-morning-at:09:00}") String morningAt,
            @Value("${app.scheduled-pull.default-evening-at:18:00}") String eveningAt) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.defaults = ChannelPullSchedule.defaults(
                parseDefault(morningAt, LocalTime.of(9, 0), "default-morning-at"),
                parseDefault(eveningAt, LocalTime.of(18, 0), "default-evening-at"));
    }

    /** 全局默认时间表。界面回显「未配置时实际会怎么跑」用的也是它。 */
    public ChannelPullSchedule defaults() {
        return defaults;
    }

    /** 参与定时拉取的渠道；界面按这份名单出卡片。 */
    public List<String> scheduledChannels() {
        return PlatformOrderRefreshService.DEFAULT_CHANNELS;
    }

    /**
     * 解析单个渠道已反序列化的 config，回落到全局默认。降级会记警告。
     *
     * @param channel 只用于日志定位
     */
    public ChannelPullSchedule parse(String channel, Map<String, Object> config) {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(config, defaults);
        warnIfDegraded(channel, parsed);
        return parsed.schedule();
    }

    /**
     * 读出所有参与定时拉取的渠道的时间表。
     *
     * <p>返回值一定包含 {@link #scheduledChannels()} 里的每一个渠道：数据库里没有那一行、
     * 或者整条 SQL 都失败了，也给默认时间表。「查不到这个渠道」不能变成「今天不拉了」。
     */
    public Map<String, ChannelPullSchedule> loadScheduled() {
        Map<String, ChannelPullSchedule> out = new LinkedHashMap<>();
        for (String channel : scheduledChannels()) {
            out.put(channel, defaults);
        }
        Map<String, String> rows;
        try {
            rows = readConfigJson(scheduledChannels());
        } catch (RuntimeException exception) {
            // 读不到配置就按默认拉。这条日志是 ERROR 而不是 WARN：读配置失败通常意味着
            // 数据库有问题，而拉取马上也要用同一个库，人应该现在就看到。
            log.error("读取渠道拉取时间表失败，本轮全部按全局默认执行", exception);
            return Map.copyOf(out);
        }
        rows.forEach((channel, json) -> out.put(channel, parse(channel, readConfig(channel, json))));
        return Map.copyOf(out);
    }

    private Map<String, String> readConfigJson(List<String> channels) {
        Map<String, String> out = new LinkedHashMap<>();
        String placeholders = String.join(",", channels.stream().map(ignored -> "?").toList());
        jdbc.query(
                "SELECT source_channel, config::text AS config FROM app.connector_configs"
                        + " WHERE source_channel IN (" + placeholders + ")",
                resultSet -> {
                    out.put(resultSet.getString("source_channel"), resultSet.getString("config"));
                },
                channels.toArray());
        return out;
    }

    /** jsonb 反序列化失败不抛：整份 config 读不懂时按「没配过」处理，由上层回落默认并记警告。 */
    private Map<String, Object> readConfig(String channel, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            log.warn("渠道 {} 的 config 解析失败，拉取时间表按全局默认执行", channel, exception);
            return Map.of();
        }
    }

    private static void warnIfDegraded(String channel, ChannelPullSchedule.Parsed parsed) {
        if (parsed.degraded()) {
            // 回落是安全的，但静悄悄地回落不是：运营改了字段却没生效，必须在日志里查得到。
            log.warn(
                    "渠道 {} 的拉取时间表有字段读不懂，已按全局默认回落 reasons={}",
                    channel,
                    parsed.fallbackReasons());
        }
    }

    private static LocalTime parseDefault(String raw, LocalTime fallback, String property) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim(), ChannelPullSchedule.TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            // 连全局默认都配坏了也不能不拉：退回硬编码的 09:00 / 18:00，并把这件事喊出来。
            log.error("app.scheduled-pull.{} 配置值 {} 不是 HH:mm，改用 {}", property, raw, fallback);
            return fallback;
        }
    }
}
