package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.rawmaterial.RawMaterialReadException;
import cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcInboundOrder;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcReadGateway;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcStockRow;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcStockTransaction;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcWriteGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * rawmaterial 模块的 MCP 工具注册与协议面隔离验收。
 *
 * <p>钉死三件事：模块声明 4 读 + 4 写；协议面开 rawmaterial 时 tools/list 只见 4 个读工具、
 * 写工具连名字都不可见（tools/call 一律 Unknown tool）；写工具只在 Agent 面按模块开关可绑定。
 * 另覆盖读写失败到稳定 business_code 的翻译与写载荷构造。
 */
class McpRawMaterialToolsTest {

    private static final Set<String> READ_TOOLS = Set.of(
            "search_raw_material_stock", "search_finished_goods_stock",
            "list_raw_inbound_orders", "list_raw_stock_transactions");
    private static final Set<String> WRITE_TOOLS = Set.of(
            "create_raw_inbound_order",
            "approve_raw_inbound_order",
            "create_raw_scrap_order",
            "approve_raw_scrap_order");
    /** 受人类确认闸保护的两个工具：审批即入账/出账，货与账真实变动。 */
    private static final List<String> APPROVE_TOOLS =
            List.of("approve_raw_inbound_order", "approve_raw_scrap_order");

    private final ObjectMapper mapper = new ObjectMapper();
    private final YuanliaokcReadGateway reads = mock(YuanliaokcReadGateway.class);
    private final YuanliaokcWriteGateway writes = mock(YuanliaokcWriteGateway.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private McpRawMaterialTools provider;

    @BeforeEach
    void setUp() {
        provider = new McpRawMaterialTools(
                reads, writes, idempotency, audits, mapper, transactionManager);
    }

    // ------------------------------------------------------------------
    // 模块声明
    // ------------------------------------------------------------------

    @Test
    void declaresFourReadAndFourWriteToolsAllInTheRawmaterialModule() {
        List<McpTool> tools = provider.tools();
        assertThat(tools).hasSize(8);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.module()).isEqualTo(McpRawMaterialTools.MODULE);
            assertThat(tool.externallyDiscoverable()).isTrue();
        });
        assertThat(tools.stream().filter(McpTool::readOnly).map(McpTool::name))
                .containsExactlyInAnyOrderElementsOf(READ_TOOLS);
        assertThat(tools.stream().filter(tool -> !tool.readOnly()).map(McpTool::name))
                .containsExactlyInAnyOrderElementsOf(WRITE_TOOLS);
        // 审批即入账/出账是终局动作：描述必须向模型言明不可逆
        assertThat(toolByName("approve_raw_inbound_order").description()).contains("不可逆");
        assertThat(toolByName("approve_raw_scrap_order").description()).contains("不可逆");
    }

    // ------------------------------------------------------------------
    // 协议面隔离：写工具绝不出现在 tools/list
    // ------------------------------------------------------------------

    @Test
    void protocolSurfaceWithRawmaterialEnabledListsOnlyTheThreeReadTools() {
        McpToolRegistry registry = registry("rawmaterial", "rawmaterial");
        McpServer server = new McpServer(registry, new McpAgentIdentity("protocol-test"), mapper);

        JsonNode list = server.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = new ArrayList<>();
        list.path("result").path("tools").forEach(node -> names.add(node.path("name").asText()));
        assertThat(names).containsExactlyInAnyOrderElementsOf(READ_TOOLS);

        // 写工具在协议面投影为「不存在」，不泄露被隐藏工具的名称或受限原因
        for (String writeTool : WRITE_TOOLS) {
            JsonNode call = server.handleRequest(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"" + writeTool + "\",\"arguments\":{}}}");
            assertThat(call.path("error").path("code").asInt()).isEqualTo(-32602);
            assertThat(call.path("error").path("message").asText())
                    .isEqualTo("Unknown tool: " + writeTool)
                    .doesNotContain("read-only", "write", "restricted");
        }
    }

    @Test
    void rawmaterialToolsStayAbsentWhenModuleIsNotEnabledOnEitherSurface() {
        McpToolRegistry registry = registry("", "");
        assertThat(registry.agentTools()).isEmpty();
        assertThat(registry.protocolTools()).isEmpty();
        // 但模块进入已知全集：管理员核对视图能看见「已知未开放」
        assertThat(registry.knownModules()).contains(McpRawMaterialTools.MODULE);
    }

    @Test
    void agentSurfaceBindsWriteToolsOnlyByModuleSwitch() {
        McpToolRegistry registry = registry("rawmaterial", "");
        assertThat(registry.agentWriteToolNames()).containsExactlyInAnyOrderElementsOf(WRITE_TOOLS);
        for (String writeTool : WRITE_TOOLS) {
            assertThat(registry.findAgentTool(writeTool)).isPresent();
            assertThat(registry.findProtocolTool(writeTool)).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 读工具：白名单投影 + 稳定错误码
    // ------------------------------------------------------------------

    @Test
    void searchFinishedGoodsStockPinsCategoryAndDefaultsToInStockOnly() {
        // 成品与原料同住一套物料档案，靠 category=成品 分野；上游过滤，不在本地筛。
        when(reads.stock("肥牛", "成品", true)).thenReturn(List.of(new YuanliaokcStockRow(
                61L, "CP20260901001", "精选肥牛卷（300克）", "成品", "300g×30盒/箱", "kg", 380L,
                new BigDecimal("114.000"), new BigDecimal("114.000"), new BigDecimal("0.000"),
                2L, "2027-03-20", "normal")));

        JsonNode result = toolByName("search_finished_goods_stock")
                .invoke(context(), Map.of("keyword", "肥牛"));

        assertThat(result.path("source").asText()).isEqualTo("YUANLIAOKC");
        JsonNode item = result.path("items").path(0);
        assertThat(item.path("material_code").asText()).isEqualTo("CP20260901001");
        assertThat(item.path("category").asText()).isEqualTo("成品");
        assertThat(item.path("current_kg").asText()).isEqualTo("114");
        assertThat(item.path("piece_count").asLong()).isEqualTo(380L);
        assertThat(item.path("earliest_expiry").asText()).isEqualTo("2027-03-20");
    }

    @Test
    void searchFinishedGoodsStockCanIncludeSoldOutRows() {
        // 「还有没有货」的答案可能是 0——显式要全量时把 only_in_stock 交给上游关掉。
        when(reads.stock(null, "成品", false)).thenReturn(List.of());

        JsonNode result = toolByName("search_finished_goods_stock")
                .invoke(context(), Map.of("include_out_of_stock", "true"));

        assertThat(result.path("items").isArray()).isTrue();
        assertThat(result.path("items")).isEmpty();
    }

    @Test
    void searchStockProjectsWhitelistedFieldsWithDecimalStringKg() {
        when(reads.stock("黑猪")).thenReturn(List.of(new YuanliaokcStockRow(
                7L, "RM-007", "雷山黑猪前腿", "猪肉", "冻品", "kg", 12L,
                new BigDecimal("103.500"), new BigDecimal("90.25"), new BigDecimal("13.25"),
                3L, "2026-11-02", "normal")));

        JsonNode result = toolByName("search_raw_material_stock")
                .invoke(context(), Map.of("keyword", "黑猪"));

        assertThat(result.path("source").asText()).isEqualTo("YUANLIAOKC");
        JsonNode item = result.path("items").path(0);
        assertThat(item.path("material_code").asText()).isEqualTo("RM-007");
        // kg 一律 decimal-string（3 位刻度去尾零），浮点不入 JSON
        assertThat(item.path("current_kg").isTextual()).isTrue();
        assertThat(item.path("current_kg").asText()).isEqualTo("103.5");
        assertThat(item.path("piece_count").asLong()).isEqualTo(12L);
    }

    @Test
    void transactionsKeepTheNegativeSignOnOutboundChanges() {
        when(reads.stockTransactions(null, "scrap_out", 50, 0)).thenReturn(List.of(
                new YuanliaokcStockTransaction(
                        31L, 7L, "雷山黑猪前腿", 5L, "C0001", "scrap_out",
                        new BigDecimal("-2.5"), new BigDecimal("101.0"),
                        "scrap_order", 9L, "变质", 3L, "2026-08-31T10:00:00")));

        JsonNode result = toolByName("list_raw_stock_transactions")
                .invoke(context(), Map.of("transaction_type", "scrap_out"));

        JsonNode item = result.path("items").path(0);
        assertThat(item.path("quantity_change_kg").asText()).isEqualTo("-2.5");
        assertThat(item.path("quantity_after_kg").asText()).isEqualTo("101");
    }

    @Test
    void readFailureTranslatesToTheStableBusinessCode() {
        when(reads.inboundOrders(null)).thenThrow(new RawMaterialReadException(
                RawMaterialReadException.Code.RAW_MATERIAL_NOT_CONFIGURED, "未配置"));

        assertThatThrownBy(() -> toolByName("list_raw_inbound_orders").invoke(context(), Map.of()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo("RAW_MATERIAL_NOT_CONFIGURED");
                    assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(503);
                });
    }

    @Test
    void statusMustLookLikeAnUpstreamEnumBeforeItIsForwarded() {
        assertThatThrownBy(() -> toolByName("list_raw_inbound_orders")
                        .invoke(context(), Map.of("status", "DROP TABLE")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
    }

    // ------------------------------------------------------------------
    // 写工具：幂等包裹 + 审计 + 稳定错误码
    // ------------------------------------------------------------------

    @Test
    void approveInboundHappyPathRunsThroughIdempotencyAndAudits() {
        idempotencyPassthrough();
        when(writes.approveInboundOrder(11L)).thenReturn(inboundOrder("posted"));

        JsonNode result = toolByName("approve_raw_inbound_order").invoke(context(), Map.of(
                "order_id", "11",
                "idempotency_key", "raw-approve-0001",
                McpHumanConfirmation.PARAMETER, "确认"));

        assertThat(result.path("order_no").asText()).isEqualTo("RK20260831001");
        assertThat(result.path("status").asText()).isEqualTo("posted");
        assertThat(result.path("lines").path(0).path("quantity_kg").asText()).isEqualTo("103.5");
        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("operation").isEqualTo("mcp.approve_raw_inbound_order");
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("RAW_INBOUND_ORDER_POSTED");
        assertThat(audit.getValue()).extracting("operator").isEqualTo("raw-agent");
    }

    @Test
    void createInboundBuildsTheUpstreamPayloadFromValidatedStringArguments() {
        idempotencyPassthrough();
        when(writes.createInboundOrder(any())).thenReturn(inboundOrder("pending_approval"));

        toolByName("create_raw_inbound_order").invoke(context(), Map.of(
                "warehouse_id", "1",
                "supplier_name", "雷山供应商",
                "lines", List.of(Map.of(
                        "material_id", "7",
                        "quantity_kg", "103.5",
                        "piece_count", "12",
                        "production_date", "2026-08-30")),
                "idempotency_key", "raw-inbound-0001"));

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(writes).createInboundOrder(payload.capture());
        JsonNode sent = payload.getValue();
        // InboundCreate 实际字段（warehouse_id 必填），quantity_kg 精确入 JSON number
        assertThat(sent.path("warehouse_id").asLong()).isEqualTo(1L);
        assertThat(sent.path("supplier_name").asText()).isEqualTo("雷山供应商");
        JsonNode line = sent.path("lines").path(0);
        assertThat(line.path("material_id").asLong()).isEqualTo(7L);
        assertThat(line.path("quantity_kg").isNumber()).isTrue();
        assertThat(line.path("quantity_kg").decimalValue()).isEqualByComparingTo(new BigDecimal("103.5"));
        assertThat(line.path("piece_count").asLong()).isEqualTo(12L);
        assertThat(line.path("production_date").asText()).isEqualTo("2026-08-30");
        assertThat(line.has("batch_no")).isFalse();
    }

    @Test
    void writeChannelDisabledSurfacesTheStableDisabledCode() {
        idempotencyPassthrough();
        when(writes.approveScrapOrder(9L)).thenThrow(new RawMaterialWriteException(
                RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_DISABLED, "未开写"));

        assertThatThrownBy(() -> toolByName("approve_raw_scrap_order").invoke(context(), Map.of(
                        "order_id", "9",
                        "idempotency_key", "raw-scrap-0001",
                        McpHumanConfirmation.PARAMETER, "确认")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo("RAW_MATERIAL_WRITE_DISABLED");
                    assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(503);
                });
    }

    @Test
    void upstreamRejectionKeepsTheDetailInTheUnprocessableError() {
        idempotencyPassthrough();
        when(writes.createScrapOrder(any())).thenThrow(new RawMaterialWriteException(
                RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_REJECTED,
                "上游拒绝了报废单创建（400）：可用库存不足"));

        assertThatThrownBy(() -> toolByName("create_raw_scrap_order").invoke(context(), Map.of(
                        "batch_id", "5",
                        "quantity_kg", "2.5",
                        "reason", "变质报废",
                        "idempotency_key", "raw-scrap-0002")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getBusinessCode())
                            .isEqualTo("RAW_MATERIAL_WRITE_REJECTED");
                    assertThat(e.getMessage()).contains("可用库存不足");
                });
    }

    @Test
    void writeToolsValidateArgumentsBeforeAnyGatewayCall() {
        // 缺幂等键（确认闸已过，错误仍归位到参数校验）
        assertThatThrownBy(() -> toolByName("approve_raw_inbound_order").invoke(context(), Map.of(
                        "order_id", "11", McpHumanConfirmation.PARAMETER, "确认")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // 数量必须是正小数字符串
        assertThatThrownBy(() -> toolByName("create_raw_scrap_order").invoke(context(), Map.of(
                        "batch_id", "5",
                        "quantity_kg", "0",
                        "reason", "变质报废",
                        "idempotency_key", "raw-scrap-0003")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // lines 必填
        assertThatThrownBy(() -> toolByName("create_raw_inbound_order").invoke(context(), Map.of(
                        "warehouse_id", "1", "idempotency_key", "raw-inbound-0002")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        verify(writes, org.mockito.Mockito.never()).createInboundOrder(any());
        verify(writes, org.mockito.Mockito.never()).approveInboundOrder(org.mockito.ArgumentMatchers.anyLong());
        verify(writes, org.mockito.Mockito.never()).createScrapOrder(any());
    }

    /**
     * 鉴权先于确认闸：入参连确认词都没带，返回的仍必须是鉴权错误——未认证调用不得
     * 从错误码里探知「这个工具背后还有一道确认闸」。
     */
    @Test
    void writeWithoutAgentIdentityFailsWithAuthErrorBeforeIdempotency() {
        assertThatThrownBy(() -> toolByName("approve_raw_inbound_order").invoke(
                        new McpRequestContext("run_x", "run_x", ""),
                        Map.of("order_id", "11", "idempotency_key", "raw-approve-0002")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("MCP_AUTH_REQUIRED");
        verify(idempotency, org.mockito.Mockito.never())
                .execute(anyString(), anyString(), any(), anyInt(), any());
    }

    // ------------------------------------------------------------------
    // 人类确认闸（2026-09-01 需求）：审批即入账/出账，必须有人亲口说「确认」
    // ------------------------------------------------------------------

    @Test
    void bothApproveToolsDeclareHumanConfirmationAsARequiredParameter() {
        for (String approveTool : APPROVE_TOOLS) {
            JsonNode schema = toolByName(approveTool).inputSchema();
            assertThat(schema.path("required").toString())
                    .as("%s 必填确认参数", approveTool)
                    .contains(McpHumanConfirmation.PARAMETER);
            assertThat(schema.path("properties").path(McpHumanConfirmation.PARAMETER)
                            .path("description").asText())
                    .contains("人类确认闸")
                    .contains("『确认』")
                    .contains("不得代填");
            assertThat(toolByName(approveTool).description()).contains("人类确认闸");
        }
        // 建单只落待审核单据、不动账，不受闸——闸只装在真正入账/出账那一步
        for (String createTool : List.of("create_raw_inbound_order", "create_raw_scrap_order")) {
            assertThat(toolByName(createTool).inputSchema().path("properties")
                            .has(McpHumanConfirmation.PARAMETER))
                    .as("%s 不受闸", createTool)
                    .isFalse();
        }
    }

    @Test
    void approveToolsWithoutConfirmationAreRejectedBeforeAnyGatewayCall() {
        for (String approveTool : APPROVE_TOOLS) {
            assertThatThrownBy(() -> toolByName(approveTool).invoke(
                            context(), Map.of("order_id", "11", "idempotency_key", "raw-gate-0001")))
                    .as("%s 缺确认参数必须拒", approveTool)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(failure -> {
                        BusinessException ex = (BusinessException) failure;
                        assertThat(ex.getBusinessCode()).isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
                        assertThat(ex.getHttpStatus()).isEqualTo(422);
                        assertThat(ex.getMessage()).contains("确认");
                    });
        }
        verify(writes, org.mockito.Mockito.never()).approveInboundOrder(org.mockito.ArgumentMatchers.anyLong());
        verify(writes, org.mockito.Mockito.never()).approveScrapOrder(org.mockito.ArgumentMatchers.anyLong());
        verify(idempotency, org.mockito.Mockito.never())
                .execute(anyString(), anyString(), any(), anyInt(), any());
    }

    @Test
    void approveToolsRejectEveryValueThatIsNotTheExactWord() {
        for (String approveTool : APPROVE_TOOLS) {
            for (String wrong : List.of("ok", "yes", "确认。", "确认了", "已确认", "同意", "")) {
                assertThatThrownBy(() -> toolByName(approveTool).invoke(context(), Map.of(
                                "order_id", "11",
                                "idempotency_key", "raw-gate-0002",
                                McpHumanConfirmation.PARAMETER, wrong)))
                        .as("%s 收到 %s 必须拒", approveTool, wrong)
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getBusinessCode())
                        .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
            }
        }
        verify(writes, org.mockito.Mockito.never()).approveInboundOrder(org.mockito.ArgumentMatchers.anyLong());
        verify(writes, org.mockito.Mockito.never()).approveScrapOrder(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * 用户输入不进幂等载荷：同一动作确认两次（一次「确认」、一次「 确认 」）必须产出
     * 逐字节相同的幂等载荷，否则第二次会被当成一个新请求。审计只留 confirmed=true。
     */
    @Test
    void confirmationNeverEntersTheIdempotencyPayloadAndIsNotAuditedInPlaintext() {
        idempotencyPassthrough();
        when(writes.approveInboundOrder(11L)).thenReturn(inboundOrder("posted"));

        toolByName("approve_raw_inbound_order").invoke(context(), Map.of(
                "order_id", "11",
                "idempotency_key", "raw-gate-0003",
                McpHumanConfirmation.PARAMETER, "  确认  "));

        ArgumentCaptor<Object> idempotencyPayload = ArgumentCaptor.forClass(Object.class);
        verify(idempotency).execute(
                anyString(), anyString(), idempotencyPayload.capture(), anyInt(), any());
        assertThat(idempotencyPayload.getValue())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsExactly(org.assertj.core.api.Assertions.entry("order_id", 11L));

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue())
                .extracting("requestPayload")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey(McpHumanConfirmation.PARAMETER)
                .containsEntry(McpHumanConfirmation.AUDIT_FIELD, true);
        assertThat(audit.getValue()).extracting("requestPayload").asString().doesNotContain("确认");
    }

    // ------------------------------------------------------------------
    // 装配助手
    // ------------------------------------------------------------------

    /** 幂等服务直通：执行 work 并按首次执行包装，幂等注册表本身的行为由其自有测试覆盖。 */
    private void idempotencyPassthrough() {
        when(idempotency.execute(anyString(), anyString(), any(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    IdempotencyService.Work<Object> work = invocation.getArgument(4);
                    return IdempotentResult.executed(work.execute(), 200);
                });
    }

    private McpTool toolByName(String name) {
        return provider.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static McpRequestContext context() {
        return new McpRequestContext("run_raw", "run_raw", "raw-agent");
    }

    private McpToolRegistry registry(String agentModules, String protocolModules) {
        McpReadTools readTools = mock(McpReadTools.class);
        when(readTools.tools()).thenReturn(List.of());
        McpWriteTools writeTools = mock(McpWriteTools.class);
        when(writeTools.tools()).thenReturn(List.of());
        McpDomainReadTools domainTools = mock(McpDomainReadTools.class);
        when(domainTools.tools()).thenReturn(List.of());
        McpControlReadTools controlTools = mock(McpControlReadTools.class);
        when(controlTools.tools()).thenReturn(List.of());
        return new McpToolRegistry(
                readTools,
                writeTools,
                domainTools,
                controlTools,
                null,
                null,
                null,
                provider,
                agentModules,
                protocolModules);
    }

    private static YuanliaokcInboundOrder inboundOrder(String status) {
        return new YuanliaokcInboundOrder(
                11L,
                "RK20260831001",
                "雷山供应商",
                1L,
                "冷库一号",
                status,
                null,
                "2026-08-31T09:00:00",
                List.of(new YuanliaokcInboundOrder.Line(
                        21L, 7L, "雷山黑猪前腿", null, null, 12L,
                        new BigDecimal("103.5"), "2026-08-30", null, null)));
    }

}
