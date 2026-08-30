package cn.zimu.fulfillment.agent.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentInputFormat;
import cn.zimu.fulfillment.agent.AgentRegistry;
import cn.zimu.fulfillment.agent.AgentRegistryHolder;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntime;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.agent.AgentStatus;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpControlReadTools;
import cn.zimu.fulfillment.mcp.McpDomainReadTools;
import cn.zimu.fulfillment.mcp.McpReadTools;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import cn.zimu.fulfillment.mcp.McpWriteTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * meta-agent 对话结局的判定（agent-console 06）。
 *
 * <p>核心断言：**结局按数据库事实判定，不按模型自述**。模型经常一边提问一边宣称
 * 「已创建」，把这种输出当 SUCCESS，用户就会去找一个不存在的草稿。
 */
class MetaAgentConversationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AgentRuntimeFacade facade;
    private JdbcTemplate jdbc;
    private MetaAgentConversationService service;

    @BeforeEach
    void setUp() {
        facade = mock(AgentRuntimeFacade.class);
        jdbc = mock(JdbcTemplate.class);
        service = new MetaAgentConversationService(facade, jdbc);
        // 默认：库里没有草稿，也没有 active
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    }

    // ---------- 输入门禁：不合法输入不进入模型 ----------

    @Test
    void blankMessageIsRejectedBeforeSpendingATokenOnIt() {
        for (String bad : new String[] {null, "", "   ", "\n\t"}) {
            assertThatThrownBy(() -> service.converse(bad, "zimu-admin", null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void overlongMessageIsRejectedRatherThanTruncated() {
        String tooLong = "描".repeat(MetaAgentConversationService.MAX_MESSAGE_LENGTH + 1);
        assertThatThrownBy(() -> service.converse(tooLong, "zimu-admin", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请拆分描述");
    }

    // ---------- 结局判定：以库为准 ----------

    @Test
    void draftRowInTheDatabaseIsWhatMakesItASuccess() {
        givenModelOutput("{\"agent_slug\":\"weekly-report-agent\"}");
        givenDraftRow("weekly-report-agent", 1, false);

        MetaAgentOutcome outcome = service.converse("做个周报 Agent", "zimu-admin", null);

        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.SUCCESS);
        assertThat(outcome.agentSlug()).isEqualTo("weekly-report-agent");
        assertThat(outcome.draftVersion()).isEqualTo(1);
        assertThat(outcome.draftEnabled()).isFalse();
    }

    @Test
    void conversationBindsAndInvokesDraftToolWhenProtocolSurfaceExcludesWrites() {
        AtomicBoolean draftCreated = new AtomicBoolean(false);
        McpToolRegistry splitRegistry = splitRegistry(draftCreated);
        MetaAgentConversationService realConversation = realConversation(
                splitRegistry, draftCreatingRuntime());
        givenDraftAfterToolInvocation(draftCreated);

        MetaAgentOutcome outcome = realConversation.converse(
                "创建一个每周汇总库存的 Agent", "zimu-admin", "thread-198");

        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.SUCCESS);
        assertThat(outcome.agentSlug()).isEqualTo("conversation-created-agent");
        assertThat(draftCreated).isTrue();
        assertThat(splitRegistry.findProtocolTool("list_agent_tools")).isEmpty();
        assertThat(splitRegistry.findProtocolTool("create_agent_draft")).isEmpty();
        assertThat(splitRegistry.findProtocolTool("update_agent_draft")).isEmpty();
    }

    @Test
    void modelClaimingSuccessWithoutADraftRowIsNotASuccess() {
        // 模型输出看起来完整，但库里没有草稿行 —— 不能报 SUCCESS
        givenModelOutput("{\"agent_slug\":\"ghost-agent\",\"name\":\"我已经创建好了\"}");

        MetaAgentOutcome outcome = service.converse("做个 Agent", "zimu-admin", null);

        assertThat(outcome.outcome()).isNotEqualTo(MetaAgentOutcome.SUCCESS);
        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.REJECTED);
        // 拒绝理由必须可操作
        assertThat(outcome.rejectionReason()).contains("职责");
    }

    @Test
    void clarifyingQuestionsWithoutADraftAreNeedsInputNotFailure() {
        givenModelOutput(
                "{\"questions\":[\"这个 Agent 需要写权限吗？\",\"它该读哪些数据？\"]}");

        MetaAgentOutcome outcome = service.converse("做个 Agent", "zimu-admin", null);

        // NEEDS_INPUT 是正常一步；显示成失败会让人以为 Agent 坏了
        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.NEEDS_INPUT);
        assertThat(outcome.questions()).hasSize(2);
        assertThat(outcome.error()).isNull();
    }

    @Test
    void alternateQuestionFieldNameIsAlsoAccepted() {
        givenModelOutput("{\"clarifying_questions\":[\"slug 用哪个？\"]}");
        assertThat(service.converse("做个 Agent", "zimu-admin", null).outcome())
                .isEqualTo(MetaAgentOutcome.NEEDS_INPUT);
    }

    @Test
    void draftWinsOverQuestionsWhenBothAppear() {
        // 模型经常一边提问一边把草稿建了；库里有行就是 SUCCESS
        givenModelOutput(
                "{\"agent_slug\":\"weekly-report-agent\",\"questions\":[\"还要加什么工具？\"]}");
        givenDraftRow("weekly-report-agent", 2, false);

        MetaAgentOutcome outcome = service.converse("做个周报 Agent", "zimu-admin", null);
        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.SUCCESS);
        // 问题仍然带回去，不丢
        assertThat(outcome.questions()).hasSize(1);
    }

    // ---------- 失败与守卫 ----------

    @Test
    void piiGuardRejectionIsRejectedNotFailed() {
        when(facade.invoke(anyString(), anyString(), any()))
                .thenReturn(AgentRunResult.rejected("none", "none", "none", AgentFailureCode.PII_GUARDED));

        MetaAgentOutcome outcome = service.converse("客户张三 13800138000", "zimu-admin", null);

        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.REJECTED);
        assertThat(outcome.rejectionReason()).contains("隐私守卫");
        assertThat(outcome.error()).isEqualTo("PII_GUARDED");
    }

    @Test
    void modelFailureCarriesTheStableCode() {
        when(facade.invoke(anyString(), anyString(), any()))
                .thenReturn(AgentRunResult.failClosed(AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED));

        MetaAgentOutcome outcome = service.converse("做个 Agent", "zimu-admin", null);

        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.FAILED);
        assertThat(outcome.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
    }

    // ---------- 平台红线 ----------

    @Test
    void anActiveDefinitionAfterAMetaAgentRunBreaksTheRedLineAndMustBlowUp() {
        givenModelOutput("{\"agent_slug\":\"weekly-report-agent\"}");
        givenDraftRow("weekly-report-agent", 1, true);
        // 事后核验发现出现了 active 定义 —— 意味着有人绕过了「启用必须人工」
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        assertThatThrownBy(() -> service.converse("做个周报 Agent", "zimu-admin", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("平台红线");
    }

    @Test
    void draftEnabledFlagIsReportedButNeverMeansActivated() {
        // 草稿上的 enabled=true 只是草稿里的一个值；status 仍是 draft，没人确认过
        givenModelOutput("{\"agent_slug\":\"weekly-report-agent\"}");
        givenDraftRow("weekly-report-agent", 1, true);

        MetaAgentOutcome outcome = service.converse("做个周报 Agent", "zimu-admin", null);
        assertThat(outcome.outcome()).isEqualTo(MetaAgentOutcome.SUCCESS);
        assertThat(outcome.draftEnabled()).isTrue();
    }

    // ------------------------------------------------------------------

    private void givenModelOutput(String json) {
        try {
            JsonNode output = JSON.readTree(json);
            when(facade.invoke(anyString(), anyString(), any()))
                    .thenReturn(AgentRunResult.success(output, "deepseek", "deepseek-chat", "meta-agent-v1"));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void givenDraftRow(String slug, int version, boolean enabled) {
        when(jdbc.queryForList(anyString(), eq(slug)))
                .thenReturn(List.of(Map.of("version", version, "enabled", enabled, "status", "draft")));
    }

    private static McpToolRegistry splitRegistry(AtomicBoolean draftCreated) {
        McpReadTools reads = mock(McpReadTools.class);
        when(reads.tools()).thenReturn(List.of(
                tool("list_agent_tools", "control", true, ignored -> {}),
                tool("search_skus", "masterdata", true, ignored -> {})));
        McpWriteTools writes = mock(McpWriteTools.class);
        when(writes.tools()).thenReturn(List.of(
                tool("create_agent_draft", "write", false, ignored -> draftCreated.set(true)),
                tool("update_agent_draft", "write", false, ignored -> {})));
        McpDomainReadTools domains = mock(McpDomainReadTools.class);
        when(domains.tools()).thenReturn(List.of());
        McpControlReadTools control = mock(McpControlReadTools.class);
        when(control.tools()).thenReturn(List.of());
        return new McpToolRegistry(
                reads, writes, domains, control, null, null, "control,write", "masterdata");
    }

    private MetaAgentConversationService realConversation(
            McpToolRegistry registry, AgentRuntime runtime) {
        AgentRuntimeFacade realFacade = new AgentRuntimeFacade(
                new AgentRegistryHolder(new AgentRegistry(List.of(metaAgentDefinition()))),
                runtime,
                mock(AuditLogService.class),
                new AgentModelMetadataRegistry(),
                new AgentToolBindingFactory(
                        registry, new McpAgentIdentity("meta-agent-test"), JSON));
        return new MetaAgentConversationService(realFacade, jdbc);
    }

    private static AgentDefinition metaAgentDefinition() {
        return new AgentDefinition(
                "meta-agent",
                "元 Agent",
                "创建 Agent 草稿",
                "只创建草稿，不直接启用。",
                "meta-agent-v1",
                "app.agent",
                true,
                List.of("list_agent_tools", "create_agent_draft", "update_agent_draft"),
                1,
                AgentStatus.ACTIVE,
                "system",
                OffsetDateTime.now(),
                true,
                List.of(),
                null,
                AgentInputFormat.NATURAL_LANGUAGE);
    }

    private static AgentRuntime draftCreatingRuntime() {
        return request -> {
            assertThat(request.tools().specifications())
                    .extracting(spec -> spec.name())
                    .containsExactlyInAnyOrder(
                            "list_agent_tools", "create_agent_draft", "update_agent_draft");
            var create = request.tools().tools().entrySet().stream()
                    .filter(entry -> "create_agent_draft".equals(entry.getKey().name()))
                    .findFirst()
                    .orElseThrow();
            create.getValue().execute(
                    ToolExecutionRequest.builder()
                            .name("create_agent_draft")
                            .arguments("{}")
                            .build(),
                    null);
            return AgentRunResult.success(
                    JSON.createObjectNode().put("agent_slug", "conversation-created-agent"),
                    "stub",
                    "stub",
                    "meta-agent-v1");
        };
    }

    private void givenDraftAfterToolInvocation(AtomicBoolean draftCreated) {
        when(jdbc.queryForList(anyString(), eq("conversation-created-agent")))
                .thenAnswer(ignored -> draftCreated.get()
                        ? List.of(Map.of("version", 1, "enabled", false, "status", "draft"))
                        : List.of());
    }

    private static McpTool tool(
            String name,
            String module,
            boolean readOnly,
            java.util.function.Consumer<Map<String, Object>> invocation) {
        return new McpToolRegistry.SimpleTool(
                name,
                name,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, arguments) -> {
                    invocation.accept(arguments);
                    return JSON.createObjectNode().put("ok", true);
                },
                readOnly,
                module);
    }
}
