package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Agent 运行时门面（agent-decision-layer 02）：带注册表语义的统一调用入口。
 *
 * <p>在 01 的 {@link AgentRuntime} 模型接缝之上做编排：经 {@link AgentRegistryHolder} 取当前
 * 注册表（DB 真源，确认/回滚后 reload 换实例即感知）按 slug 解析 {@link AgentDefinition}、
 * enabled 判定、每次运行生成唯一 run_id（沿用 trace_id 的 {@code "run_"+UUID-hex} 生成模式）、
 * 通过 {@link AuditLogService} 落 AGENT 审计（service=agent, operation=agent.{slug}.run），
 * 未启用/未注册的 Agent 显式拒绝且留审计，未配置模型（底层 fail-closed）同样拒绝并留审计。
 *
 * <p>接口取舍：01 的 {@code AgentRuntime.run(AgentTaskRequest)} 是纯模型接缝（不知道 slug 与
 * 注册表），保持零改动；注册表语义全部收敛在本门面，拒绝路径与审计路径因此可独立 mock 测试。
 * resume 一期与 invoke 行为一致（无状态会话），仅语义上表示延续已有 thread_id 会话。
 *
 * <p>工具执行（03 票）：每次运行按 {@code AgentDefinition.tool_names} 白名单从
 * {@link McpToolRegistry} 生成工具绑定（run_id 即工具调用 request/trace id），随
 * {@link AgentTaskRequest} 透传底层运行时；白名单之外的工具在 LangChain4j 侧不暴露。
 * 白名单引用未知工具属配置漂移，绑定抛 {@link IllegalArgumentException} 直接暴露。
 *
 * <p>可观测性（08 票）：每次运行以 run_id 为关联键落 {@code agent_run} 行（先 RUNNING
 * 后收口；input 只存 SHA-256 digest；拒绝路径同样留 FAILED 行供追责），并透传调用侧
 * business_entity_type/id（{@link AgentRunContext#withBusinessEntity}）供双向追溯；
 * 审计的 trace_id/request_id 与 run_id 同值，agent_run 与 AuditLog 由此双向关联。
 * provider 经 {@link AgentObservability} 接缝注入（默认 DB 实现），任何观测回调失败
 * 都 try/catch 隔离，不影响运行结果与审计。
 *
 * <p>运行期守卫（08 票）：模型调用前按平台默认链（{@link AgentGuard}，当前 [PII 拒绝]）
 * 对输入判定——豁免（{@code guard_exemptions}）之外命中 → outcome=REJECTED
 * （{@code PII_GUARDED}）转人工、不进模型，留审计与 FAILED 观测行；守卫故障按失败
 * 隔离跳过（行为约束，与 07 票权限互不替代）。
 */
public class AgentRuntimeFacade {

    private static final String DEFAULT_OPERATOR = "agent";

    private final AgentRegistryHolder holder;
    private final AgentRuntime runtime;
    private final AuditLogService audits;
    private final AgentModelMetadataRegistry metadata;
    private final AgentToolBindingFactory toolBindingFactory;
    private AgentObservability observability = AgentObservability.disabled();

    public AgentRuntimeFacade(
            AgentRegistryHolder holder,
            AgentRuntime runtime,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            AgentToolBindingFactory toolBindingFactory) {
        this.holder = holder;
        this.runtime = runtime;
        this.audits = audits;
        this.metadata = metadata;
        this.toolBindingFactory = toolBindingFactory;
    }

    /**
     * 注入可观测性 provider（08 票）：Spring 装配 Bean 时经 setter 注入（{@code
     * AgentRegistryConfiguration} 的 @Bean 工厂方法按 02 票签名构造，本类以可选 setter
     * 保持该装配零改动）；单元测试直接 new 时默认 no-op，行为与 08 票之前一致。
     */
    @Autowired
    public void setObservability(AgentObservability observability) {
        if (observability != null) {
            this.observability = observability;
        }
    }

    /**
     * 以注册表中的 Agent 定义运行一次（system prompt 取自 definition，输入为 userInput）。
     *
     * @return 模型运行结果；注册表拒绝（未注册/未启用）与底层失败均以
     *         {@link AgentRunResult} 携带稳定失败码返回，不抛异常。
     */
    public AgentRunResult invoke(String agentSlug, String userInput, AgentRunContext context) {
        AgentRunContext ctx = context == null ? AgentRunContext.empty() : context;
        String runId = newRunId();
        AgentDefinition definition = holder.current().bySlug(agentSlug);
        return invokeResolved(definition, userInput, ctx, runId);
    }

    /** Run exactly the version selected when the durable business task was queued. */
    public AgentRunResult invokePinned(
            String agentSlug,
            int expectedVersion,
            String userInput,
            AgentRunContext context) {
        return invokePinnedWithRunId(
                agentSlug, expectedVersion, newRunId(), userInput, context);
    }

    /**
     * Run a pinned definition under a caller-reserved run id so deterministic policy can be
     * installed before any model tool is bound. The id must use the platform-generated format.
     */
    public AgentRunResult invokePinnedWithRunId(
            String agentSlug,
            int expectedVersion,
            String runId,
            String userInput,
            AgentRunContext context) {
        if (runId == null || !runId.matches("run_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid reserved Agent run id");
        }
        AgentRunContext ctx = context == null ? AgentRunContext.empty() : context;
        AgentDefinition definition = holder.current().bySlug(agentSlug);
        if (definition != null && definition.version() != expectedVersion) {
            runStarted(ctx, runId, definition, userInput);
            return finalizeRun(
                    ctx,
                    runId,
                    definition,
                    AgentFailureCode.AGENT_VERSION_MISMATCH.name(),
                    0,
                    AgentRunResult.failClosed(AgentFailureCode.AGENT_VERSION_MISMATCH),
                    null);
        }
        return invokeResolved(definition, userInput, ctx, runId);
    }

    private AgentRunResult invokeResolved(
            AgentDefinition definition,
            String userInput,
            AgentRunContext ctx,
            String runId) {
        if (definition == null) {
            runStarted(ctx, runId, null, userInput);
            return finalizeRun(ctx, runId, null, AgentFailureCode.AGENT_NOT_FOUND.name(), 0,
                    AgentRunResult.failClosed(AgentFailureCode.AGENT_NOT_FOUND), null);
        }
        if (!definition.enabled()) {
            runStarted(ctx, runId, definition, userInput);
            return finalizeRun(ctx, runId, definition, AgentFailureCode.AGENT_DISABLED.name(), 0,
                    AgentRunResult.failClosed(AgentFailureCode.AGENT_DISABLED), null);
        }
        runStarted(ctx, runId, definition, userInput);
        long startedNanos = System.nanoTime();
        try {
            // 08 票运行期守卫（05 决策平台默认链 = [PII 拒绝]）：模型调用前判定，命中即短路
            AgentRunResult guarded = guardReject(definition, userInput);
            if (guarded != null) {
                long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
                return finalizeRun(ctx, runId, definition, guarded.error(), latencyMs, guarded, null);
            }
            AgentToolBinding binding = toolBindingFactory.bind(
                    runId, definition.toolNames(), definition.allowWrite());
            AgentRunResult result = runtime.run(
                    new AgentTaskRequest(definition.systemPrompt(), userInput, binding, definition));
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            // 04 决策：outcome 维度——失败（REJECTED/FAILED）才以失败码作状态，NEEDS_INPUT 不再是失败
            String status = result.error() == null ? result.outcome().name() : result.error();
            return finalizeRun(ctx, runId, definition, status, latencyMs, result, projectedModel(result));
        } catch (RuntimeException ex) {
            // 绑定漂移等配置错误与运行时意外异常：留 FAILED 观测行收口后原样上抛（不吞配置错误）
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            runFinished(runId, "AGENT_RUNTIME_EXCEPTION", latencyMs, null);
            throw ex;
        }
    }

    /**
     * 收口一次运行：统一审计（status 语义见调用方）+ 观测收口 + run_id/耗时富化。
     * 四条路径（未注册 / 未启用 / 守卫拒绝 / 正常运行）共用同一收口尾，避免重复。
     */
    private AgentRunResult finalizeRun(
            AgentRunContext ctx,
            String runId,
            AgentDefinition definition,
            String status,
            long latencyMs,
            AgentRunResult result,
            String projectedModel) {
        recordAudit(ctx, runId, definition, status, latencyMs, result);
        runFinished(runId, result.error(), latencyMs, projectedModel);
        // 控制面收口富化：回填 run_id 与实测耗时（供领域包装回填业务 run-result 观测字段）
        return result.withRunMetadata(runId, latencyMs);
    }

    /** 按 slug 取当前生效定义（定义驱动：输入形态/输出 schema 由调用方按定义约定路由）。 */
    public AgentDefinition definitionOf(String agentSlug) {
        return holder.current().bySlug(agentSlug);
    }

    /** 会话延续：一期无状态，语义等价于 {@link #invoke}，thread_id 照常透传进审计。 */
    public AgentRunResult resume(String agentSlug, String userInput, AgentRunContext context) {
        return invoke(agentSlug, userInput, context);
    }

    /** 每次运行唯一 run_id，沿用 trace_id 生成模式（UUID hex，无连字符）。 */
    public static String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "");
    }

    // ------------------------------------------------------------------
    // 可观测性（08 票）：失败隔离——观测回调失败不得影响运行结果与审计
    // ------------------------------------------------------------------

    private void runStarted(AgentRunContext ctx, String runId, AgentDefinition definition, String userInput) {
        try {
            observability.runStarted(new AgentObservability.Start(
                    runId,
                    ctx.threadId(),
                    definition == null ? "unknown" : definition.agentSlug(),
                    definition == null ? null : String.valueOf(definition.version()),
                    definition == null ? "none" : definition.promptVersion(),
                    definition == null ? "none" : definition.modelRef(),
                    AgentPayloadRedactor.digest(userInput),
                    ctx.businessEntityType(),
                    ctx.businessEntityId(),
                    null));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖运行（与既有审计失败容忍语义一致）
        }
    }

    private void runFinished(String runId, String errorType, long latencyMs, String projectedModel) {
        try {
            observability.runFinished(
                    AgentObservability.Finish.of(runId, errorType, latencyMs, projectedModel));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖运行（与既有审计失败容忍语义一致）
        }
    }

    /**
     * 08 票运行期守卫（05 决策平台默认链 = [PII 拒绝]）：模型调用前对输入判定，
     * 命中 → REJECTED（{@code PII_GUARDED}）转人工、不进模型；guard_exemptions 声明
     * 后跳过（默认空 = 守卫生效）。守卫判定是纯字符串扫描（不应抛异常），异常仍按
     * 失败隔离跳过守卫，不阻断既有 Agent 运行（与观测/审计失败容忍语义一致）。
     *
     * @return 命中返回拒绝结果；放行返回 null
     */
    private AgentRunResult guardReject(AgentDefinition definition, String userInput) {
        try {
            if (AgentGuard.exempt(definition, AgentGuardExemption.PII)) {
                return null;
            }
            if (!AgentGuard.piiProblems(userInput).isEmpty()) {
                return AgentRunResult.rejected("none", "none", "none", AgentFailureCode.PII_GUARDED);
            }
            return null;
        } catch (RuntimeException ignored) {
            // 守卫故障不得阻断 Agent 运行（失败隔离契约）
            return null;
        }
    }

    /** 服务端 allowlist 投影后的模型名（未命中投影为 none），null 保留 Start 时值。 */
    private String projectedModel(AgentRunResult result) {
        if (result == null) {
            return null;
        }
        return metadata.publicProjection(result.provider(), result.model(), result.promptVersion())
                .model();
    }

    private void recordAudit(
            AgentRunContext ctx,
            String runId,
            AgentDefinition definition,
            String status,
            long latencyMs,
            AgentRunResult result) {
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
                    .responsePayload(Map.of(
                            "status", status,
                            "provider", meta.provider(),
                            "model", meta.model(),
                            "prompt_version", promptVersion))
                    .businessCode(status)
                    .latencyMs((int) latencyMs));
        } catch (RuntimeException ignored) {
            // 审计失败不掩盖 Agent 运行结果与拒绝结果（与既有 MCP 写路径审计语义一致）
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
