package cn.zimu.fulfillment.connector.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 从真实的 {@code connector_configs.config}（jsonb）读时间表。
 *
 * <p>用真库而不是 mock：本类的全部价值在于「jsonb 里长什么样都不能让拉取停下来」，
 * 而 jsonb 的类型行为（数组、字符串、嵌套对象）正是 mock 掉就测不到的那部分。
 */
@Testcontainers
class ChannelPullScheduleStoreTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    private JdbcTemplate jdbc;
    private ChannelPullScheduleStore store;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        PGSimpleDataSource configured = new PGSimpleDataSource();
        configured.setURL(postgres.getJdbcUrl());
        configured.setUser(postgres.getUsername());
        configured.setPassword(postgres.getPassword());
        dataSource = configured;
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        // 每个用例从「谁都没配过」开始。
        jdbc.update("UPDATE app.connector_configs SET config = config - 'pull_schedule'");
        store = new ChannelPullScheduleStore(jdbc, new ObjectMapper(), "09:00", "18:00");
    }

    private void writeSchedule(String channel, String json) {
        jdbc.update(
                "UPDATE app.connector_configs"
                        + " SET config = jsonb_set(COALESCE(config,'{}'::jsonb),"
                        + " ARRAY['pull_schedule'], ?::jsonb, true)"
                        + " WHERE source_channel = ?",
                json,
                channel);
    }

    @Test
    void everyScheduledChannelAlwaysGetsASchedule() {
        Map<String, ChannelPullSchedule> loaded = store.loadScheduled();

        assertThat(loaded.keySet())
                .containsExactlyInAnyOrderElementsOf(store.scheduledChannels())
                .contains("CAISHIXIAN", "JUFUBAO", "FEIXIANG");
    }

    @Test
    void anUnconfiguredChannelBehavesExactlyLikeBeforeThisFeature() {
        ChannelPullSchedule schedule = store.loadScheduled().get("CAISHIXIAN");

        assertThat(schedule.morning()).isEqualTo(new ChannelPullSchedule.Slot(true, LocalTime.of(9, 0)));
        assertThat(schedule.evening()).isEqualTo(new ChannelPullSchedule.Slot(true, LocalTime.of(18, 0)));
        assertThat(schedule.notifyWecom()).isTrue();
    }

    @Test
    void anExplicitPerChannelScheduleWins() {
        writeSchedule(
                "FEIXIANG",
                """
                {"morning":{"enabled":true,"at":"10:30"},
                 "evening":{"enabled":false,"at":"20:00"},
                 "notify_wecom":false}
                """);

        ChannelPullSchedule schedule = store.loadScheduled().get("FEIXIANG");

        assertThat(schedule.morning()).isEqualTo(new ChannelPullSchedule.Slot(true, LocalTime.of(10, 30)));
        assertThat(schedule.evening().enabled()).isFalse();
        assertThat(schedule.notifyWecom()).isFalse();
        // 其它渠道不受影响。
        assertThat(store.loadScheduled().get("CAISHIXIAN").morning().at()).isEqualTo(LocalTime.of(9, 0));
    }

    @Test
    void aStructurallyWrongScheduleFallsBackToPullingNotToSilence() {
        // jsonb 里塞了个数组：读不懂就按全局默认拉，绝不能变成「今天不拉了」。
        writeSchedule("JUFUBAO", "[1,2,3]");

        ChannelPullSchedule schedule = store.loadScheduled().get("JUFUBAO");

        assertThat(schedule.morning().enabled()).isTrue();
        assertThat(schedule.morning().at()).isEqualTo(LocalTime.of(9, 0));
        assertThat(schedule.evening().enabled()).isTrue();
    }

    @Test
    void aDatabaseFailureFallsBackToTheDefaultsInsteadOfStoppingTheSchedule() {
        // 连库都读不到时最容易写出「返回空 = 今天不拉」。那会让一次数据库抖动表现成
        // 系统安静停摆，界面上看不出任何异常——本仓已经因为同类问题丢过单。
        PGSimpleDataSource broken = new PGSimpleDataSource();
        broken.setURL("jdbc:postgresql://127.0.0.1:1/nonexistent");
        broken.setUser("nobody");
        broken.setPassword("nobody");
        broken.setConnectTimeout(1);
        ChannelPullScheduleStore store =
                new ChannelPullScheduleStore(new JdbcTemplate(broken), new ObjectMapper(), "09:00", "18:00");

        Map<String, ChannelPullSchedule> loaded = store.loadScheduled();

        assertThat(loaded.keySet()).containsExactlyInAnyOrderElementsOf(store.scheduledChannels());
        assertThat(loaded.values()).allSatisfy(schedule -> {
            assertThat(schedule.morning()).isEqualTo(new ChannelPullSchedule.Slot(true, LocalTime.of(9, 0)));
            assertThat(schedule.evening()).isEqualTo(new ChannelPullSchedule.Slot(true, LocalTime.of(18, 0)));
            assertThat(schedule.notifyWecom()).isTrue();
        });
    }

    @Test
    void aBrokenGlobalDefaultStillPullsAtNineAndSix() {
        ChannelPullScheduleStore broken =
                new ChannelPullScheduleStore(jdbc, new ObjectMapper(), "不是时间", "");

        assertThat(broken.defaults().morning().at()).isEqualTo(LocalTime.of(9, 0));
        assertThat(broken.defaults().evening().at()).isEqualTo(LocalTime.of(18, 0));
    }
}
