package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 空值语义的门禁：**任何读不懂的输入都必须回落成「照常拉」，绝不能变成「不拉」**。
 *
 * <p>这些用例存在的理由写在 {@link ChannelPullSchedule} 的类注释里：「空值 = 不拉」会让一次
 * 配置读取故障表现成系统安静停摆，而界面上看不出任何异常。本仓已经因为同类问题丢过单。
 */
class ChannelPullScheduleTest {

    private static final ChannelPullSchedule DEFAULTS =
            ChannelPullSchedule.defaults(LocalTime.of(9, 0), LocalTime.of(18, 0));

    private static Map<String, Object> config(Object pullSchedule) {
        Map<String, Object> config = new HashMap<>();
        config.put("endpoint", "https://example.test");
        config.put(ChannelPullSchedule.CONFIG_KEY, pullSchedule);
        return config;
    }

    @Test
    void neverConfiguredFallsBackToTheGlobalDefaultAndIsNotTreatedAsDegraded() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(Map.of(), DEFAULTS);

        assertThat(parsed.schedule()).isEqualTo(DEFAULTS);
        // 绝大多数渠道就是没配过，这是常态不是降级——记成降级会把日志淹掉。
        assertThat(parsed.degraded()).isFalse();
    }

    @Test
    void nullConfigStillPulls() {
        assertThat(ChannelPullSchedule.parse(null, DEFAULTS).schedule()).isEqualTo(DEFAULTS);
    }

    @Test
    void aCorruptScheduleFallsBackToPullingAndSaysWhy() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(config("坏了"), DEFAULTS);

        assertThat(parsed.schedule()).isEqualTo(DEFAULTS);
        assertThat(parsed.fallbackReasons()).containsExactly("PULL_SCHEDULE_NOT_OBJECT");
    }

    @Test
    void anUnparseableTimeFallsBackToTheDefaultTimeNotToSilence() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(
                config(Map.of("morning", Map.of("enabled", true, "at", "两点半"))), DEFAULTS);

        assertThat(parsed.schedule().morning().enabled()).isTrue();
        assertThat(parsed.schedule().morning().at()).isEqualTo(LocalTime.of(9, 0));
        assertThat(parsed.fallbackReasons()).contains("MORNING_AT_NOT_TIME");
    }

    @Test
    void aNonBooleanEnabledFlagFallsBackToEnabled() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(
                config(Map.of("evening", Map.of("enabled", "false", "at", "20:00"))), DEFAULTS);

        // 字符串 "false" 不算显式停用：停用必须是真正的布尔 false，否则一次写入方式的 bug
        // 就会安静地把这一档关掉。
        assertThat(parsed.schedule().evening().enabled()).isTrue();
        assertThat(parsed.schedule().evening().at()).isEqualTo(LocalTime.of(20, 0));
        assertThat(parsed.fallbackReasons()).contains("EVENING_ENABLED_NOT_BOOLEAN");
    }

    @Test
    void oneBrokenFieldDoesNotWipeOutTheOtherSlotsExplicitChoice() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(
                config(Map.of(
                        "morning", Map.of("enabled", false, "at", "08:30"),
                        "evening", "坏了")),
                DEFAULTS);

        // 晚班读不懂就按默认；但早班「我明明关了」必须留住，否则用户会看到它自己又跑起来。
        assertThat(parsed.schedule().morning()).isEqualTo(
                new ChannelPullSchedule.Slot(false, LocalTime.of(8, 30)));
        assertThat(parsed.schedule().evening()).isEqualTo(DEFAULTS.evening());
        assertThat(parsed.fallbackReasons()).containsExactly("EVENING_NOT_OBJECT");
    }

    @Test
    void anExplicitFalseIsTheOnlyWayToStopASlot() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(
                config(Map.of(
                        "morning", Map.of("enabled", false, "at", "09:00"),
                        "evening", Map.of("enabled", true, "at", "18:00"),
                        "notify_wecom", false)),
                DEFAULTS);

        assertThat(parsed.degraded()).isFalse();
        assertThat(parsed.schedule().morning().enabled()).isFalse();
        assertThat(parsed.schedule().notifyWecom()).isFalse();
    }

    @Test
    void missingNotifyFlagMeansPushNotSilence() {
        ChannelPullSchedule.Parsed parsed = ChannelPullSchedule.parse(
                config(Map.of("morning", Map.of("enabled", true, "at", "09:00"))), DEFAULTS);

        assertThat(parsed.schedule().notifyWecom()).isTrue();
        assertThat(parsed.fallbackReasons()).isEmpty();
    }

    @Test
    void configValueRoundTrips() {
        ChannelPullSchedule original = new ChannelPullSchedule(
                new ChannelPullSchedule.Slot(false, LocalTime.of(7, 5)),
                new ChannelPullSchedule.Slot(true, LocalTime.of(19, 30)),
                false);

        ChannelPullSchedule.Parsed parsed =
                ChannelPullSchedule.parse(config(original.toConfigValue()), DEFAULTS);

        assertThat(parsed.schedule()).isEqualTo(original);
        assertThat(parsed.fallbackReasons()).isEqualTo(List.of());
        assertThat(original.morning().atText()).isEqualTo("07:05");
    }
}
