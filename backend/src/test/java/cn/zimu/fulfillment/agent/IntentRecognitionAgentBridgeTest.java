package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 07 — 意图识别运行桥埋点（agent-decision-layer 07）：解释运行写入 Agent 观测
 * （Start 先 RUNNING → Finish 收口）、run_id 与审计 trace_id/request_id 同值、
 * provider/model/prompt_version/intent/error_code 随审计可关联、allowlist 投影
 * （未白名单 → none）、启停只影响观测视图（禁用时零写入）；观测/审计失败隔离。
 * 纯单元测试：AgentObservability mock、AuditLogService 走真实记录映射（仓库 mock），
 * 底层解释器不参与。
 */
class IntentRecognitionAgentBridgeTest {

    private static final String THREAD_ID = "task-42";
    private static final long SUBMISSION_ID = 7;
    private static final String INPUT = "请帮我下一单";

    private final AgentObservability observability = mock(AgentObservability.class);
    private final AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    private final AuditLogService audits =
            new AuditLogService(auditLogs, new ObjectMapper(), mock(EntityManager.class));
    private final AgentModelMetadataRegistry metadata = new AgentModelMetadataRegistry();

    private AgentDefinition enabledDefinition() {
        return AgentDefinition.of(
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
                new AgentRegistryHolder(new AgentRegistry(List.of(definitions))), observability, audits, metadata);
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
    void finishedRunClosesRunAndAuditsFullMetadataUnderRunId() {
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        bridge.runFinished(runId, THREAD_ID, SUBMISSION_ID, success(), 12);

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        AgentObservability.Finish finish = finishCaptor.getValue();
        assertThat(finish.runId()).isEqualTo(runId);
        assertThat(finish.errorType()).isNull();
        assertThat(finish.latencyMs()).isEqualTo(12);
        // 未白名单的模型三元组投影为 none（allowlist 生效）
        assertThat(finish.model()).isEqualTo("none");

        AuditLog audit = capturedAudit();
        assertThat(audit.getRequestId()).isEqualTo(runId);
        assertThat(audit.getTraceId()).isEqualTo(runId);
        assertThat(audit.getOperation()).isEqualTo("agent.intent-recognition.run");
        assertThat(audit.getBusinessCode()).isEqualTo("SUCCESS");
        assertThat(audit.getActorType()).isEqualTo(cn.zimu.fulfillment.common.audit.AuditActorType.AGENT);
        assertThat(audit.getRequestPayload().get("agent_slug")).isEqualTo("intent-recognition");
        assertThat(audit.getRequestPayload().get("thread_id")).isEqualTo(THREAD_ID);
        Map<String, Object> response = audit.getResponsePayload();
        assertThat(response.get("intent")).isEqualTo("NON_BUSINESS");
        assertThat(response.get("provider")).isEqualTo("none");
        assertThat(response.get("model")).isEqualTo("none");
        assertThat(response.get("prompt_version")).isEqualTo("none");
        assertThat(response).doesNotContainKey("error_code");
    }

    @Test
    void allowlistedTripleIsProjectedForObservationAndAudit() {
        metadata.setPublicMetadataAliases(List.of(alias("test-provider", "test-model", "test-prompt-v1")));
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        bridge.runFinished(runId, THREAD_ID, SUBMISSION_ID, success(), 12);

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        assertThat(finishCaptor.getValue().model()).isEqualTo("test-model");
        Map<String, Object> response = capturedAudit().getResponsePayload();
        assertThat(response.get("provider")).isEqualTo("test-provider");
        assertThat(response.get("model")).isEqualTo("test-model");
        assertThat(response.get("prompt_version")).isEqualTo("test-prompt-v1");
    }

    @Test
    void failedRunCarriesStableErrorCodeIntoRunAndAudit() {
        IntentRecognitionAgentBridge bridge = bridge(enabledDefinition());
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        bridge.runFinished(runId, THREAD_ID, SUBMISSION_ID,
                IntentRecognitionRunMetadata.failed("MODEL_CALL_FAILED"), 3);

        ArgumentCaptor<AgentObservability.Finish> finishCaptor =
                ArgumentCaptor.forClass(AgentObservability.Finish.class);
        verify(observability).runFinished(finishCaptor.capture());
        assertThat(finishCaptor.getValue().errorType()).isEqualTo("MODEL_CALL_FAILED");
        Map<String, Object> response = capturedAudit().getResponsePayload();
        assertThat(response.get("status")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(response.get("error_code")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(response.get("intent")).isNull();
    }

    @Test
    void disabledAgentWritesNoObservationAndNoAudit() {
        AgentDefinition disabled = AgentDefinition.of(
                "intent-recognition", "意图识别", "d", "s", "v1", "app.message-interpreter", false,
                List.of());
        IntentRecognitionAgentBridge bridge = bridge(disabled);

        assertThat(bridge.isEnabled()).isFalse();
        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);

        assertThat(runId).isNull();
        bridge.runFinished(runId, THREAD_ID, SUBMISSION_ID, success(), 12);
        verify(observability, never()).runStarted(any());
        verify(observability, never()).runFinished(any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    void unregisteredAgentIsFailClosedAndWritesNothing() {
        IntentRecognitionAgentBridge bridge = bridge();

        assertThat(bridge.isEnabled()).isFalse();
        assertThat(bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT)).isNull();
        verify(observability, never()).runStarted(any());
        verify(auditLogs, never()).save(any());
    }

    @Test
    void observabilityAndAuditFailuresNeverMaskInterpretationRun() {
        AuditLogService failingAudits = mock(AuditLogService.class);
        IntentRecognitionAgentBridge bridge = new IntentRecognitionAgentBridge(
                new AgentRegistryHolder(new AgentRegistry(List.of(enabledDefinition()))), observability, failingAudits, metadata);
        doThrow(new IllegalStateException("db down")).when(observability).runStarted(any());
        doThrow(new IllegalStateException("db down")).when(observability).runFinished(any());
        when(failingAudits.record(any())).thenThrow(new IllegalStateException("db down"));

        String runId = bridge.runStarted(THREAD_ID, SUBMISSION_ID, INPUT);
        assertThat(runId).isNotNull();
        bridge.runFinished(runId, THREAD_ID, SUBMISSION_ID, success(), 12);
        // 无异常上抛即通过（失败隔离契约）
    }

    private AuditLog capturedAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs).save(captor.capture());
        return captor.getValue();
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
