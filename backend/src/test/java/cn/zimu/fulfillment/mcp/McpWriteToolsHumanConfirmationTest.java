package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentDraftService;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundService;
import cn.zimu.fulfillment.message.MessageSubmissionQueryService;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.order.OrderDraftService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code submit_jd_outbound} 的人类确认闸验收（2026-09-01 需求：防 Agent 误发）。
 *
 * <p>京东出库是真实动货的一步：缺参数拒、错值拒、只有用户亲输的「确认」二字放行。
 * 另钉两条边界——闸的判定发生在任何服务调用之前，且用户输入不进下游命令与审计明文
 * （审计只留 {@code human_confirmed=true}）。同 provider 的其他写工具（草稿成单、重解释等）
 * 保持零改动：它们只动系统内单据，不受闸。
 */
class McpWriteToolsHumanConfirmationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final MessageSubmissionService submissionService = mock(MessageSubmissionService.class);
    private final MessageSubmissionQueryService submissionQuery = mock(MessageSubmissionQueryService.class);
    private final OrderDraftService orderDraftService = mock(OrderDraftService.class);
    private final McpReviewRequestService reviewRequestService = mock(McpReviewRequestService.class);
    private final ShipmentJdOutboundService jdOutboundService = mock(ShipmentJdOutboundService.class);
    private final AgentDraftService agentDraftService = mock(AgentDraftService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private McpWriteTools provider;

    @BeforeEach
    void setUp() {
        provider = new McpWriteTools(
                idempotency,
                audits,
                submissionService,
                submissionQuery,
                orderDraftService,
                reviewRequestService,
                jdOutboundService,
                agentDraftService,
                mapper,
                transactionManager,
                entityManager);
    }

    // ------------------------------------------------------------------
    // Schema：必填 + 中文指令写死
    // ------------------------------------------------------------------

    @Test
    void jdOutboundSchemaDeclaresHumanConfirmationAsRequired() {
        JsonNode schema = toolByName("submit_jd_outbound").inputSchema();

        assertThat(schema.path("required").toString()).contains(McpHumanConfirmation.PARAMETER);
        assertThat(schema.path("properties").path(McpHumanConfirmation.PARAMETER).path("description").asText())
                .contains("人类确认闸")
                .contains("『确认』")
                .contains("不得代填");
    }

    /** 不受闸的工具保持原样：草稿成单只动系统内单据，schema 里不该冒出确认参数。 */
    @Test
    void unGatedWriteToolsKeepTheirSchemaUntouched() {
        for (String toolName : java.util.List.of(
                "confirm_order_draft",
                "reinterpret_submission",
                "submit_order_draft_suggestion",
                "submit_supplementary_material",
                "submit_review_request",
                "create_agent_draft",
                "update_agent_draft")) {
            JsonNode schema = toolByName(toolName).inputSchema();
            assertThat(schema.path("required").toString())
                    .as("%s 不受闸", toolName)
                    .doesNotContain(McpHumanConfirmation.PARAMETER);
            assertThat(schema.path("properties").has(McpHumanConfirmation.PARAMETER))
                    .as("%s 不受闸", toolName)
                    .isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 三例：缺参数拒 / 错值拒 / 正确放行
    // ------------------------------------------------------------------

    @Test
    void jdOutboundWithoutConfirmationIsRejectedBeforeAnySubmission() {
        assertThatThrownBy(() -> toolByName("submit_jd_outbound").invoke(
                        context(), Map.of("shipment_id", "31", "idempotency_key", "jd-outbound-0001")))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> {
                    BusinessException ex = (BusinessException) failure;
                    assertThat(ex.getBusinessCode()).isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
                    assertThat(ex.getHttpStatus()).isEqualTo(422);
                    assertThat(ex.getMessage()).contains("确认");
                });
        verify(jdOutboundService, never()).submit(anyLong(), any(), anyString(), any());
    }

    @Test
    void jdOutboundWithAnyValueOtherThanTheExactWordIsRejected() {
        for (String wrong : java.util.List.of("ok", "yes", "确认。", "确认了", "已确认", "true", " ")) {
            assertThatThrownBy(() -> toolByName("submit_jd_outbound").invoke(context(), Map.of(
                            "shipment_id", "31",
                            "idempotency_key", "jd-outbound-0002",
                            McpHumanConfirmation.PARAMETER, wrong)))
                    .as("值 %s 必须被拒", wrong)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
        }
        verify(jdOutboundService, never()).submit(anyLong(), any(), anyString(), any());
    }

    @Test
    void jdOutboundWithTheExactWordRunsTheExistingSubmissionPath() {
        when(jdOutboundService.submit(
                        org.mockito.ArgumentMatchers.eq(31L),
                        any(ShipmentJdOutboundCommand.class),
                        org.mockito.ArgumentMatchers.eq("jd-outbound-0003"),
                        any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(
                        Map.of("shipment_id", 31L, "jd_order_no", "JD202609010001"), 201));

        JsonNode result = toolByName("submit_jd_outbound").invoke(context(), Map.of(
                "shipment_id", "31",
                "idempotency_key", "jd-outbound-0003",
                McpHumanConfirmation.PARAMETER, "确认"));

        assertThat(result.path("jd_order_no").asText()).isEqualTo("JD202609010001");
        verify(jdOutboundService).submit(
                org.mockito.ArgumentMatchers.eq(31L),
                any(ShipmentJdOutboundCommand.class),
                org.mockito.ArgumentMatchers.eq("jd-outbound-0003"),
                any(CommandContext.class));
    }

    // ------------------------------------------------------------------
    // 载荷纪律：用户输入不进下游、不落审计明文
    // ------------------------------------------------------------------

    @Test
    void confirmationNeverReachesTheAuditPayloadInPlaintext() {
        when(jdOutboundService.submit(anyLong(), any(), anyString(), any()))
                .thenReturn(IdempotentResult.executed(Map.of("shipment_id", 31L), 201));

        toolByName("submit_jd_outbound").invoke(context(), Map.of(
                "shipment_id", "31",
                "idempotency_key", "jd-outbound-0004",
                McpHumanConfirmation.PARAMETER, "  确认  "));

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue())
                .extracting("requestPayload")
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .doesNotContainKey(McpHumanConfirmation.PARAMETER)
                // 审计只留「人类确认过」这一事实
                .containsEntry(McpHumanConfirmation.AUDIT_FIELD, true);
        assertThat(audit.getValue()).extracting("requestPayload").asString().doesNotContain("确认");
    }

    private McpTool toolByName(String name) {
        return provider.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static McpRequestContext context() {
        return new McpRequestContext("run_jd", "run_jd", "hermes");
    }
}
