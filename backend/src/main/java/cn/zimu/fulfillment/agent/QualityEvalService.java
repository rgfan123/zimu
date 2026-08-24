package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * QUALITY 评测编排（07 决策 11 中间路线；meta-agent-platform-impl 09）：提交（异步入队）与
 * 执行（promptfoo 生成 YAML → {@link QualityEvalRunner} 执行 → 结果回写 {@code
 * app.agent_eval_results}）。
 *
 * <p>运行形态：异步任务（{@code QUALITY_EVAL}，复用 Spring Worker 模式）执行，以
 * {@code run_mode=PREVIEW} 落 {@code agent_runs}——不污染 LIVE 统计与 09 基线；结果是
 * 参考指标（供确认人参考），失败落 FAILED 结果与 FAILED 观测行后任务收口成功、不进入
 * 确认阻断链（重试语义由入队 {@code maxAttempts} 控制，默认 1 = 不重试）。
 * 密钥只经环境变量（YAML 仅 {@code {{env.DEEPSEEK_API_KEY}}} 引用），不入 DB/日志/产物；
 * {@code agent_eval_results.details} 只存逐条得分与稳定错误摘要，绝不落模型原始输出。
 *
 * <p>执行按提交时冻结的 (agent_slug, agent_version) 取定义与用例集（07 决策 #2：每版本
 * 冻结一份用例集，可复现可回滚）——运行期间新版本激活不影响已提交评测。
 */
@Service
public class QualityEvalService {

    public static final String TASK_TYPE = "QUALITY_EVAL";
    public static final String BUSINESS_ENTITY_TYPE = "AGENT_EVAL";

    private final AgentRegistryHolder holder;
    private final AgentDefinitionRepository definitions;
    private final AgentEvalCaseRepository cases;
    private final QualityEvalRunner runner;
    private final AgentObservability observability;
    private final AsyncTaskStore taskStore;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public QualityEvalService(
            AgentRegistryHolder holder,
            AgentDefinitionRepository definitions,
            AgentEvalCaseRepository cases,
            QualityEvalRunner runner,
            AgentObservability observability,
            AsyncTaskStore taskStore,
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.holder = holder;
        this.definitions = definitions;
        this.cases = cases;
        this.runner = runner;
        this.observability = observability;
        this.taskStore = taskStore;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 提交一次 QUALITY 评测：对当前 active 定义入队异步任务并返回 run_id（结果按 run_id
     * 关联 {@code agent_eval_results} 与 {@code agent_runs} PREVIEW 行；载荷冻结
     * (slug, version) 供执行期复现）。
     */
    public String submit(String agentSlug, String triggeredBy) {
        AgentDefinition definition = holder.current().bySlug(agentSlug);
        if (definition == null) {
            throw BusinessException.notFound("Agent 未注册: " + agentSlug);
        }
        String runId = AgentRuntimeFacade.newRunId();
        taskStore.enqueue(
                TASK_TYPE,
                agentSlug + ":" + definition.version() + ":" + runId,
                "quality-eval:" + agentSlug + ":v" + definition.version(),
                1);
        return runId;
    }

    /** 执行一次评测（worker 路径）：载荷 {@code slug:version:runId}，按冻结版本取定义与用例。 */
    public void execute(AsyncTaskStore.AsyncTask task) {
        String[] parts = task.payloadRef().split(":", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("QUALITY_EVAL 载荷非法: " + task.payloadRef());
        }
        String agentSlug = parts[0];
        int version;
        try {
            version = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("QUALITY_EVAL 载荷版本非法: " + task.payloadRef());
        }
        String runId = parts[2];
        AgentDefinition definition = definitions.findVersion(agentSlug, version)
                .orElseThrow(() -> new IllegalStateException(
                        "QUALITY_EVAL 目标定义不存在: " + agentSlug + " v" + version));
        List<QualityEvalCase> qualityCases = cases.qualityCases(agentSlug, definition.version());
        long startedNanos = System.nanoTime();
        safeRunStarted(new AgentObservability.Start(
                runId,
                "quality-eval",
                agentSlug,
                null,
                definition.promptVersion(),
                definition.modelRef(),
                AgentPayloadRedactor.digest(String.join(
                        "|", qualityCases.stream().map(QualityEvalCase::input).toList())),
                BUSINESS_ENTITY_TYPE,
                agentSlug,
                "PREVIEW"));
        try {
            Path dir = Files.createTempDirectory("promptfoo-");
            Path chat = dir.resolve("chat.json");
            Path config = dir.resolve("promptfoo.yaml");
            Path output = dir.resolve("results.json");
            Files.writeString(chat, PromptfooYamlGenerator.chatMessagesJson(definition), StandardCharsets.UTF_8);
            Files.writeString(config, PromptfooYamlGenerator.generateConfig(definition, qualityCases), StandardCharsets.UTF_8);
            QualityEvalRunner.RunResult run = runner.run(config, output);
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            if (!run.succeeded()) {
                // 只留稳定摘要（退出码），不落控制台原文（可能含模型输出）
                writeResult(runId, agentSlug, version, "FAILED", 0, 0,
                        errorDetails("promptfoo eval 失败（exit " + run.exitCode() + "）"));
                safeRunFinished(
                        AgentObservability.Finish.of(runId, "QUALITY_EVAL_FAILED", latencyMs, null));
                return;
            }
            PromptfooEvalResult parsed = PromptfooEvalResult.parse(run.outputJson());
            writeResult(runId, agentSlug, version, "SUCCEEDED",
                    parsed.caseCount(), parsed.passedCount(), scoreDetails(qualityCases, parsed));
            safeRunFinished(AgentObservability.Finish.of(runId, null, latencyMs, null));
        } catch (RuntimeException ex) {
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            writeResult(runId, agentSlug, version, "FAILED", 0, 0, errorDetails(summary(ex)));
            safeRunFinished(
                    AgentObservability.Finish.of(runId, "QUALITY_EVAL_FAILED", latencyMs, null));
            throw ex;
        } catch (Exception ex) {
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            writeResult(runId, agentSlug, version, "FAILED", 0, 0, errorDetails(summary(ex)));
            safeRunFinished(
                    AgentObservability.Finish.of(runId, "QUALITY_EVAL_FAILED", latencyMs, null));
            throw new IllegalStateException("QUALITY 评测执行失败", ex);
        }
    }

    private void writeResult(
            String runId, String agentSlug, int version, String status, int caseCount, int passedCount, ObjectNode details) {
        jdbc.update(
                """
                INSERT INTO app.agent_eval_results
                    (run_id, agent_slug, agent_version, metric_kind, case_count, passed_count,
                     status, details, finished_at)
                VALUES (?, ?, ?, 'QUALITY', ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                """,
                runId,
                agentSlug,
                version,
                caseCount,
                passedCount,
                status,
                details == null ? null : details.toString());
    }

    /** 逐条得分按用例 id 关联（07 决策 #2：按 run_id / 用例可查），不落模型原始输出。 */
    private ObjectNode scoreDetails(List<QualityEvalCase> qualityCases, PromptfooEvalResult parsed) {
        ObjectNode node = mapper.createObjectNode();
        ArrayNode cases = node.putArray("cases");
        int size = Math.min(parsed.scores().size(), qualityCases.size());
        for (int i = 0; i < size; i++) {
            ObjectNode item = cases.addObject();
            item.put("case_id", qualityCases.get(i).id());
            item.put("score", parsed.scores().get(i));
        }
        return node;
    }

    /** 稳定错误摘要（不落模型输出/日志原文/路径细节）。 */
    private ObjectNode errorDetails(String summary) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", summary == null ? "未知错误" : summary);
        return node;
    }

    /** 异常摘要：类型名 + 截断消息（200 字符内）。 */
    private static String summary(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String stripped = message.strip();
        return ex.getClass().getSimpleName() + ": "
                + (stripped.length() > 200 ? stripped.substring(0, 200) + "…" : stripped);
    }

    /** 观测失败隔离：runStarted/runFinished 不得影响评测结果与结果回写（与门面同款容忍语义）。 */
    private void safeRunStarted(AgentObservability.Start start) {
        try {
            observability.runStarted(start);
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖评测（失败隔离契约）
        }
    }

    private void safeRunFinished(AgentObservability.Finish finish) {
        try {
            observability.runFinished(finish);
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖评测（失败隔离契约）
        }
    }
}
