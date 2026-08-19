package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 数据查询 Agent（06 票；meta-agent-platform-impl 05 收敛为门面薄包装）：自然语言问题 →
 * 门面（定义驱动）→ 结构化答案（{@link DataQueryAgentOutput}）。
 *
 * <p>注册表解析 / enabled 判定 / run_id / 工具绑定 / 模型运行 / 审计 / 观测全部由
 * {@link AgentRuntimeFacade} 承接（04 决策：权限/守卫/审计/观测留 Control Plane）；
 * 本类只保留领域层：
 * <ul>
 *   <li>领域守卫（决策 05：不进平台默认链）——PII 拒绝与歧义澄清在模型调用前确定性短路
 *       （outcome=REJECTED / NEEDS_INPUT，留拒绝审计），不猜测参数、不发起模型调用；</li>
 *   <li>输出 record 反序列化——门面返回的传输层 {@code JsonNode} 经
 *       {@code treeToValue} 还原为 {@link DataQueryAgentOutput}（不丢类型安全）；</li>
 *   <li>业务 run-result 组装（{@link DataQueryRunResult}，run_id/latency 取自门面富化）。</li>
 * </ul>
 *
 * <p>工具调用序列的审计级 {@code tool_call_sequence} 通道随编排层收敛删除——工具调用的
 * 规范化可观测记录在 {@code app.agent_tool_calls}（08 票），run_id 关联不变。
 */
@Service
public class DataQueryAgentService {

    /** 注册表 slug（与 V33 种子 data-query-agent 一致）。 */
    public static final String AGENT_SLUG = "data-query-agent";

    private static final String DEFAULT_OPERATOR = "agent";

    private final AgentRuntimeFacade facade;
    private final AuditLogService audits;
    private final ObjectMapper mapper;

    public DataQueryAgentService(AgentRuntimeFacade facade, AuditLogService audits, ObjectMapper mapper) {
        this.facade = facade;
        this.audits = audits;
        this.mapper = mapper;
    }

    /**
     * 用自然语言问题运行一次数据查询（可选会话上下文 thread_id 透传审计）。
     *
     * <p>PII / 歧义路径不触碰模型，返回确定性结果并留拒绝审计；模型路径经门面运行，
     * 失败以 {@link AgentFailureCode} 稳定码返回，不抛异常。
     */
    public DataQueryRunResult answer(String question, AgentRunContext context) {
        AgentRunContext ctx = context == null ? AgentRunContext.empty() : context;

        if (question == null || question.isBlank()) {
            DataQueryAgentOutput output = new DataQueryAgentOutput(
                    "请说明要查询的内容（如：最近 7 天缺货的订单行数、SKU 进货价与零售价、采购工单缺口数量）。",
                    List.of(),
                    0.0,
                    true,
                    List.of("缺少查询内容"));
            return guardResult(ctx, output, AgentOutcome.NEEDS_INPUT);
        }

        List<String> pii = DataQueryAgentGuard.piiProblems(question);
        if (!pii.isEmpty()) {
            DataQueryAgentOutput output = new DataQueryAgentOutput(
                    "该查询涉及客户/收货人 PII，数据查询 Agent 无 PII 工具，已转人工处理"
                            + "（requires_human=true），未发起任何工具调用。",
                    List.of(),
                    0.0,
                    true,
                    List.of());
            return guardResult(ctx, output, AgentOutcome.REJECTED);
        }

        List<String> ambiguous = DataQueryAgentGuard.ambiguityProblems(question);
        if (!ambiguous.isEmpty()) {
            DataQueryAgentOutput output = new DataQueryAgentOutput(
                    "问题缺少必要信息，按歧义澄清策略未发起任何工具调用；请补充下列信息后重试。",
                    List.of(),
                    0.0,
                    true,
                    ambiguous);
            return guardResult(ctx, output, AgentOutcome.NEEDS_INPUT);
        }

        // 模型路径：门面（注册表/enabled/绑定/审计/观测全部由门面承接，定义驱动）
        AgentRunResult result = facade.invoke(AGENT_SLUG, question, ctx);
        if (result.error() != null) {
            // REJECTED / FAILED：稳定失败码（门面已留审计与观测）
            return new DataQueryRunResult(
                    null, result.error(), result.runId(), result.error(), List.of(), result.latencyMs());
        }
        try {
            DataQueryAgentOutput output =
                    mapper.treeToValue(result.output(), DataQueryAgentOutput.class);
            // 模型路径澄清（04 决策：NEEDS_INPUT 不再是失败）：输出带澄清要求即按 NEEDS_INPUT 收口
            boolean modelClarified = output != null && !output.clarification_needed().isEmpty();
            String status = modelClarified ? AgentOutcome.NEEDS_INPUT.name() : result.outcome().name();
            return new DataQueryRunResult(
                    output,
                    null,
                    result.runId(),
                    status,
                    List.of(),
                    result.latencyMs());
        } catch (Exception ex) {
            // 传输层 JsonNode 无法还原为业务 record：按输出无效收口（不把解析细节带进结果）
            return new DataQueryRunResult(
                    null,
                    AgentFailureCode.AGENT_OUTPUT_INVALID.name(),
                    result.runId(),
                    AgentFailureCode.AGENT_OUTPUT_INVALID.name(),
                    List.of(),
                    result.latencyMs());
        }
    }

    /** 领域守卫短路：确定性结果 + 拒绝审计（NEEDS_INPUT 不再是失败；REJECTED 转人工）。 */
    private DataQueryRunResult guardResult(
            AgentRunContext ctx, DataQueryAgentOutput output, AgentOutcome outcome) {
        String runId = AgentRuntimeFacade.newRunId();
        recordGuardAudit(ctx, runId, outcome);
        return new DataQueryRunResult(output, null, runId, outcome.name(), List.of(), 0);
    }

    /** 守卫拒绝审计（操作与门面一致：agent.data-query-agent.run；业务码 = outcome）。 */
    private void recordGuardAudit(AgentRunContext ctx, String runId, AgentOutcome outcome) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", outcome.name());
            response.put("provider", "none");
            response.put("model", "none");
            response.put("prompt_version", "none");
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(runId)
                    .traceId(runId)
                    .operator(ctx.operator() == null || ctx.operator().isBlank()
                            ? DEFAULT_OPERATOR
                            : ctx.operator())
                    .actorType(AuditActorType.AGENT)
                    .service("agent")
                    .operation("agent." + AGENT_SLUG + ".run")
                    .requestPayload(Map.of(
                            "agent_slug", AGENT_SLUG,
                            "run_id", runId,
                            "thread_id", ctx.threadId(),
                            "prompt_version", "none",
                            "model_ref", "none",
                            "tool_names", List.of()))
                    .responsePayload(response)
                    .businessCode(outcome.name()));
        } catch (RuntimeException ignored) {
            // 审计失败不掩盖守卫结果（与既有审计失败容忍语义一致）
        }
    }
}
