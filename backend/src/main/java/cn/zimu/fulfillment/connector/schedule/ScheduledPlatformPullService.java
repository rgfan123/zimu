package cn.zimu.fulfillment.connector.schedule;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.PlatformOrderRefreshService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 定时来源渠道拉取的编排：领运行 → 拉取 → （可选）自动发货 → 收口。
 *
 * <p><b>一次运行只负责一个渠道</b>（V85 起）。各平台各自设拉取时间之后，「一次运行拉全部」
 * 就表达不了了；更要命的是运行记录的 {@code run_key} 只有 {@code 日期:时段} 时，先跑完的
 * 渠道会占掉整个时段，后跑的被判成「已被领取」直接跳过——静默漏拉。
 *
 * <p><b>不另造拉取路径</b>是本类的第一条纪律。拉取一律走
 * {@link PlatformOrderRefreshService#refresh}——能力门禁、单渠道单飞、脚本回退、
 * last_error 落库、审计全都长在那条路径上。定时触发与人工点击因此走完全同一段代码，
 * 只有操作人身份不同。请求体里只填渠道，日期窗口仍留空由那个方法按
 * {@code app.platform-pull.default-days} 决定——定时任务不该另外发明一个窗口配置，
 * 那会和人工拉取的口径分叉（本票也明确不做「拉取窗口天数」）。
 *
 * <p><b>操作人身份</b>固定 {@code system:scheduled-pull}，参照既有
 * {@code system:platform-pull} 的写法。不冒充任何人类操作员：审计里读到这个身份的人
 * 应当立刻知道「这不是谁点的，是定时器跑的」。
 *
 * <p><b>失败边界</b>：三渠道全失败时 refresh 会抛 {@code PLATFORM_REFRESH_ALL_FAILED}
 * （502）。那是给 HTTP 调用方的契约，对定时任务不是异常而是一种结果——捕获后照常
 * 收口并播报，不让它把运行记录留在 RUNNING。
 */
@Service
public class ScheduledPlatformPullService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPlatformPullService.class);
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 系统身份：明确的机器身份，不冒充人类操作员。 */
    static final String OPERATOR = "system:scheduled-pull";

    private final PlatformOrderRefreshService refreshService;
    private final ScheduledPullRunStore runs;
    private final AuditLogService auditLogService;
    private final SourceBatchAutoShipper autoShipper;
    private final ChannelPullScheduleStore schedules;
    private final Duration catchUp;

    ScheduledPlatformPullService(
            PlatformOrderRefreshService refreshService,
            ScheduledPullRunStore runs,
            AuditLogService auditLogService,
            Optional<SourceBatchAutoShipper> autoShipper,
            ChannelPullScheduleStore schedules,
            @Value("${app.scheduled-pull.catch-up-minutes:30}") int catchUpMinutes) {
        this.refreshService = refreshService;
        this.runs = runs;
        this.auditLogService = auditLogService;
        this.autoShipper = autoShipper.orElse(SourceBatchAutoShipper.disabled());
        this.schedules = schedules;
        this.catchUp = Duration.ofMinutes(Math.max(1, catchUpMinutes));
    }

    /**
     * 每分钟一跳的入口：问每个渠道「你现在该拉了吗」，命中的才拉。
     *
     * <p>时区固定 {@link #SHANGHAI}，与 {@code run_date} 同源。用系统默认时区的话，容器
     * 时区一变，「今天」的边界就和 run_key 里的日期错位，跨零点会重复跑或漏跑。
     *
     * <p>本方法自己不抛异常：调度线程拿到异常也无处可去。**渠道之间也不连坐**——领运行时
     * 数据库抖了一下，不该让同一分钟里其它渠道的那一档跟着丢掉。失败的那个渠道由补偿窗口
     * 在后面几分钟里自己重试。
     *
     * @return 本轮真正领到并跑起来的运行 id
     */
    public List<Long> runDue() {
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        List<ScheduledPullPlanner.Due> due =
                ScheduledPullPlanner.due(now, schedules.loadScheduled(), catchUp);
        List<Long> started = new ArrayList<>();
        for (ScheduledPullPlanner.Due item : due) {
            try {
                runOnce(item.slot(), item.sourceChannel(), item.notifyWecom()).ifPresent(started::add);
            } catch (RuntimeException exception) {
                log.error(
                        "定时拉取本渠道未能起跑，其余渠道继续 slot={} channel={}",
                        item.slot(),
                        item.sourceChannel(),
                        exception);
            }
        }
        return List.copyOf(started);
    }

    /**
     * 跑一次「某渠道某档的拉取 + 自动发货」。
     *
     * <p>本方法自己不抛异常：定时触发器拿到异常也无处可去，而运行记录必须收口。
     *
     * @param notifyWecom 拉完是否允许发企微播报卡；按调用那一刻的配置固化进运行记录
     * @return 本次运行的 id；被别的实例领走、或补偿窗口内已经跑过则 empty
     */
    public Optional<Long> runOnce(
            ScheduledPullRunStore.Slot slot, String sourceChannel, boolean notifyWecom) {
        LocalDate runDate = LocalDate.now(SHANGHAI);
        Optional<ScheduledPullRunStore.Run> claimed =
                runs.begin(runDate, slot, sourceChannel, notifyWecom);
        if (claimed.isEmpty()) {
            // 补偿窗口内每分钟都会再试一次，这条日志因此是常态而非异常，记 debug 不记 info。
            log.debug(
                    "定时拉取已被领取，本次不重复触发 slot={} channel={} date={}",
                    slot,
                    sourceChannel,
                    runDate);
            return Optional.empty();
        }
        ScheduledPullRunStore.Run run = claimed.get();
        long started = System.nanoTime();
        boolean failed = false;
        ScheduledPullRunStore.Summary summary = ScheduledPullRunStore.Summary.empty();
        try {
            summary = execute(run);
        } catch (RuntimeException exception) {
            // 编排本身炸了（不是渠道失败，那个已在 pull 里收敛）：如实记 FAILED。
            log.error("定时拉取编排异常 run_key={}", run.runKey(), exception);
            failed = true;
            summary = new ScheduledPullRunStore.Summary(
                    List.of(problem("ORCHESTRATION_FAILED", String.valueOf(exception.getMessage()))),
                    List.of(),
                    1,
                    0);
        } finally {
            try {
                runs.finish(run.id(), failed, summary);
            } catch (RuntimeException exception) {
                // 收口失败会让这次运行永远停在 RUNNING，从而挡住同一时段的重跑。
                // 这里只能记日志：货可能已经发了，不该再抛出去掩盖前面的结果。
                log.error("定时拉取收口失败，运行将停留在 RUNNING run_key={}", run.runKey(), exception);
            }
            audit(run, summary, failed, (int) ((System.nanoTime() - started) / 1_000_000));
        }
        return Optional.of(run.id());
    }

    private ScheduledPullRunStore.Summary execute(ScheduledPullRunStore.Run run) {
        List<Map<String, Object>> pull = pull(run);
        SourceBatchAutoShipper.Outcome shipped =
                autoShipper.shipReadyBatches(run.runDate(), run.sourceChannel());
        int problems = (int) pull.stream().filter(ScheduledPlatformPullService::isProblem).count()
                + shipped.problemCount();
        return new ScheduledPullRunStore.Summary(pull, shipped.entries(), problems, shipped.shippedBatches());
    }

    /** 本渠道拉取。渠道级失败由 refresh 自己收敛成结果行，不会抛出来。 */
    private List<Map<String, Object>> pull(ScheduledPullRunStore.Run run) {
        CommandContext context = commandContext(run);
        try {
            // 只指定渠道，日期窗口留空由 refresh 按 default-days 决定：
            // 窗口口径归一处，定时与人工点击必须一致。
            Map<String, Object> result =
                    refreshService.refreshChannels(List.of(run.sourceChannel()), context);
            return channelRows(result.get("channels"));
        } catch (BusinessException exception) {
            // 全渠道未成功：对 HTTP 是 502，对定时任务只是「今天没拉到东西」。
            Map<String, Object> details = exception.getDetails();
            List<Map<String, Object>> rows =
                    details == null ? List.of() : channelRows(details.get("channels"));
            return rows.isEmpty()
                    ? List.of(problem(exception.getBusinessCode(), exception.getMessage()))
                    : rows;
        } catch (RuntimeException exception) {
            log.error("定时拉取失败 run_key={}", run.runKey(), exception);
            return List.of(problem("PLATFORM_PULL_EXCEPTION", String.valueOf(exception.getMessage())));
        }
    }

    /**
     * 从 refresh 结果里摘出每渠道一行摘要。
     *
     * <p>**只取白名单字段**：refresh 的渠道结果里含 {@code command}（拉取命令行）与
     * {@code script_output}（脚本尾部输出），后者可能带平台订单文本。本表摘要会被渲染
     * 进企微卡片，整份透传等于把它们送进会话。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> channelRows(Object channels) {
        if (!(channels instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> channel = (Map<String, Object>) raw;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", text(channel.get("channel")));
            row.put("status", text(channel.get("status")));
            row.put("business_code", text(channel.get("business_code")));
            row.put("message", text(channel.get("message")));
            row.put("batch_id", text(channel.get("batch_id")));
            row.put("batch_no", text(channel.get("batch_no")));
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    /** 拉取侧「需要人看」的判据：只有 FAILED 算问题；SKIPPED 是门禁按预期挡下，不是故障。 */
    private static boolean isProblem(Map<String, Object> row) {
        return "FAILED".equals(row.get("status"));
    }

    private static Map<String, Object> problem(String businessCode, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", "ALL");
        row.put("status", "FAILED");
        row.put("business_code", businessCode == null ? "PLATFORM_PULL_FAILED" : businessCode);
        row.put("message", message == null ? "" : message);
        return Map.copyOf(row);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 拉取上下文：requestId/traceId 同源，审计可按 run_key 关联回运行记录。 */
    private static CommandContext commandContext(ScheduledPullRunStore.Run run) {
        String id = "scheduled-pull-" + run.id();
        return new CommandContext(id, id, OPERATOR, OPERATOR);
    }

    /**
     * 运行级审计。
     *
     * <p>**必须自己写一条**：下游 {@code SourceImportService#confirmResult} 的审计把
     * actorType 硬编码为 HUMAN，定时任务确认的批次在那条记录里看起来像人点的。
     * 这一条是唯一如实标注 SYSTEM 的记录，出事时按它对齐时间线。
     */
    private void audit(
            ScheduledPullRunStore.Run run,
            ScheduledPullRunStore.Summary summary,
            boolean failed,
            int latencyMs) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("pull", summary.pull());
            response.put("ship", summary.ship());
            response.put("problem_count", summary.problemCount());
            response.put("shipped_batches", summary.shippedBatches());
            auditLogService.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId("scheduled-pull-" + run.id())
                    .traceId("scheduled-pull-" + run.id())
                    .operator(OPERATOR)
                    .actorType(AuditActorType.SYSTEM)
                    .service("scheduled-platform-pull")
                    .operation("scheduled-pull.run")
                    .requestPayload(Map.of(
                            "run_key", run.runKey(),
                            "slot", run.slot().name(),
                            "source_channel", run.sourceChannel()))
                    .responsePayload(response)
                    .httpStatus(failed ? 500 : 200)
                    .businessCode(failed ? "SCHEDULED_PULL_FAILED" : "SCHEDULED_PULL_COMPLETED")
                    .latencyMs(latencyMs));
        } catch (RuntimeException exception) {
            log.error("定时拉取审计写入失败 run_key={}", run.runKey(), exception);
        }
    }
}
