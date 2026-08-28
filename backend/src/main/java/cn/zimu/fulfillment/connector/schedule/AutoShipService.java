package cn.zimu.fulfillment.connector.schedule;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 自动发货：把「完全就绪」的来源批次确认掉，并如实汇报每一批的结局。
 *
 * <p>这是本特性里唯一会花真钱的类——确认会向京东建真实出库单。因此它的每一条纪律
 * 都是「宁可不发，不可错发」：
 *
 * <ol>
 *   <li><b>只发完全就绪的批次</b>。有任何一行阻断就整批交给人（{@link AutoShipReadiness}）。
 *       {@code SourceImportService#confirm} 支持部分确认，但那个能力是给人用的：
 *       人点确认后响应里会列出被跳过的行，他当场就知道少发了什么。定时任务没有这个
 *       环节，用部分确认等于每天固定时刻悄悄少发一批货而无人知晓。</li>
 *   <li><b>幂等</b>。幂等键是 {@code auto-ship-{批次id}-{运行日期}}，
 *       **刻意不含时间戳**——含时间戳的键每次调用都不同，等于没有幂等，
 *       同一批货会被重复确认、重复建单。日期粒度意味着同一天的 09:00 与 18:00
 *       两次运行对同一批次只会成功确认一次，第二次拿到重放。</li>
 *   <li><b>失败不连坐</b>。逐批 try/catch，一批炸掉不影响后面的批次。</li>
 *   <li><b>爆炸半径有界</b>。单次运行最多处理 {@code batch-limit} 个批次，
 *       剩余的下一个时段继续。</li>
 * </ol>
 *
 * <p><b>本 bean 在关闭时根本不存在</b>（{@code @ConditionalOnProperty}），
 * 编排会拿到 {@link SourceBatchAutoShipper#disabled()}。「关掉自动发货」因此是一件
 * 结构上的事情，而不是一个可能被写错的 if——没有任何代码路径能把货发出去。
 *
 * <p><b>操作人</b>固定 {@code system:scheduled-pull}，与拉取同一个身份，
 * 不冒充任何人类操作员。注意它仍必须出现在 {@code app.jd.outbound-authorized-operators}
 * 白名单里，否则 {@code ShipmentJdOutboundService#requireAuthorized} 会 403 ——
 * 「谁能建真单」的裁决权留在配置面，不因为换了入口就放宽。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.scheduled-pull.auto-ship",
        name = "enabled",
        havingValue = "true")
class AutoShipService implements SourceBatchAutoShipper {

    private static final Logger log = LoggerFactory.getLogger(AutoShipService.class);

    /** 与定时拉取同一个系统身份；审计里读到它的人应当立刻知道「这不是谁点的」。 */
    static final String OPERATOR = ScheduledPlatformPullService.OPERATOR;

    private final AutoShipReadiness readiness;
    private final SourceBatchConfirmer confirmer;
    private final AutoShipBlockerReader blockers;
    private final int batchLimit;

    AutoShipService(
            AutoShipReadiness readiness,
            SourceBatchConfirmer confirmer,
            AutoShipBlockerReader blockers,
            @Value("${app.scheduled-pull.auto-ship.batch-limit:20}") int batchLimit) {
        this.readiness = readiness;
        this.confirmer = confirmer;
        this.blockers = blockers;
        this.batchLimit = Math.max(1, batchLimit);
    }

    @Override
    public Outcome shipReadyBatches(LocalDate runDate) {
        List<AutoShipReadiness.Candidate> candidates;
        try {
            candidates = readiness.candidates(batchLimit);
        } catch (RuntimeException exception) {
            // 连候选都取不到时不能假装「今天没货要发」——那会让一次静默的数据库故障
            // 表现成一个平静的空运行。如实记成问题，人会看到。
            log.error("自动发货取候选批次失败 run_date={}", runDate, exception);
            return new Outcome(
                    List.of(entry(0, "", "", "READINESS_QUERY_FAILED", List.of("READINESS_QUERY_FAILED"), "")),
                    1,
                    0);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        int problems = 0;
        int shipped = 0;
        for (AutoShipReadiness.Candidate candidate : candidates) {
            Result result = process(candidate, runDate);
            entries.add(result.entry());
            problems += result.problem() ? 1 : 0;
            shipped += result.shipped() ? 1 : 0;
        }
        return new Outcome(List.copyOf(entries), problems, shipped);
    }

    /** 一个批次的处理结果。 */
    private record Result(Map<String, Object> entry, boolean problem, boolean shipped) {}

    /**
     * 处理一个批次。**本方法不抛异常**：一批失败不能阻断其它批次，这是硬性要求。
     */
    private Result process(AutoShipReadiness.Candidate candidate, LocalDate runDate) {
        if (!candidate.fullyReady()) {
            // 有阻断行：整批交给人。这里是「悄悄少发货」与「有人来看一眼」的分界线。
            log.info(
                    "批次有阻断行，不自动确认 batch_no={} blocked_rows={}",
                    candidate.batchNo(),
                    candidate.blockedRows());
            return new Result(
                    entry(
                            candidate,
                            "SKIPPED_BLOCKED",
                            candidate.blockedCodes(),
                            "阻断 " + candidate.blockedRows() + " 行，待处理 " + candidate.pendingRows() + " 行"),
                    true,
                    false);
        }
        try {
            return confirmAndSubmit(candidate, runDate);
        } catch (BusinessException exception) {
            // 确认被闸门拒绝（如就绪度在取候选之后变了）：如实记码，不重试。
            log.warn(
                    "自动发货被拒 batch_no={} code={} msg={}",
                    candidate.batchNo(),
                    exception.getBusinessCode(),
                    exception.getMessage());
            return new Result(
                    entry(candidate, "CONFIRM_REJECTED", List.of(exception.getBusinessCode()), ""),
                    true,
                    false);
        } catch (RuntimeException exception) {
            // 结局未知：可能已在京东侧建单。**绝不在本次运行内重试**——
            // 幂等键能挡住重复建单，但「结局未知」时的正确动作是让人来看，不是再按一次。
            log.error("自动发货异常 batch_no={}", candidate.batchNo(), exception);
            return new Result(
                    entry(candidate, "CONFIRM_EXCEPTION", List.of("AUTO_SHIP_EXCEPTION"), ""),
                    true,
                    false);
        }
    }

    private Result confirmAndSubmit(AutoShipReadiness.Candidate candidate, LocalDate runDate) {
        IdempotentResult<Map<String, Object>> confirmed =
                confirmer.confirmSourceBatch(candidate.batchId(), idempotencyKey(candidate, runDate), context(candidate));
        if (confirmed.replayed()) {
            // 同一天已经确认过（09:00 发过，18:00 又扫到）。**不得再次触发建单**：
            // 重放返回的是首次结果，再调一次等于对同一批货发起第二次外部建单。
            return new Result(entry(candidate, "ALREADY_CONFIRMED", List.of(), "本日已确认，未重复建单"), false, false);
        }

        List<String> unexpected = unexpectedlySkippedCodes(confirmed.result());
        confirmer.submitJdOutboundsForSourceBatch(candidate.batchId(), context(candidate));
        AutoShipBlockerReader.Failures failures = readFailures(candidate);

        if (!unexpected.isEmpty()) {
            // 本类判定「完全就绪」，闸门却跳过了行——两套判据分叉了。
            // 货已经发了一部分，必须让人立刻知道，且要知道是判据出了问题而不是数据出了问题。
            log.error(
                    "自动发货判据与确认闸门分叉：批次被判完全就绪但闸门跳过了行 batch_no={} codes={}",
                    candidate.batchNo(),
                    unexpected);
            return new Result(
                    entry(candidate, "READINESS_DIVERGED", unexpected, "判据分叉：闸门跳过了行，请核对阻断口径"),
                    true,
                    true);
        }
        if (failures.any()) {
            return new Result(
                    entry(candidate, "SHIPPED_WITH_JD_FAILURES", reasonCodes(failures), failures.describe()),
                    true,
                    true);
        }
        return new Result(entry(candidate, "SHIPPED", List.of(), ""), false, true);
    }

    /**
     * 读回京东侧失败原因。读失败不该掀翻已经发出去的货，但也不能假装成功——
     * 记成一条自述「读不到」的问题，人去后台看。
     */
    private AutoShipBlockerReader.Failures readFailures(AutoShipReadiness.Candidate candidate) {
        try {
            return blockers.of(candidate.batchId());
        } catch (RuntimeException exception) {
            log.error("读取京东建单失败原因失败 batch_no={}", candidate.batchNo(), exception);
            return new AutoShipBlockerReader.Failures(0, List.of(), List.of("BLOCKER_READ_FAILED"));
        }
    }

    /** 归类后的原因码，按「缺货 / 映射校验 / 京东无答复 / 其它」分开，不笼统报失败。 */
    private static List<String> reasonCodes(AutoShipBlockerReader.Failures failures) {
        List<String> codes = new ArrayList<>();
        AutoShipReasons.summarize(failures.blockers()).forEach((category, specific) -> specific.stream()
                .map(code -> category.name() + ":" + code)
                .forEach(codes::add));
        failures.otherCodes().stream().map(code -> AutoShipReasons.Category.OTHER.name() + ":" + code).forEach(codes::add);
        return List.copyOf(codes);
    }

    /**
     * 确认响应里被跳过的行的 error_code。
     *
     * <p>这是镜像判据分叉的探针：本类只确认 {@code blocked_rows = 0} 的批次，
     * 因此 {@code skipped_rows} 理应恒为空。非空即代表 {@link AutoShipBlockedPredicate}
     * 与 file 包里的原件已经不是同一条判据了。
     *
     * <p>只取 {@code error_code}（受控词表），不取 {@code reason}——那是自由文本，
     * 由各解析器拼装，可能带上收件人字段，而本结果会被渲染进企微卡片。
     */
    @SuppressWarnings("unchecked")
    private static List<String> unexpectedlySkippedCodes(Map<String, Object> confirmResult) {
        if (confirmResult == null || !(confirmResult.get("skipped_rows") instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .map(row -> String.valueOf(row.getOrDefault("error_code", "UNKNOWN")))
                .distinct()
                .toList();
    }

    /**
     * 幂等键：批次 id + 运行日期。
     *
     * <p><b>不含时间戳</b>。时间戳每次调用都不同，幂等注册表会把每次都当成新请求，
     * 同一批货会被反复确认、反复建单——那正是这个键要防的事。
     */
    private static String idempotencyKey(AutoShipReadiness.Candidate candidate, LocalDate runDate) {
        return "auto-ship-" + candidate.batchId() + "-" + runDate;
    }

    /**
     * 建单上下文。
     *
     * <p>{@code authenticatedOperator} 必须与 {@code operator} 相等且非空：
     * {@code ShipmentJdOutboundService#requireAuthorized} 三个条件缺一不可，
     * 用三参构造器（authenticatedOperator 为 null）会稳定 403。
     */
    private static CommandContext context(AutoShipReadiness.Candidate candidate) {
        String id = "auto-ship-" + candidate.batchId();
        return new CommandContext(id, id, OPERATOR, OPERATOR);
    }

    private static Map<String, Object> entry(
            AutoShipReadiness.Candidate candidate, String outcome, List<String> reasonCodes, String detail) {
        return entry(
                candidate.batchId(), candidate.batchNo(), candidate.sourceChannel(), outcome, reasonCodes, detail);
    }

    /** 一条批次摘要。字段与 V83 的 {@code ship_summary} 注释对齐；全部无 PII。 */
    private static Map<String, Object> entry(
            long batchId,
            String batchNo,
            String channel,
            String outcome,
            List<String> reasonCodes,
            String detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("batch_id", String.valueOf(batchId));
        row.put("batch_no", batchNo == null ? "" : batchNo);
        row.put("channel", channel == null ? "" : channel);
        row.put("outcome", outcome);
        row.put("reason_codes", List.copyOf(reasonCodes));
        row.put("detail", detail == null ? "" : detail);
        return Map.copyOf(row);
    }
}
