package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 06/05 — 数据查询 Agent 领域包装（agent-decision-layer 06；meta-agent-platform-impl 05
 * 收敛为门面薄包装）：领域守卫（PII 拒绝 → REJECTED / 歧义澄清 → NEEDS_INPUT，决策 05 不进
 * 平台链）+ 拒绝审计、输出 record 反序列化、失败码映射。模型路径经门面（mock），门面自身的
 * 注册表拒绝/审计/观测在 {@code AgentRuntimeFacadeTest} 覆盖。
 */
class DataQueryAgentServiceTest {

    private final AgentRuntimeFacade facade = mock(AgentRuntimeFacade.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private DataQueryAgentService service;

    @BeforeEach
    void setUp() {
        service = new DataQueryAgentService(facade, audits, mapper);
    }

    private static AgentRunResult successOutput() {
        ObjectNode output = new ObjectMapper().createObjectNode()
                .put("answer", "最近 7 天缺货的订单行共 3 行")
                .put("confidence", 0.95)
                .put("requires_human", false);
        output.putArray("sources").addObject()
                .put("tool", "list_procurement_tickets")
                .put("row_count", 3);
        output.putArray("clarification_needed");
        return AgentRunResult.success(output, "deepseek", "deepseek-chat", "data-query-v1")
                .withRunMetadata("run_q1", 15);
    }

    // ------------------------------------------------------------------
    // 领域守卫：NEEDS_INPUT / REJECTED（零模型调用，决策 05 不进平台链）
    // ------------------------------------------------------------------

    @Test
    void blankQuestionYieldsNeedsInputWithoutModelCall() {
        DataQueryRunResult result = service.answer("   ", null);

        assertThat(result.error()).isNull();
        assertThat(result.status()).isEqualTo("NEEDS_INPUT");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().clarification_needed()).isNotEmpty();
        verify(facade, never()).invoke(any(), any(), any());
        assertThat(lastGuardAuditBusinessCode()).isEqualTo("NEEDS_INPUT");
    }

    @Test
    void placeholderSkuQuestionYieldsNeedsInputAndPassesThreadId() {
        DataQueryRunResult result =
                service.answer("SKU-xxx 的进货价和零售价是多少", AgentRunContext.of("thread-6"));

        assertThat(result.status()).isEqualTo("NEEDS_INPUT");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().clarification_needed())
                .anySatisfy(reason -> assertThat(reason).contains("SKU"));
        assertThat(result.output().sources()).isEmpty();
        verify(facade, never()).invoke(any(), any(), any());

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent.data-query-agent.run");
        assertThat(auditField(command, "actorType")).isEqualTo(AuditActorType.AGENT);
        assertThat(auditRequestPayload(command).get("thread_id")).isEqualTo("thread-6");
    }

    @Test
    void ticketNoQuestionYieldsNeedsInputNotGuessing() {
        DataQueryRunResult result = service.answer("采购工单 P-123 还差多少数量", null);

        assertThat(result.status()).isEqualTo("NEEDS_INPUT");
        assertThat(result.output().clarification_needed())
                .anySatisfy(reason -> assertThat(reason).contains("ticket_id"));
    }

    @Test
    void ambiguousProviderQuestionYieldsNeedsInput() {
        DataQueryRunResult result = service.answer("某履约方本月共接收多少运单回执", null);

        assertThat(result.status()).isEqualTo("NEEDS_INPUT");
        assertThat(result.output().clarification_needed()).isNotEmpty();
    }

    @Test
    void piiQuestionIsRoutedToHumanAsRejectedWithoutModelCall() {
        DataQueryRunResult result = service.answer("查一下客户张三的收货地址", null);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().answer()).contains("人工");
        assertThat(result.output().clarification_needed()).isEmpty();
        verify(facade, never()).invoke(any(), any(), any());
        assertThat(lastGuardAuditBusinessCode()).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------
    // 模型路径：经门面（mock）→ 反序列化 → 失败码映射
    // ------------------------------------------------------------------

    @Test
    void modelPathDeserializesFacadeOutputIntoDomainRecord() {
        when(facade.invoke(org.mockito.ArgumentMatchers.eq(DataQueryAgentService.AGENT_SLUG), any(), any())).thenReturn(successOutput());

        DataQueryRunResult result = service.answer("最近 7 天有多少缺货的订单行", null);

        assertThat(result.error()).isNull();
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.runId()).isEqualTo("run_q1");
        assertThat(result.latencyMs()).isEqualTo(15);
        assertThat(result.output().requires_human()).isFalse();
        assertThat(result.output().answer()).contains("3");
        assertThat(result.output().sources()).hasSize(1);
    }

    @Test
    void facadeFailureCodeIsMappedThrough() {
        when(facade.invoke(org.mockito.ArgumentMatchers.eq(DataQueryAgentService.AGENT_SLUG), any(), any()))
                .thenReturn(AgentRunResult.failure(
                        "deepseek", "deepseek-chat", "data-query-v1",
                        AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED)
                        .withRunMetadata("run_x", 3));

        DataQueryRunResult result = service.answer("最近 7 天有多少缺货的订单行", null);

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.status()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.output()).isNull();
    }

    @Test
    void facadeRejectedCodeIsMappedThrough() {
        when(facade.invoke(org.mockito.ArgumentMatchers.eq(DataQueryAgentService.AGENT_SLUG), any(), any()))
                .thenReturn(AgentRunResult.rejected(
                        "deepseek", "deepseek-chat", "data-query-v1",
                        AgentFailureCode.AGENT_DISABLED)
                        .withRunMetadata("run_y", 0));

        DataQueryRunResult result = service.answer("最近 7 天有多少缺货的订单行", null);

        assertThat(result.error()).isEqualTo("AGENT_DISABLED");
        assertThat(result.output()).isNull();
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private AuditLogService.AuditCommand lastAuditCommand() {
        ArgumentCaptor<AuditLogService.AuditCommand> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(captor.capture());
        return captor.getValue();
    }

    private String lastGuardAuditBusinessCode() {
        return String.valueOf(auditField(lastAuditCommand(), "businessCode"));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> auditRequestPayload(AuditLogService.AuditCommand command) {
        return (java.util.Map<String, Object>) auditField(command, "requestPayload");
    }

    private static Object auditField(AuditLogService.AuditCommand command, String field) {
        try {
            java.lang.reflect.Field f = AuditLogService.AuditCommand.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(command);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取审计命令字段 " + field, ex);
        }
    }
}
