package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.agent.AgentRegistry;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.agent.AgentTaskRequest;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.List;
import java.util.Map;

/**
 * 采购比价 Agent 的服务编排（agent-decision-layer 05）：按注册表定义执行一次采购比价。
 *
 * <p>与 02 票 {@link AgentRuntimeFacade} 同构：按 slug 解析 {@link AgentDefinition}、
 * enabled 判定、每次运行生成唯一 run_id、经 {@link AuditLogService} 落 AGENT 审计
 * （service=agent, operation=agent.procurement-price-agent.run），未启用/未注册显式拒绝
 * 且留审计，未配置模型 fail-closed 且留审计。输入为结构化 JSON
 * （{@link ProcurementPriceInput}），解析校验失败抛 {@code INVALID_PARAMETERS} 业务错误，
 * 不进入模型调用。
 *
 * <p>建议不落任何业务表：运行结果仅返回给调用方并随 AGENT 审计留痕（responsePayload 携带
 * 可复核事实摘要），完整工具调用序列可观测性由 08 票承接。
 */
public class ProcurementPriceAgent {

    /** 注册表 slug，与 {@code ProcurementPriceAgentConfiguration} 声明一致。 */
    public static final String AGENT_SLUG = "procurement-price-agent";

    private static final String DEFAULT_OPERATOR = "agent";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final AgentRegistry registry;
    private final ProcurementPriceRuntime runtime;
    private final AuditLogService audits;
    private final AgentModelMetadataRegistry metadata;
    private final AgentToolBindingFactory toolBindingFactory;

    public ProcurementPriceAgent(
            AgentRegistry registry,
            ProcurementPriceRuntime runtime,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            AgentToolBindingFactory toolBindingFactory) {
        this.registry = registry;
        this.runtime = runtime;
        this.audits = audits;
        this.metadata = metadata;
        this.toolBindingFactory = toolBindingFactory;
    }

    /**
     * 执行一次采购比价：输入为结构化 JSON（procurement_ticket_id 或 sku_id + 可选 quantity）。
     *
     * @return 模型运行结果；注册表拒绝（未注册/未启用）与底层失败均以
     *         {@link ProcurementPriceRunResult} 携带稳定失败码返回，不抛异常；
     *         输入不合法抛 {@code INVALID_PARAMETERS} 业务错误。
     */
    public ProcurementPriceRunResult compare(String jsonInput, AgentRunContext context) {
        ProcurementPriceInput input = ProcurementPriceInput.parse(jsonInput);
        AgentRunContext ctx = context == null ? AgentRunContext.empty() : context;
        String runId = AgentRuntimeFacade.newRunId();
        AgentDefinition definition = registry.bySlug(AGENT_SLUG);
        if (definition == null) {
            recordAudit(ctx, runId, null, AgentFailureCode.AGENT_NOT_FOUND.name(), 0, null);
            return ProcurementPriceRunResult.failClosed(AgentFailureCode.AGENT_NOT_FOUND);
        }
        if (!definition.enabled()) {
            recordAudit(ctx, runId, definition, AgentFailureCode.AGENT_DISABLED.name(), 0, null);
            return ProcurementPriceRunResult.failClosed(AgentFailureCode.AGENT_DISABLED);
        }
        long startedNanos = System.nanoTime();
        AgentToolBinding binding = toolBindingFactory.bind(runId, definition.toolNames());
        ProcurementPriceRunResult result =
                runtime.run(new AgentTaskRequest(definition.systemPrompt(), input.toUserInput(), binding));
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
        String status = result.error() == null ? STATUS_SUCCESS : result.error();
        recordAudit(ctx, runId, definition, status, latencyMs, result);
        return result;
    }

    private void recordAudit(
            AgentRunContext ctx,
            String runId,
            AgentDefinition definition,
            String status,
            long latencyMs,
            ProcurementPriceRunResult result) {
        String slug = definition == null ? "unknown" : definition.agentSlug();
        String promptVersion = definition == null ? "none" : definition.promptVersion();
        AgentModelMetadataRegistry.PublicMetadata meta = result == null
                ? AgentModelMetadataRegistry.none()
                : metadata.publicProjection(result.provider(), result.model(), result.promptVersion());
        try {
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(runId)
                    .traceId(runId)
                    .operator(blankToDefault(ctx.operator(), DEFAULT_OPERATOR))
                    .actorType(AuditActorType.AGENT)
                    .service("agent")
                    .operation("agent." + slug + ".run")
                    .requestPayload(Map.of(
                            "agent_slug", slug,
                            "run_id", runId,
                            "thread_id", ctx.threadId(),
                            "prompt_version", promptVersion,
                            "model_ref", definition == null ? "none" : definition.modelRef(),
                            "tool_names", definition == null ? List.of() : definition.toolNames()))
                    .responsePayload(responsePayload(status, meta, promptVersion, result))
                    .businessCode(status)
                    .latencyMs((int) latencyMs));
        } catch (RuntimeException ignored) {
            // 审计失败不掩盖 Agent 运行结果与拒绝结果（与既有审计失败容忍语义一致）
        }
    }

    /** 建议只进审计与可观测记录：只携带可复核事实摘要，不含任何凭据/配置。 */
    private static Map<String, Object> responsePayload(
            String status,
            AgentModelMetadataRegistry.PublicMetadata meta,
            String promptVersion,
            ProcurementPriceRunResult result) {
        // 顶层与嵌套均可空值，一律用 LinkedHashMap 组装，避免 Map.of/Map.ofEntries
        // 对 null 的 NPE 在 recordAudit 的 try 内被吞掉而丢审计
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("provider", meta.provider());
        payload.put("model", meta.model());
        payload.put("prompt_version", promptVersion);
        Object summary = recommendationSummary(result);
        if (summary != null) {
            payload.put("recommendation_summary", summary);
        }
        return payload;
    }

    private static Object recommendationSummary(ProcurementPriceRunResult result) {
        if (result == null || result.recommendation() == null) {
            return null;
        }
        ProcurementPriceRecommendation recommendation = result.recommendation();
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("target_sku", recommendation.targetSku());
        summary.put("requested_quantity", recommendation.requestedQuantity());
        summary.put("confidence", recommendation.confidence());
        summary.put("requires_human", recommendation.requiresHuman());
        summary.put("missing_fields", recommendation.missingFields());
        summary.put("candidates", recommendation.candidates().stream()
                .map(candidate -> candidate == null ? null : candidateSummary(candidate))
                .toList());
        if (recommendation.recommendation() != null) {
            summary.put("recommendation", recommendationSummary(recommendation.recommendation()));
        }
        return summary;
    }

    /** 候选摘要：任何字段（provider_code/price/price_basis）为 null 时审计照常落库。 */
    private static Map<String, Object> candidateSummary(ProcurementPriceRecommendation.Candidate candidate) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("provider_code", candidate.providerCode());
        entry.put("price", candidate.price());
        entry.put("price_basis", candidate.priceBasis() == null ? null : candidate.priceBasis().name());
        return entry;
    }

    /** 推荐摘要：provider_code/reason 可空，null 安全组装。 */
    private static Map<String, Object> recommendationSummary(ProcurementPriceRecommendation.Recommendation recommendation) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("provider_code", recommendation.providerCode());
        entry.put("reason", recommendation.reason());
        return entry;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
