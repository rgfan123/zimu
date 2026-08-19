package cn.zimu.fulfillment.agent;

import org.springframework.stereotype.Component;

/**
 * 意图识别运行桥（agent-decision-layer 07，T06 适配）：解释任务执行时
 * （{@code InterpretationWorker} → {@code InterpretationService.interpret} 路径）把每次
 * 模型调用尝试写入 Agent 运行记录。
 *
 * <p>写入通道选用 08 票 {@link AgentObservability} 接缝（默认 DB 实现
 * {@link JdbcAgentObservability} 落 {@code app.agent_runs}）：run_id 沿用门面的生成模式，
 * thread_id=异步任务 id（重试同任务可聚组），business_entity=MESSAGE_SUBMISSION/submission_id
 * （与既有 {@code MessageInterpretation} 双向可追溯）；Start 先落 RUNNING，Finish 收口
 * status/error_type/latency，input 只存 SHA-256 digest。
 *
 * <p>04 差异⑦：agent_runs 已含 {@code intent}/{@code provider} 列（V33 迁移），运行期才可知的
 * provider/intent 随 Finish 事件落列，取代旧实现「每次运行额外落一条 AGENT 审计
 * （operation=agent.intent-recognition.run）补字段」的重复通道——该重复审计已删除。
 * provider/model/prompt_version 一律经 {@link AgentModelMetadataRegistry} 服务端 allowlist
 * 投影后才落库（未命中投影为 none），error_code 为 {@code InterpretationFailureCode} 稳定枚举。
 * run_id ↔ agent_runs ↔ 业务提交由此全向可查（intent/provider/error_code 均在 agent_runs
 * 行上直接可查，无需审计拼装）。
 *
 * <p>启停语义：以注册表 {@code enabled} 判定（fail-closed，未注册=未启用）；关闭时桥不写任何
 * 观测，既有消息管线照常执行（管线由 {@code app.message-interpreter.*} 配置驱动，不经
 * 本桥）。观测写入一律 try/catch 隔离，失败不得影响解释结果（与 08 票失败隔离契约一致）。
 */
@Component
public class IntentRecognitionAgentBridge {

    public static final String BUSINESS_ENTITY_TYPE = "MESSAGE_SUBMISSION";

    /** 注册表 slug（与 V33 种子 intent-recognition 一致）。 */
    public static final String AGENT_SLUG = "intent-recognition";

    private final AgentRegistryHolder holder;
    private final AgentObservability observability;
    private final AgentModelMetadataRegistry metadata;

    public IntentRecognitionAgentBridge(
            AgentRegistryHolder holder,
            AgentObservability observability,
            AgentModelMetadataRegistry metadata) {
        this.holder = holder;
        this.observability = observability;
        this.metadata = metadata;
    }

    /** 注册表可见性 + enabled 判定（fail-closed：未注册=未启用）。 */
    public boolean isEnabled() {
        return holder.current().isEnabled(AGENT_SLUG);
    }

    /**
     * 一次解释尝试开始（模型调用前）：落 RUNNING 观测行并生成 run_id。
     *
     * @return run_id；Agent 未注册/未启用时返回 null（无观测写入，不影响既有管线）
     */
    public String runStarted(String threadId, long submissionId, String inputContent) {
        AgentDefinition definition = holder.current().bySlug(AGENT_SLUG);
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
                    String.valueOf(submissionId),
                    null));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖解释运行（与 08 票失败隔离契约一致）
        }
        return runId;
    }

    /**
     * 一次解释尝试收口：把运行期才可知的 provider/intent 与稳定错误码随 Finish 落
     * agent_runs 的 intent/provider 列，实际运行的 prompt_version（allowlist 投影后）
     * 一并覆盖落列（04 差异⑦，完整替代旧重复审计通道的元数据），不再额外落 AGENT 审计。
     * provider/model/prompt_version 经 allowlist 投影后写入，未命中投影为 none。
     */
    public void runFinished(String runId, IntentRecognitionRunMetadata meta, long latencyMs) {
        if (runId == null) {
            return;
        }
        AgentModelMetadataRegistry.PublicMetadata projected =
                metadata.publicProjection(meta.provider(), meta.model(), meta.promptVersion());
        try {
            observability.runFinished(new AgentObservability.Finish(
                    runId,
                    meta.errorCode(),
                    latencyMs,
                    projected.model(),
                    projected.provider(),
                    meta.intent(),
                    projected.promptVersion()));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖解释运行（与 08 票失败隔离契约一致）
        }
    }
}
