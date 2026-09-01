package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.PlatformOrderRefreshService;
import cn.zimu.fulfillment.file.OrderFulfillmentRoutingService;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCancelService;
import cn.zimu.fulfillment.order.ManualOrderCreateService;
import cn.zimu.fulfillment.order.ManualOrderCreateWrite;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 履约域四个 MCP 写工具（hermes 触发平台拉取 / 手工建单 / 履约路由 / 京东出库取消）
 * 的注册与行为验收。
 *
 * <p>每个工具钉四类：成功路径、幂等重放、参数校验先于任何服务调用、协议面投影为「不存在」。
 * 另钉住 refresh 的幂等取舍——真实外呼不可重放，只做幂等键格式校验、不入幂等注册表
 * （与 REST 面 A1 取舍同一判据），手工单出参只投影四个字段（不回吐收货人 PII），
 * 以及 cancel_jd_outbound 的人类确认闸（真实动货的逆操作，与 submit_jd_outbound 同级）。
 */
class McpFulfillmentWriteToolsTest {

    private static final Set<String> TOOL_NAMES = Set.of(
            "refresh_platform_orders",
            "create_manual_order",
            "route_order_fulfillment",
            "cancel_jd_outbound");

    /** 生产 ObjectMapper 是全局 SNAKE_CASE（JacksonConfig），投影读的就是这套键名。 */
    private final ObjectMapper mapper =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final PlatformOrderRefreshService refreshService = mock(PlatformOrderRefreshService.class);
    private final ManualOrderCreateService manualOrderService = mock(ManualOrderCreateService.class);
    private final OrderFulfillmentRoutingService routingService = mock(OrderFulfillmentRoutingService.class);
    private final ShipmentJdOutboundCancelService cancelService =
            mock(ShipmentJdOutboundCancelService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private McpFulfillmentWriteTools provider;

    @BeforeEach
    void setUp() {
        provider = new McpFulfillmentWriteTools(
                refreshService, manualOrderService, routingService, cancelService, idempotency, audits,
                mapper, transactionManager);
    }

    // ------------------------------------------------------------------
    // 模块声明
    // ------------------------------------------------------------------

    @Test
    void declaresFourWriteToolsInTheWriteModule() {
        List<McpTool> tools = provider.tools();

        assertThat(tools).hasSize(4);
        assertThat(tools.stream().map(McpTool::name)).containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.module()).isEqualTo(McpFulfillmentWriteTools.MODULE);
            assertThat(tool.readOnly()).isFalse();
            assertThat(tool.externallyDiscoverable()).isTrue();
            assertThat(tool.inputSchema().path("required").toString()).contains("idempotency_key");
        });
        // 真实外呼必须写进描述：模型不能把它当成一次本地查询来试探
        assertThat(toolByName("refresh_platform_orders").description())
                .contains("真实").contains("导入批次");
        // 手工单建成后不路由就没有发货单，这条因果必须在描述里
        assertThat(toolByName("route_order_fulfillment").description())
                .contains("发货单").contains("最新版本");
    }

    // ------------------------------------------------------------------
    // 协议面隔离：三个写工具在只读协议面一律「不存在」
    // ------------------------------------------------------------------

    @Test
    void protocolSurfaceProjectsAllWriteToolsAsNonexistent() {
        McpToolRegistry registry = registry("write", "write");
        McpServer server = new McpServer(registry, new McpAgentIdentity("protocol-test"), mapper);

        JsonNode list = server.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = new ArrayList<>();
        list.path("result").path("tools").forEach(node -> names.add(node.path("name").asText()));
        assertThat(names).doesNotContainAnyElementsOf(TOOL_NAMES);

        for (String writeTool : TOOL_NAMES) {
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
    void agentSurfaceBindsAllWriteToolsByModuleSwitch() {
        McpToolRegistry registry = registry("write", "");

        assertThat(registry.agentWriteToolNames()).containsAll(TOOL_NAMES);
        for (String writeTool : TOOL_NAMES) {
            assertThat(registry.findAgentTool(writeTool)).isPresent();
            assertThat(registry.findProtocolTool(writeTool)).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // refresh_platform_orders
    // ------------------------------------------------------------------

    @Test
    void refreshTriggersTheSingleChannelPullAndReturnsBatchFacts() {
        when(refreshService.refreshChannels(eq(List.of("CAISHIXIAN")), any(CommandContext.class)))
                .thenReturn(refreshResponse());

        JsonNode result = toolByName("refresh_platform_orders").invoke(
                context(), Map.of("channel", "caishixian", "idempotency_key", "hermes-refresh-0001"));

        JsonNode channel = result.path("channels").path(0);
        assertThat(channel.path("channel").asText()).isEqualTo("CAISHIXIAN");
        assertThat(channel.path("status").asText()).isEqualTo("OK");
        assertThat(channel.path("batch_no").asText()).isEqualTo("IB20260901001");
        assertThat(result.path("date_begin").asText()).isEqualTo("2026-08-03");

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("operation").isEqualTo("mcp.refresh_platform_orders");
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("PLATFORM_ORDERS_REFRESHED");
        assertThat(audit.getValue()).extracting("operator").isEqualTo("hermes");
    }

    /**
     * refresh 的幂等取舍（与 REST 面 A1 同一判据）：真实外呼不可重放，幂等键只做格式校验，
     * 不占用幂等注册表——注册表重放会返回一个早已被消费掉的旧批次，比重复拉取更坏。
     */
    @Test
    void refreshValidatesTheIdempotencyKeyFormatButNeverEntersTheIdempotencyRegistry() {
        when(refreshService.refreshChannels(any(), any(CommandContext.class))).thenReturn(refreshResponse());

        toolByName("refresh_platform_orders").invoke(
                context(), Map.of("channel", "FEIXIANG", "idempotency_key", "hermes-refresh-0002"));
        verify(idempotency, never()).execute(anyString(), anyString(), any(), anyInt(), any());

        // 短于 8 字符按写面统一口径拒绝
        assertThatThrownBy(() -> toolByName("refresh_platform_orders").invoke(
                        context(), Map.of("channel", "FEIXIANG", "idempotency_key", "short")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void refreshRejectsUnknownChannelsAndMissingArgumentsBeforeAnyPull() {
        assertThatThrownBy(() -> toolByName("refresh_platform_orders").invoke(
                        context(), Map.of("channel", "TAOBAO", "idempotency_key", "hermes-refresh-0003")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        assertThatThrownBy(() -> toolByName("refresh_platform_orders").invoke(
                        context(), Map.of("idempotency_key", "hermes-refresh-0004")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        verify(refreshService, never()).refreshChannels(any(), any());
    }

    @Test
    void refreshWithoutAgentIdentityIsRejectedBeforeAnyPull() {
        assertThatThrownBy(() -> toolByName("refresh_platform_orders").invoke(
                        new McpRequestContext("run_x", "run_x", ""),
                        Map.of("channel", "JUFUBAO", "idempotency_key", "hermes-refresh-0005")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("MCP_AUTH_REQUIRED");
        verify(refreshService, never()).refreshChannels(any(), any());
    }

    // ------------------------------------------------------------------
    // create_manual_order
    // ------------------------------------------------------------------

    @Test
    void manualOrderSchemaAdvertisesAnInt32PositiveQuantity() {
        JsonNode quantity = toolByName("create_manual_order")
                .inputSchema()
                .path("properties")
                .path("items")
                .path("items")
                .path("properties")
                .path("quantity");

        assertThat(quantity.path("type").asText()).isEqualTo("integer");
        assertThat(quantity.path("minimum").asInt()).isEqualTo(1);
        assertThat(quantity.path("maximum").asLong()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void manualOrderPassesTheThreeSegmentsThroughAndProjectsFourFields() {
        when(manualOrderService.create(any(), eq("hermes-manual-0001"), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(orderDetail(), 201));

        JsonNode result = toolByName("create_manual_order").invoke(context(), Map.of(
                "customer_code", "C-0007",
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区某路 1 号"),
                "items", List.of(Map.of("sku_id", "9", "quantity", 12)),
                "remark", "hermes 代录",
                "idempotency_key", "hermes-manual-0001"));

        // 出参只有四个字段：收货人 PII 是入参、不回吐
        assertThat(result.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("order_id", "order_no", "source_ref", "version");
        assertThat(result.path("order_id").asText()).isEqualTo("5001");
        assertThat(result.path("order_no").asText()).isEqualTo("ORD-20260901-0001");
        assertThat(result.path("source_ref").asText()).isEqualTo("MAN-A1B2C3D4E5F6");
        assertThat(result.path("version").asLong()).isEqualTo(3L);

        ArgumentCaptor<ManualOrderCreateWrite> write = ArgumentCaptor.forClass(ManualOrderCreateWrite.class);
        verify(manualOrderService).create(write.capture(), eq("hermes-manual-0001"), any());
        ManualOrderCreateWrite sent = write.getValue();
        assertThat(sent.customerCode()).isEqualTo("C-0007");
        assertThat(sent.receiver().name()).isEqualTo("张三");
        assertThat(sent.receiver().phone()).isEqualTo("13800000000");
        assertThat(sent.items()).singleElement().satisfies(item -> {
            assertThat(item.skuId()).isEqualTo("9");
            assertThat(item.quantity()).isEqualTo(12);
        });
        assertThat(sent.remark()).isEqualTo("hermes 代录");
    }

    @Test
    void manualOrderOmitsCustomerCodeSoTheServiceFallsBackToThePlatformCustomer() {
        when(manualOrderService.create(any(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(orderDetail(), 201));

        toolByName("create_manual_order").invoke(context(), Map.of(
                "receiver", Map.of("name", "李四", "phone", "13900000000", "address", "北京市朝阳区某路 2 号"),
                "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                "idempotency_key", "hermes-manual-0002"));

        ArgumentCaptor<ManualOrderCreateWrite> write = ArgumentCaptor.forClass(ManualOrderCreateWrite.class);
        verify(manualOrderService).create(write.capture(), anyString(), any());
        assertThat(write.getValue().customerCode()).isNull();
        assertThat(write.getValue().remark()).isNull();
    }

    /**
     * 来源渠道声明：清单必须从 {@link cn.zimu.fulfillment.common.domain.SourceChannel} 派生
     * （排除 MANUAL），硬编码会在新接渠道时安静漏掉。
     */
    @Test
    void manualOrderOriginChannelListIsDerivedFromSourceChannelWithoutManual() {
        String description = toolByName("create_manual_order")
                .inputSchema().path("properties").path("origin_channel").path("description").asText();

        for (SourceChannel channel : SourceChannel.values()) {
            if (channel == SourceChannel.MANUAL) {
                continue;
            }
            assertThat(description).contains(channel.name());
        }
        assertThat(description).contains("存档");
        assertThat(ManualOrderCreateService.archivalOriginChannels())
                .doesNotContain(SourceChannel.MANUAL.name());
    }

    @Test
    void manualOrderForwardsTheDeclaredOriginChannelNormalisedToUpperCase() {
        when(manualOrderService.create(any(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(orderDetail(), 201));

        toolByName("create_manual_order").invoke(context(), Map.of(
                "receiver", Map.of("name", "王五", "phone", "13700000000", "address", "杭州市西湖区某路 3 号"),
                "items", List.of(Map.of("sku_id", "9", "quantity", 2)),
                "origin_channel", "zhonghui",
                "idempotency_key", "hermes-manual-origin-0001"));

        ArgumentCaptor<ManualOrderCreateWrite> write = ArgumentCaptor.forClass(ManualOrderCreateWrite.class);
        verify(manualOrderService).create(write.capture(), anyString(), any());
        assertThat(write.getValue().originChannel()).isEqualTo("ZHONGHUI");
    }

    @Test
    void manualOrderRejectsUnknownOriginAndManualItself() {
        // MANUAL 不是「来源」：手工单自身就是 MANUAL 渠道
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                        "origin_channel", "MANUAL",
                        "idempotency_key", "hermes-manual-origin-0002")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                        "origin_channel", "TAOBAO",
                        "idempotency_key", "hermes-manual-origin-0003")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        verify(manualOrderService, never()).create(any(), anyString(), any());
    }

    @Test
    void manualOrderReplayReturnsTheSameProjectionAndAuditsTheReplay() {
        when(manualOrderService.create(any(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.replayed(201, mapper.valueToTree(orderDetail())));

        JsonNode result = toolByName("create_manual_order").invoke(context(), Map.of(
                "receiver", Map.of("name", "李四", "phone", "13900000000", "address", "北京市朝阳区某路 2 号"),
                "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                "idempotency_key", "hermes-manual-0002"));

        assertThat(result.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("order_id", "order_no", "source_ref", "version");
        assertThat(result.path("order_no").asText()).isEqualTo("ORD-20260901-0001");

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("IDEMPOTENT_REPLAY");
    }

    @Test
    void manualOrderRejectsBadArgumentsBeforeTouchingTheService() {
        // 浮点 token 即使数学上接近整数也不是 JSON integer。
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1.5d)),
                        "idempotency_key", "hermes-manual-0003")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        for (Object invalidQuantity : List.of(1.0d, "1", 2_147_483_648L)) {
            assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                            "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                            "items", List.of(Map.of("sku_id", "9", "quantity", invalidQuantity)),
                            "idempotency_key", "hermes-manual-invalid-shape")))
                    .as(String.valueOf(invalidQuantity))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("INVALID_PARAMETERS");
        }
        // 0 不是正整数
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 0)),
                        "idempotency_key", "hermes-manual-0004")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // receiver 必填
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                        "idempotency_key", "hermes-manual-0005")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // 收货地址必填
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                        "idempotency_key", "hermes-manual-0006")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // items 至少一行
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(),
                        "idempotency_key", "hermes-manual-0007")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // sku_id 必须是正整数标识符
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "SKU-9", "quantity", 1)),
                        "idempotency_key", "hermes-manual-0008")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        // 幂等键必填
        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1)))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");

        verify(manualOrderService, never()).create(any(), anyString(), any());
    }

    @Test
    void manualOrderPropagatesTheServiceBusinessCodeAndAuditsTheFailure() {
        when(manualOrderService.create(any(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.unprocessable(
                        "MANUAL_ORDER_SKU_NOT_FOUND", "SKU 不存在或已停用: 9"));

        assertThatThrownBy(() -> toolByName("create_manual_order").invoke(context(), Map.of(
                        "receiver", Map.of("name", "张三", "phone", "138", "address", "地址"),
                        "items", List.of(Map.of("sku_id", "9", "quantity", 1)),
                        "idempotency_key", "hermes-manual-0009")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("MANUAL_ORDER_SKU_NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // route_order_fulfillment
    // ------------------------------------------------------------------

    @Test
    void routingReturnsShipmentIdsAndTheNewOrderVersion() {
        when(routingService.route(eq(5001L), eq(3L), eq("hermes-route-0001"), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(routeResult(), 201));

        JsonNode result = toolByName("route_order_fulfillment").invoke(context(), Map.of(
                "order_id", "5001",
                "expected_version", "3",
                "idempotency_key", "hermes-route-0001"));

        assertThat(result.path("order_version").asLong()).isEqualTo(4L);
        assertThat(result.path("shipment_ids")).hasSize(2);
        assertThat(result.path("shipment_ids").path(0).asText()).isEqualTo("77");

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("operation").isEqualTo("mcp.route_order_fulfillment");
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("ORDER_FULFILLMENT_ROUTED");
    }

    @Test
    void routingReplayReturnsTheRegistrySnapshotAndAuditsTheReplay() {
        when(routingService.route(anyLong(), anyLong(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.replayed(201, mapper.valueToTree(routeResult())));

        JsonNode result = toolByName("route_order_fulfillment").invoke(context(), Map.of(
                "order_id", "5001",
                "expected_version", "3",
                "idempotency_key", "hermes-route-0001"));

        assertThat(result.path("order_version").asLong()).isEqualTo(4L);
        assertThat(result.path("shipment_ids")).hasSize(2);

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("IDEMPOTENT_REPLAY");
    }

    @Test
    void routingRejectsBadIdentifiersAndVersionsBeforeTouchingTheService() {
        assertThatThrownBy(() -> toolByName("route_order_fulfillment").invoke(context(), Map.of(
                        "order_id", "0", "expected_version", "3", "idempotency_key", "hermes-route-0002")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        assertThatThrownBy(() -> toolByName("route_order_fulfillment").invoke(context(), Map.of(
                        "order_id", "5001", "expected_version", "-1", "idempotency_key", "hermes-route-0003")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        assertThatThrownBy(() -> toolByName("route_order_fulfillment").invoke(context(), Map.of(
                        "order_id", "5001", "idempotency_key", "hermes-route-0004")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");

        verify(routingService, never()).route(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void routingWithoutAgentIdentityIsRejectedBeforeTheService() {
        assertThatThrownBy(() -> toolByName("route_order_fulfillment").invoke(
                        new McpRequestContext("run_x", "run_x", ""),
                        Map.of("order_id", "5001", "expected_version", "3",
                                "idempotency_key", "hermes-route-0005")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("MCP_AUTH_REQUIRED");
        verify(routingService, never()).route(anyLong(), anyLong(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // cancel_jd_outbound：真实动货的逆操作，受人类确认闸保护
    // ------------------------------------------------------------------

    @Test
    void cancelDeclaresTheConfirmationGateAndTellsTheModelWhatItUndoes() {
        McpTool tool = toolByName("cancel_jd_outbound");

        assertThat(tool.inputSchema().path("required").toString())
                .contains("shipment_id")
                .contains("idempotency_key")
                .contains(McpHumanConfirmation.PARAMETER)
                // 单据类型可缺省：不传即服务层默认 XSCK
                .doesNotContain("order_type");
        assertThat(tool.inputSchema().path("properties").path(McpHumanConfirmation.PARAMETER)
                        .path("description").asText())
                .contains("人类确认闸")
                .contains("『确认』")
                .contains("不得代填");
        // 描述必须写清「真实调用京东」「只在未出库前有效」「本地记录删除后可重新提交」
        assertThat(tool.description())
                .contains("真实")
                .contains("京东")
                .contains("出库前")
                .contains("删除")
                .contains("重新提交")
                .contains("人类确认闸");
        // 字典值写进 order_type 描述，模型不必去猜助记码
        String orderType = tool.inputSchema().path("properties").path("order_type")
                .path("description").asText();
        for (String code : List.of("XSCK", "CGRK", "THRK", "TGCK", "ZKTZ", "ZTJG", "BFCK")) {
            assertThat(orderType).as("单据类型字典 %s", code).contains(code);
        }
    }

    @Test
    void cancelWithTheExactWordReachesTheCancelServiceAndAuditsTheAgent() {
        when(cancelService.cancel(
                        eq(31L), eq((String) null), eq("hermes-cancel-0001"), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(cancelResult(), 200));

        JsonNode result = toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                "shipment_id", "31",
                "idempotency_key", "hermes-cancel-0001",
                McpHumanConfirmation.PARAMETER, "确认"));

        assertThat(result.path("cancelled").asBoolean()).isTrue();
        assertThat(result.path("jd_delivery_no").asText()).isEqualTo("JD202609010001");
        assertThat(result.path("submission_record_deleted").asBoolean()).isTrue();

        // 不传 order_type 时必须传 null 下去，由服务层落到 XSCK 默认
        verify(cancelService).cancel(
                eq(31L), eq((String) null), eq("hermes-cancel-0001"), any(CommandContext.class));

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("operation").isEqualTo("mcp.cancel_jd_outbound");
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("JD_OUTBOUND_CANCELLED");
        assertThat(audit.getValue()).extracting("operator").isEqualTo("hermes");
    }

    @Test
    void cancelForwardsTheDeclaredOrderTypeNormalisedToUpperCase() {
        when(cancelService.cancel(anyLong(), anyString(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(cancelResult(), 200));

        toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                "shipment_id", "31",
                "order_type", "bfck",
                "idempotency_key", "hermes-cancel-0002",
                McpHumanConfirmation.PARAMETER, "确认"));

        verify(cancelService).cancel(
                eq(31L), eq("BFCK"), eq("hermes-cancel-0002"), any(CommandContext.class));
    }

    @Test
    void cancelWithoutConfirmationIsRejectedBeforeTouchingTheCancelService() {
        assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(
                        context(), Map.of("shipment_id", "31", "idempotency_key", "hermes-cancel-0003")))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> {
                    BusinessException ex = (BusinessException) failure;
                    assertThat(ex.getBusinessCode()).isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
                    assertThat(ex.getHttpStatus()).isEqualTo(422);
                    assertThat(ex.getMessage()).contains("确认");
                });
        verify(cancelService, never()).cancel(anyLong(), any(), anyString(), any());
    }

    @Test
    void cancelRejectsEveryValueThatIsNotTheExactWord() {
        for (String wrong : List.of("ok", "yes", "确认。", "确认了", "已确认", "同意", "true", " ")) {
            assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                            "shipment_id", "31",
                            "idempotency_key", "hermes-cancel-0004",
                            McpHumanConfirmation.PARAMETER, wrong)))
                    .as("值 %s 必须被拒", wrong)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
        }
        verify(cancelService, never()).cancel(anyLong(), any(), anyString(), any());
    }

    /**
     * 鉴权先于确认闸：未认证调用连确认词都没带，看到的仍必须是鉴权错误——
     * 不得从错误码里探知「这个工具背后还有一道确认闸」。
     */
    @Test
    void cancelWithoutAgentIdentitySeesTheAuthErrorRatherThanTheGate() {
        assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(
                        new McpRequestContext("run_x", "run_x", ""),
                        Map.of("shipment_id", "31", "idempotency_key", "hermes-cancel-0005")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("MCP_AUTH_REQUIRED");
        verify(cancelService, never()).cancel(anyLong(), any(), anyString(), any());
    }

    @Test
    void cancelRejectsBadIdentifiersAndShortKeysBeforeTouchingTheService() {
        assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                        "shipment_id", "0",
                        "idempotency_key", "hermes-cancel-0006",
                        McpHumanConfirmation.PARAMETER, "确认")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                        "shipment_id", "SHIP-31",
                        "idempotency_key", "hermes-cancel-0007",
                        McpHumanConfirmation.PARAMETER, "确认")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("INVALID_PARAMETERS");
        assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                        "shipment_id", "31",
                        "idempotency_key", "short",
                        McpHumanConfirmation.PARAMETER, "确认")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        verify(cancelService, never()).cancel(anyLong(), any(), anyString(), any());
    }

    /** 用户输入不进下游命令，也不进审计明文：审计只留 human_confirmed=true。 */
    @Test
    void cancelConfirmationNeverReachesTheAuditPayloadInPlaintext() {
        when(cancelService.cancel(anyLong(), any(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.executed(cancelResult(), 200));

        toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                "shipment_id", "31",
                "idempotency_key", "hermes-cancel-0008",
                McpHumanConfirmation.PARAMETER, "  确认  "));

        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue())
                .extracting("requestPayload")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .doesNotContainKey(McpHumanConfirmation.PARAMETER)
                .containsEntry(McpHumanConfirmation.AUDIT_FIELD, true)
                .containsEntry("shipment_id", 31L);
        assertThat(audit.getValue()).extracting("requestPayload").asString().doesNotContain("确认");
    }

    @Test
    void cancelReplayReturnsTheRegistrySnapshotAndAuditsTheReplay() {
        when(cancelService.cancel(anyLong(), any(), anyString(), any(CommandContext.class)))
                .thenReturn(IdempotentResult.replayed(200, mapper.valueToTree(cancelResult())));

        JsonNode result = toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                "shipment_id", "31",
                "idempotency_key", "hermes-cancel-0008",
                McpHumanConfirmation.PARAMETER, "确认"));

        assertThat(result.path("cancelled").asBoolean()).isTrue();
        ArgumentCaptor<AuditLogService.AuditCommand> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(audit.capture());
        assertThat(audit.getValue()).extracting("businessCode").isEqualTo("IDEMPOTENT_REPLAY");
    }

    /** 服务层错误码原样透传：出库单不是 SUBMITTED、京东拒绝、操作人未授权都不该被改写。 */
    @Test
    void cancelPropagatesTheServiceBusinessCode() {
        when(cancelService.cancel(anyLong(), any(), anyString(), any(CommandContext.class)))
                .thenThrow(BusinessException.conflict(
                        "JD_OUTBOUND_NOT_SUBMITTED", "该发货批次没有已提交的京东出库单，无可取消"));

        assertThatThrownBy(() -> toolByName("cancel_jd_outbound").invoke(context(), Map.of(
                        "shipment_id", "31",
                        "idempotency_key", "hermes-cancel-0009",
                        McpHumanConfirmation.PARAMETER, "确认")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("JD_OUTBOUND_NOT_SUBMITTED");
    }

    // ------------------------------------------------------------------
    // 装配助手
    // ------------------------------------------------------------------

    private static Map<String, Object> cancelResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipment_id", "31");
        result.put("erp_delivery_no", "ERP20260901001");
        result.put("jd_delivery_no", "JD202609010001");
        result.put("cancelled", true);
        result.put("submission_record_deleted", true);
        return result;
    }

    private McpTool toolByName(String name) {
        return provider.tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static McpRequestContext context() {
        return new McpRequestContext("run_hermes", "run_hermes", "hermes");
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
                null,
                provider,
                agentModules,
                protocolModules);
    }

    private static Map<String, Object> refreshResponse() {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("channel", "CAISHIXIAN");
        channel.put("status", "OK");
        channel.put("message", "在线拉取完成");
        channel.put("order_count", 18);
        channel.put("batch_id", 991L);
        channel.put("batch_no", "IB20260901001");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("channels", List.of(channel));
        response.put("date_begin", "2026-08-03");
        response.put("date_end", "2026-09-01");
        return response;
    }

    private static Map<String, Object> routeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", "5001");
        result.put("order_version", 4L);
        result.put("shipment_ids", List.of("77", "78"));
        return result;
    }

    private static OrderDetailDto orderDetail() {
        return new OrderDetailDto(
                "5001",
                "ORD-20260901-0001",
                "MANUAL",
                "MAN-A1B2C3D4E5F6",
                "7",
                "手工平台客户",
                "张三",
                "CONFIRMED",
                "SKU_MAPPED",
                "HEALTHY",
                0,
                1,
                null,
                null,
                null,
                3L,
                null,
                new Receiver("张三", "13800000000", null, null, null, null, "上海市浦东新区某路 1 号"),
                Settlement.unspecifiedSourceFact(),
                "hermes 代录",
                List.of(),
                List.of());
    }
}
