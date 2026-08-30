package cn.zimu.fulfillment.connector.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code app.scheduled_pull_runs} 的读写。
 *
 * <p><b>为什么开始一次运行是「插入」而不是「查了再插」</b>：定时任务在多实例部署下会在
 * 同一秒被每个实例各触发一次。先查后插之间必然有窗口，两个实例都会查到「今天还没跑」，
 * 于是两个实例同时去拉三个平台、同时去确认同一批货。{@code run_key} 的唯一约束把这个
 * 判断交给数据库：{@code ON CONFLICT DO NOTHING} 返回空即代表「别人已经领走了」，
 * 没有任何窗口。
 *
 * <p><b>V85 起 run_key 下沉到渠道</b>（{@code 日期:时段:渠道}）。各平台可以各自设时间之后，
 * 原来的 {@code 日期:时段} 会让先跑完的那个渠道占掉整个时段，后跑的被判成「已被领取」直接
 * 跳过——静默漏拉。同一个唯一约束现在同时承担两件事：跨实例单飞，以及补偿窗口内的重复触发
 * 去重（{@link ScheduledPullPlanner} 会在窗口内每分钟重试一次）。
 */
@Component
class ScheduledPullRunStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    ScheduledPullRunStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 运行时段。名字进 run_key，不要随便改——改了等于当天可以再跑一遍。 */
    enum Slot {
        MORNING,
        EVENING,
        MANUAL
    }

    /** 一次运行的句柄。{@code runDate} 同时是自动发货幂等键的日期部分。 */
    record Run(long id, String runKey, Slot slot, LocalDate runDate, String sourceChannel) {}

    static String runKey(LocalDate runDate, Slot slot, String sourceChannel) {
        return runDate + ":" + slot.name() + ":" + sourceChannel;
    }

    /**
     * 领取「某天某时段某渠道」这一次运行。
     *
     * @param notifyWecom 按触发那一刻的渠道配置固化：卡发不发取决于当时的配置，事后改配置
     *                    不该改写既有运行的含义，否则「这次为什么没发卡」永远查不清
     * @return 领到则返回句柄；已被本实例或其它实例领走、或补偿窗口内的重复触发则返回 empty，
     *         调用方必须直接返回
     */
    Optional<Run> begin(LocalDate runDate, Slot slot, String sourceChannel, boolean notifyWecom) {
        String key = runKey(runDate, slot, sourceChannel);
        List<Long> ids = jdbc.query(
                """
                INSERT INTO app.scheduled_pull_runs
                    (run_key, slot, run_date, source_channel, notify_wecom, status)
                VALUES (?, ?, ?, ?, ?, 'RUNNING')
                ON CONFLICT (run_key) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                key,
                slot.name(),
                runDate,
                sourceChannel,
                notifyWecom);
        return ids.isEmpty()
                ? Optional.empty()
                : Optional.of(new Run(ids.getFirst(), key, slot, runDate, sourceChannel));
    }

    /**
     * 收口一次运行。{@code lock_version} 自增：企微业务卡管道按 (域, 实体, 版本) 建卡，
     * 版本推进才会产生新卡，因此收口只能发生一次——重复收口会推出第二张一模一样的卡。
     */
    void finish(long runId, boolean failed, Summary summary) {
        jdbc.update(
                """
                UPDATE app.scheduled_pull_runs
                SET status=?, pull_summary=?::jsonb, ship_summary=?::jsonb,
                    problem_count=?, shipped_batches=?,
                    finished_at=CURRENT_TIMESTAMP, lock_version=lock_version+1
                WHERE id=? AND status='RUNNING'
                """,
                failed ? "FAILED" : "COMPLETED",
                toJson(summary.pull()),
                toJson(summary.ship()),
                summary.problemCount(),
                summary.shippedBatches(),
                runId);
    }

    /**
     * 一次运行的可播报摘要。
     *
     * @param pull           每渠道拉取结果（无 PII）
     * @param ship           每批次发货结果（无 PII）
     * @param problemCount   需要人处理的条数；0 表示不必打扰任何人
     * @param shippedBatches 本次真正确认发货的批次数
     */
    record Summary(
            List<Map<String, Object>> pull,
            List<Map<String, Object>> ship,
            int problemCount,
            int shippedBatches) {

        static Summary empty() {
            return new Summary(List.of(), List.of(), 0, 0);
        }
    }

    private String toJson(List<Map<String, Object>> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // 摘要写不出 JSON 不该让整次运行失败（货可能已经发出去了），但也不能静默丢掉：
            // 落一个自述损坏的数组，人看到它就知道要去翻审计日志。
            return "[{\"outcome\":\"SUMMARY_SERIALIZATION_FAILED\"}]";
        }
    }
}
