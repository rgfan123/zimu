package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 意图识别运行桥（agent-decision-layer 07）：解释任务执行时（{@code InterpretationWorker} →
 * {@code InterpretationService.interpret} 路径）把每次模型调用尝试写入 Agent 运行记录。
 *
 * <p>写入通道选用 08 票 {@link AgentObservability} 接缝（默认 DB 实现
 * {@link JdbcAgentObservability} 落 {@code app.agent_runs}），不做任何新表/新字段：run_id
 * 沿用门面的生成模式，thread_id=异步任务 id（重试同任务可聚组），business_entity=
 * MESSAGE_SUBMISSION/submission_id（与既有 {@code MessageInterpretation} 双向可追溯）；
 * Start 先落 RUNNING，Finish 收口 status/error_type/latency，input 只存 SHA-256 digest。
 *
 * <p>agent_runs 表没有 provider/intent 列（08 票 schema 未含），故每次运行额外落一条 AGENT
 * 审计（service=agent, operation=agent.intent-recognition.run，trace_id/request_id=run_id）：
 * provider/model/prompt_version/intent/error_code 经 allowlist 投影后随审计 responsePayload
 * 可查，run_id ↔ 审计 ↔ agent_runs ↔ 业务提交 由此全向关联。
 *
 * <p>启停语义：以注册表 {@code enabled} 判定（fail-closed，未注册=未启用）；关闭时桥不写任何
 * 观测/审计，既有消息管线照常执行（管线由 {@code app.message-interpreter.*} 配置驱动，不经
 * 本桥）。观测/审计写入一律 try/catch 隔离，失败不得影响解释结果（与 08 票失败隔离契约一致）。
 */
@Component
public class IntentRecognitionAgentBridge {

    public static final String BUSINESS_ENTITY_TYPE = "MESSAGE_SUBMISSION";

    private static final String AUDIT_OPERATOR = "message-worker";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final AgentRegistry registry;
    private final AgentObservability observability;
    private final AuditLogService audits;
    private final AgentModelMetadataRegistry metadata;

    public IntentRecognitionAgentBridge(
            AgentRegistry registry,
            AgentObservability observability,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata) {
        this.registry = registry;
        this.observability = observability;
        this.audits = audits;
        this.metadata = metadata;
    }

    /** 注册表可见性 + enabled 判定（fail-closed：未注册=未启用）。 */
    public boolean isEnabled() {
        return registry.isEnabled(IntentRecognitionAgentConfiguration.AGENT_SLUG);
    }

    /**
     * 一次解释尝试开始（模型调用前）：落 RUNNING 观测行并生成 run_id。
     *
     * @return run_id；Agent 未注册/未启用时返回 null（无观测写入，不影响既有管线）
     */
    public String runStarted(String threadId, long submissionId, String inputContent) {
        AgentDefinition definition = registry.bySlug(IntentRecognitionAgentConfiguration.AGENT_SLUG);
        if (definition == null || !definition.enabled()) {
            return null;
        }
        String runId = AgentRuntimeFacade.newRunId();
        try {
            observability.runStarted(new AgentObservability.Start(
                    runId,
                    threadId,
                    definition.agentSlug(),
                    null,
                    definition.promptVersion(),
                    definition.modelRef(),
                    AgentPayloadRedactor.digest(inputContent),
                    BUSINESS_ENTITY_TYPE,
                    String.valueOf(submissionId)));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖解释运行（与 08 票失败隔离契约一致）
        }
        return runId;
    }

    /** 一次解释尝试收口：先落 AGENT 审计（run_id 双向关联），再收口观测行。 */
    public void runFinished(
            String runId,
            String threadId,
            long submissionId,
            IntentRecognitionRunMetadata run,
            long latencyMs) {
        if (runId == null) {
            return;
        }
        recordAudit(runId, threadId, run, latencyMs);
        try {
            observability.runFinished(new AgentObservability.Finish(
                    runId,
                    run.errorCode(),
                    latencyMs,
                    projectedModel(run)));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖解释运行（与 08 票失败隔离契约一致）
        }
    }

    private String projectedModel(IntentRecognitionRunMetadata run) {
        return metadata.publicProjection(run.provider(), run.model(), run.promptVersion()).model();
    }

    private void recordAudit(
            String runId, String threadId, IntentRecognitionRunMetadata run, long latencyMs) {
        AgentDefinition definition = registry.bySlug(IntentRecognitionAgentConfiguration.AGENT_SLUG);
        String slug = definition == null ? IntentRecognitionAgentConfiguration.AGENT_SLUG : definition.agentSlug();
        String promptVersion = definition == null ? "none" : definition.promptVersion();
        AgentModelMetadataRegistry.PublicMetadata meta =
                metadata.publicProjection(run.provider(), run.model(), run.promptVersion());
        String status = run.errorCode() == null ? STATUS_SUCCESS : run.errorCode();
        Map<String, Object> responsePayload = new LinkedHashMap<>();
        responsePayload.put("status", status);
        responsePayload.put("provider", meta.provider());
        responsePayload.put("model", meta.model());
        responsePayload.put("prompt_version", meta.promptVersion());
        responsePayload.put("intent", run.intent());
        if (run.errorCode() != null) {
            responsePayload.put("error_code", run.errorCode());
        }
        try {
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(runId)
                    .traceId(runId)
                    .operator(AUDIT_OPERATOR)
                    .actorType(AuditActorType.AGENT)
                    .service("agent")
                    .operation("agent." + slug + ".run")
                    .requestPayload(Map.of(
                            "agent_slug", slug,
                            "run_id", runId,
                            "thread_id", threadId,
                            "prompt_version", promptVersion,
                            "model_ref", definition == null ? "none" : definition.modelRef(),
                            "tool_names", definition == null ? List.of() : definition.toolNames()))
                    .responsePayload(responsePayload)
                    .businessCode(status)
                    .latencyMs((int) latencyMs));
        } catch (RuntimeException ignored) {
            // 审计失败不掩盖解释运行（与既有审计失败容忍语义一致）
        }
    }
}
