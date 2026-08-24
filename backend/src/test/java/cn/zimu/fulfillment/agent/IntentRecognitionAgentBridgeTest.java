package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 07 — 意图识别运行桥埋点（agent-decision-layer 07，T06 适配）：解释运行写入 Agent 观测
 * （Start 先 RUNNING → Finish 收口）、运行期才可知的 provider/intent 随 Finish 落
 * agent_runs 列（04 差异⑦，不再额外落 AGENT 审计）、allowlist 投影（未白名单 → none）、
 * 启停只影响观测视图（禁用时零写入）；观测失败隔离。纯单元测试：AgentObservability mock，
 * 底层解释器不参与。
 */
class IntentRecognitionAgentBridgeTest {

    private static final String THREAD_ID = "task-42";
    private static final long SUBMISSION_ID = 7;
    private static final String INPUT = "请帮我下一单";

    private final AgentObservability observability = mock(AgentObservability.class);
    private final AgentModelMetadataRegistry metadata = new AgentModelMetadataRegistry();

    private AgentDefinition enabledDefinition() {
        return AgentDefinition.ofActiveV1(
                "intent-recognition",
                "意图识别",
                "企业微信消息意图分类与分流",
                "你是意图识别 Agent。",
                "intent-recognition-v1",
                "app.message-interpreter",
                true,
                List.of());
    }

    private IntentRecognitionAgentBridge bridge(AgentDefinition... definitions) {
        return new IntentRecognitionAgentBridge(
                AgentSeedFixtures.holderOf(definitions), observability, metadata);
    }

    private static IntentRecognitionRunMetadata success() {
        return new IntentRecognitionRunMetadata(
                "test-provider", "test-model", "test-prompt-v1", "NON_BUSINESS", null);
    }

    @Test
    void startedRunEmitsRunningStartWithDigestAndBusinessEntity() {
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());

        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        assertThat(runId).startsWith("run_").hasSize(4 + 32);
        ArgumentCaptor<AgentObservability.Start> captor =
                ArgumentCaptor.forClass(AgentObservability.Start.class);
        verify(observability).runStarted(captor.capture());
        AgentObservability.Start start = captor.getValue();
        assertThat(start.runId()).isEqualTo(runId);
        assertThat(start.threadId()).isEqualTo(THREAD_ID);
        assertThat(start.agentSlug()).isEqualTo("intent-recognition");
        assertThat(start.promptVersion()).isEqualTo("intent-recognition-v1");
        assertThat(start.model()).isEqualTo("app.message-interpreter");
        assertThat(start.agentVersion()).isNull();
        assertThat(start.inputDigest()).isEqualTo(AgentPayloadRedactor.digest(INPUT));
        assertThat(start.inputDigest()).isNotEqualTo(INPUT);
        assertThat(start.businessEntityType()).isEqualTo("MESSAGE_SUBMISSION");
        assertThat(start.businessEntityId()).isEqualTo("7");
    }

    @Test
    void finishedRunClosesRunWithProjectedProviderAndIntent() {
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        bridge.runFinished(runId, success(), 12);

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        AgentObservability.Finish finish = finishCaptor.getValue();
        assertThat(finish.runId()).isEqualTo(runId);
        assertThat(finish.errorType()).isNull();
        assertThat(finish.latencyMs()).isEqualTo(12);
        // 未白名单的模型三元组投影为 none（allowlist 生效）；intent 归一化后直落列
        assertThat(finish.model()).isEqualTo("none");
        assertThat(finish.provider()).isEqualTo("none");
        assertThat(finish.promptVersion()).isEqualTo("none");
        assertThat(finish.intent()).isEqualTo("NON_BUSINESS");
    }

    @Test
    void allowlistedTripleIsProjectedIntoFinish() {
        metadata.setPublicMetadataAliases(List.of(alias("test-provider", "test-model", "test-prompt-v1")));
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        bridge.runFinished(runId, success(), 12);

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        AgentObservability.Finish finish = finishCaptor.getValue();
        assertThat(finish.model()).isEqualTo("test-model");
        assertThat(finish.provider()).isEqualTo("test-provider");
        assertThat(finish.promptVersion()).isEqualTo("test-prompt-v1");
        assertThat(finish.intent()).isEqualTo("NON_BUSINESS");
    }

    @Test
    void failedRunCarriesStableErrorCodeIntoFinish() {
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        bridge.runFinished(runId, IntentRecognitionRunMetadata.failed("MODEL_CALL_FAILED"), 3);

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        AgentObservability.Finish finish = finishCaptor.getValue();
        assertThat(finish.errorType()).isEqualTo("MODEL_CALL_FAILED");
        // 失败路径意图未知：intent 不落，provider/prompt_version 投影为 none
        assertThat(finish.intent()).isNull();
        assertThat(finish.provider()).isEqualTo("none");
        assertThat(finish.promptVersion()).isEqualTo("none");
    }

    @Test
    void disabledAgentWritesNoObservation() {
        AgentDefinition disabled = AgentDefinition.ofActiveV1(
                "intent-recognition", "意图识别", "d", "s", "v1", "app.message-interpreter", false,
                List.of());
        IntentRecognitionAgentBridge bridge = bridge(disabled);

        assertThat(bridge.isEnabled()).isFalse();
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        assertThat(runId).isNull();
        bridge.runFinished(runId, success(), 12);
        verify(observability, never()).runStarted(any());
        verify(observability, never()).runFinished(any());
    }

    @Test
    void unregisteredAgentIsFailClosedAndWritesNothing() {
        IntentRecognitionAgentBridge bridge = bridge();

        assertThat(bridge.isEnabled()).isFalse();
        assertThat(bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT)).isNull();
        verify(observability, never()).runStarted(any());
    }

    @Test
    void observabilityFailuresNeverMaskInterpretationRun() {
        IntentRecognitionAgentBridge bridge = new IntentRecognitionAgentBridge(
                AgentSeedFixtures.holderOf(enabledDefinition()), observability, metadata);
        doThrow(new IllegalStateException("db down")).when(observability).runStarted(any());
        doThrow(new IllegalStateException("db down")).when(observability).runFinished(any());

        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);
        assertThat(runId).isNotNull();
        bridge.runFinished(runId, success(), 12);
        // 无异常上抛即通过（失败隔离契约）
    }

    private static AgentModelMetadataRegistry.PublicMetadataAlias alias(
            String provider, String model, String promptVersion) {
        AgentModelMetadataRegistry.PublicMetadataAlias alias =
                new AgentModelMetadataRegistry.PublicMetadataAlias();
        alias.setProvider(provider);
        alias.setModel(model);
        alias.setPromptVersion(promptVersion);
        return alias;
    }
}
