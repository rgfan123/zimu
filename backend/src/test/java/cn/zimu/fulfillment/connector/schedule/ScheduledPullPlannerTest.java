package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 「现在该拉哪些渠道」的判据：错峰、单档停用、补偿窗口、以及补偿不跨零点。 */
class ScheduledPullPlannerTest {

    private static final LocalDateTime TEN_AM = LocalDateTime.of(2026, 8, 30, 10, 0);

    private static ChannelPullSchedule schedule(String morningAt, String eveningAt) {
        return new ChannelPullSchedule(
                new ChannelPullSchedule.Slot(true, LocalTime.parse(morningAt)),
                new ChannelPullSchedule.Slot(true, LocalTime.parse(eveningAt)),
                true);
    }

    @Test
    void onlyTheChannelWhoseTimeItIsGetsPulled() {
        List<ScheduledPullPlanner.Due> due = ScheduledPullPlanner.due(
                TEN_AM,
                Map.of(
                        "CAISHIXIAN", schedule("08:00", "18:00"),
                        "FEIXIANG", schedule("10:00", "20:00")),
                Duration.ofMinutes(30));

        assertThat(due).singleElement().satisfies(item -> {
            assertThat(item.sourceChannel()).isEqualTo("FEIXIANG");
            assertThat(item.slot()).isEqualTo(ScheduledPullRunStore.Slot.MORNING);
        });
    }

    @Test
    void aChannelWithBothSlotsAtDifferentTimesFiresTwiceADayNotAtOnce() {
        Map<String, ChannelPullSchedule> schedules = Map.of("FEIXIANG", schedule("10:00", "20:00"));

        assertThat(ScheduledPullPlanner.due(TEN_AM, schedules, Duration.ofMinutes(30)))
                .extracting(ScheduledPullPlanner.Due::slot)
                .containsExactly(ScheduledPullRunStore.Slot.MORNING);
        assertThat(ScheduledPullPlanner.due(
                        TEN_AM.withHour(20), schedules, Duration.ofMinutes(30)))
                .extracting(ScheduledPullPlanner.Due::slot)
                .containsExactly(ScheduledPullRunStore.Slot.EVENING);
    }

    @Test
    void disablingOneSlotLeavesTheOtherAlone() {
        ChannelPullSchedule morningOff = new ChannelPullSchedule(
                new ChannelPullSchedule.Slot(false, LocalTime.of(10, 0)),
                new ChannelPullSchedule.Slot(true, LocalTime.of(10, 0)),
                true);

        assertThat(ScheduledPullPlanner.due(
                        TEN_AM, Map.of("FEIXIANG", morningOff), Duration.ofMinutes(30)))
                .extracting(ScheduledPullPlanner.Due::slot)
                .containsExactly(ScheduledPullRunStore.Slot.EVENING);
    }

    @Test
    void bothSlotsOffMeansTheChannelIsNeverPulled() {
        ChannelPullSchedule allOff = new ChannelPullSchedule(
                new ChannelPullSchedule.Slot(false, LocalTime.of(10, 0)),
                new ChannelPullSchedule.Slot(false, LocalTime.of(10, 0)),
                true);

        assertThat(ScheduledPullPlanner.due(
                        TEN_AM, Map.of("FEIXIANG", allOff), Duration.ofMinutes(30)))
                .isEmpty();
    }

    @Test
    void aMissedTickIsStillPickedUpInsideTheCatchUpWindow() {
        Map<String, ChannelPullSchedule> schedules = Map.of("FEIXIANG", schedule("10:00", "20:00"));

        // 配置时刻过去 29 分钟：重启/停顿错过了那一分钟，补偿窗口仍能把这一档捞回来。
        assertThat(ScheduledPullPlanner.due(
                        TEN_AM.plusMinutes(29), schedules, Duration.ofMinutes(30)))
                .hasSize(1);
        // 窗口外就不再补：迟到两小时才去拉，对一天两次的节奏已经没有意义。
        assertThat(ScheduledPullPlanner.due(
                        TEN_AM.plusMinutes(30), schedules, Duration.ofMinutes(30)))
                .isEmpty();
    }

    @Test
    void aSlotIsNeverDueBeforeItsConfiguredTime() {
        assertThat(ScheduledPullPlanner.due(
                        TEN_AM.minusMinutes(1),
                        Map.of("FEIXIANG", schedule("10:00", "20:00")),
                        Duration.ofMinutes(30)))
                .isEmpty();
    }

    @Test
    void theCatchUpWindowNeverReachesAcrossMidnight() {
        // 23:50 配的那一档，次日 00:10 仍在「+30 分钟」里，但 run_date 已经翻篇、run_key 是新的一把，
        // 唯一约束拦不住——补偿窗口若跨天，等于每天多跑一次。
        assertThat(ScheduledPullPlanner.due(
                        LocalDateTime.of(2026, 8, 31, 0, 10),
                        Map.of("FEIXIANG", schedule("23:50", "23:55")),
                        Duration.ofMinutes(30)))
                .isEmpty();
    }

    @Test
    void zeroCatchUpStillCoversTheConfiguredMinuteItself() {
        // 补偿窗口配成 0 也不能变成「永远不跑」：至少要覆盖配置时刻所在的那一整分钟。
        assertThat(ScheduledPullPlanner.due(
                        TEN_AM.plusSeconds(30),
                        Map.of("FEIXIANG", schedule("10:00", "20:00")),
                        Duration.ZERO))
                .hasSize(1);
    }
}
