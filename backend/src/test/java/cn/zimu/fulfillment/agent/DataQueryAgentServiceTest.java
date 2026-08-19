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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 06 — 数据查询 Agent 服务（agent-decision-layer 06）：确定性策略门（PII 转人工 /
 * 歧义澄清 / 空输入澄清）、未配置模型 fail-closed、审计记录（run_id / thread_id /
 * 白名单 / 工具调用序列）。底层模型接缝不触碰（本类所有路径在模型调用前返回）。
 */
class DataQueryAgentServiceTest {

    private final AuditLogService audits = mock(AuditLogService.class);
    private final AgentToolBindingFactory bindingFactory = mock(AgentToolBindingFactory.class);

    private DataQueryAgentService service;
    private AgentDefinition definition;

    @BeforeEach
    void setUp() {
        definition = AgentSeedFixtures.dataQueryDefinition();
        service = new DataQueryAgentService(
                AgentSeedFixtures.holderOf(definition),
                new AgentModelProperties(),
                bindingFactory,
                audits,
                new AgentModelMetadataRegistry(),
                new ObjectMapper());
    }

    private DataQueryAgentService serviceWithRegistry(AgentRegistry registry) {
        return new DataQueryAgentService(
                new AgentRegistryHolder(registry),
                new AgentModelProperties(),
                bindingFactory,
                audits,
                new AgentModelMetadataRegistry(),
                new ObjectMapper());
    }

    // ------------------------------------------------------------------
    // 歧义澄清路径
    // ------------------------------------------------------------------

    @Test
    void blankQuestionRequiresClarificationWithoutModelCall() {
        DataQueryRunResult result = service.answer("   ", null);

        assertThat(result.error()).isNull();
        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().clarification_needed()).isNotEmpty();
        assertThat(result.output().sources()).isEmpty();
        assertThat(result.toolCalls()).isEmpty();
        assertThat(result.output().answer()).contains("澄清");
    }

    @Test
    void placeholderSkuQuestionRequiresClarificationAndPassesThreadId() {
        DataQueryRunResult result =
                service.answer("SKU-xxx 的进货价和零售价是多少", AgentRunContext.of("thread-6"));

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().clarification_needed())
                .anySatisfy(reason -> assertThat(reason).contains("SKU"));
        assertThat(result.toolCalls()).isEmpty();

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditRequestPayload(command).get("thread_id")).isEqualTo("thread-6");
        assertThat(auditResponsePayload(command).get("tool_call_sequence"))
                .isEqualTo(List.of());
    }

    @Test
    void ticketNoQuestionRequiresClarificationNotGuessing() {
        DataQueryRunResult result = service.answer("采购工单 P-123 还差多少数量", null);

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.output().clarification_needed())
                .anySatisfy(reason -> assertThat(reason).contains("ticket_id"));
        assertThat(result.toolCalls()).isEmpty();
    }

    @Test
    void ambiguousProviderQuestionRequiresClarification() {
        DataQueryRunResult result = service.answer("某履约方本月共接收多少运单回执", null);

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.output().clarification_needed()).isNotEmpty();
        assertThat(result.toolCalls()).isEmpty();
        // 澄清路径同样留审计（工具调用序列为空）
        assertThat(auditResponsePayload(lastAuditCommand()).get("tool_call_sequence"))
                .isEqualTo(List.of());
    }

    // ------------------------------------------------------------------
    // PII 拒绝路径
    // ------------------------------------------------------------------

    @Test
    void piiQuestionIsRoutedToHumanWithoutModelCall() {
        DataQueryRunResult result = service.answer("查一下客户张三的收货地址", null);

        assertThat(result.status()).isEqualTo("PII_GUARDED");
        assertThat(result.output().requires_human()).isTrue();
        assertThat(result.output().answer()).contains("人工");
        assertThat(result.output().clarification_needed()).isEmpty();
        assertThat(result.toolCalls()).isEmpty();

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditResponsePayload(command).get("status")).isEqualTo("PII_GUARDED");
        assertThat(auditField(command, "businessCode")).isEqualTo("PII_GUARDED");
    }

    // ------------------------------------------------------------------
    // 注册表拒绝与 fail-closed
    // ------------------------------------------------------------------

    @Test
    void unknownAgentSlugFailsClosed() {
        DataQueryAgentService empty = serviceWithRegistry(new AgentRegistry(List.of()));

        DataQueryRunResult result = empty.answer("最近 7 天有多少缺货的订单行", null);

        assertThat(result.error()).isEqualTo("AGENT_NOT_FOUND");
        assertThat(result.output()).isNull();
        assertThat(result.status()).isEqualTo("AGENT_NOT_FOUND");
        assertThat(auditRequestPayload(lastAuditCommand()).get("agent_slug")).isEqualTo("unknown");
    }

    @Test
    void disabledAgentFailsClosed() {
        AgentDefinition disabled = AgentDefinition.ofActiveV1(
                definition.agentSlug(),
                definition.name(),
                definition.description(),
                definition.systemPrompt(),
                definition.promptVersion(),
                definition.modelRef(),
                false,
                definition.toolNames());
        DataQueryAgentService svc =
                serviceWithRegistry(new AgentRegistry(List.of(disabled)));

        DataQueryRunResult result = svc.answer("最近 7 天有多少缺货的订单行", null);

        assertThat(result.error()).isEqualTo("AGENT_DISABLED");
        assertThat(result.output()).isNull();
    }

    @Test
    void unconfiguredModelFailsClosedWithoutConnecting() {
        DataQueryRunResult result = service.answer("最近 7 天有多少缺货的订单行", null);

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.output()).isNull();
        assertThat(auditResponsePayload(lastAuditCommand()).get("status"))
                .isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        // 门面语义一致：失败路径模型三元组一律 none
        assertThat(auditResponsePayload(lastAuditCommand()).get("model")).isEqualTo("none");
    }

    // ------------------------------------------------------------------
    // 审计
    // ------------------------------------------------------------------

    @Test
    void auditRecordsRunIdWhitelistAndOperation() {
        service.answer("SKU-xxx 的进货价和零售价是多少", null);

        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "operation")).isEqualTo("agent.data-query-agent.run");
        assertThat(auditField(command, "actorType")).isEqualTo(AuditActorType.AGENT);
        assertThat(auditField(command, "operator")).isEqualTo("agent");

        Map<String, Object> request = auditRequestPayload(command);
        assertThat((String) request.get("run_id")).startsWith("run_").hasSize(4 + 32);
        assertThat(request.get("agent_slug")).isEqualTo("data-query-agent");
        assertThat(request.get("prompt_version")).isEqualTo("data-query-v1");
        assertThat(request.get("model_ref")).isEqualTo("app.agent");
        assertThat(request.get("tool_names"))
                .isEqualTo(AgentSeedFixtures.DATA_QUERY_TOOL_NAMES);
    }

    @Test
    void gatePathsProjectNoneModelMetadataIntoAudit() {
        service.answer("查一下客户张三的收货地址", null);

        Map<String, Object> response = auditResponsePayload(lastAuditCommand());
        assertThat(response.get("model")).isEqualTo("none");
        assertThat(response.get("provider")).isEqualTo("none");
        assertThat(response.get("prompt_version")).isEqualTo("none");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> auditRequestPayload(AuditLogService.AuditCommand command) {
        return (Map<String, Object>) auditField(command, "requestPayload");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> auditResponsePayload(AuditLogService.AuditCommand command) {
        return (Map<String, Object>) auditField(command, "responsePayload");
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
