package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.mcp.McpRequestContext;
import cn.zimu.fulfillment.mcp.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KehuzxRemoteReadToolsTest {

    private static final String CUSTOMER_CODE = "KH-260826-001";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesOnlyNamespacedApprovedReadToolsAndPersistsSourceEnvelope() {
        AtomicReference<KehuzxReadEvidence> recorded = new AtomicReference<>();
        KehuzxMcpProperties properties = properties();
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> mapper.valueToTree(Map.of(
                        "total", 1,
                        "items", List.of(Map.of(
                                "id", "customer-1",
                                "code", CUSTOMER_CODE,
                                "phone", "13800000000",
                                "address", "不应进入 Agent 或证据")))),
                properties,
                recorded::set,
                mapper,
                Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC));

        assertThat(tools.tools())
                .extracting(McpTool::name)
                .containsExactlyInAnyOrder(
                        "kehuzx_search_customers",
                        "kehuzx_get_customer_detail",
                        "kehuzx_search_demands",
                        "kehuzx_search_orders",
                        "kehuzx_get_order_detail");
        assertThat(tools.tools()).allMatch(McpTool::readOnly);
        assertThat(tools.tools()).noneMatch(McpTool::externallyDiscoverable);

        McpRequestContext context =
                new McpRequestContext("run_123", "run_123", "followup-agent");
        tools.authorizeRun(context.requestId(), List.of(CUSTOMER_CODE));
        JsonNode result = tool(tools, "kehuzx_search_customers").invoke(
                context,
                Map.of("keyword", "华北"));

        assertThat(result.path("source").asText()).isEqualTo("KEHUZX");
        assertThat(result.path("contract_version").asText()).isEqualTo("kehuzx-mcp-v1");
        assertThat(result.path("upstream_commit").asText()).isEqualTo("c6a2418");
        assertThat(result.path("queried_at").asText()).isEqualTo("2026-08-26T04:00:00Z");
        assertThat(result.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("13800000000", "不应进入 Agent 或证据");
        assertThat(recorded.get().agentRunId()).isEqualTo("run_123");
        assertThat(recorded.get().toolName()).isEqualTo("search_customers");
        assertThat(recorded.get().argumentsDigest()).hasSize(64);
        assertThat(recorded.get().responseDigest()).hasSize(64);
        assertThat(recorded.get().responsePayload()).isEqualTo(result);
    }

    @Test
    void advertisedWriteNameCannotBeResolvedLocally() {
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> mapper.createObjectNode(),
                properties(),
                evidence -> {},
                mapper,
                Clock.systemUTC());

        assertThatThrownBy(() -> tool(tools, "kehuzx_create_customer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kehuzx_create_customer");
    }

    @Test
    void remoteFailurePersistsOnlyItsStableCodeBeforeRethrow() {
        AtomicReference<KehuzxReadFailure> recordedFailure = new AtomicReference<>();
        KehuzxReadEvidenceRecorder recorder = new KehuzxReadEvidenceRecorder() {
            @Override
            public void record(KehuzxReadEvidence evidence) {}

            @Override
            public void recordFailure(KehuzxReadFailure failure) {
                recordedFailure.set(failure);
            }
        };
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> {
                    throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TIMEOUT);
                },
                properties(),
                recorder,
                mapper,
                Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC));
        tools.authorizeRun("run_failure_123", List.of(CUSTOMER_CODE));

        assertThatThrownBy(() -> tool(tools, "kehuzx_search_customers").invoke(
                        new McpRequestContext("run_failure_123", "run_failure_123", "followup-agent"),
                        Map.of("keyword", "华北")))
                .isInstanceOf(KehuzxReadException.class);
        assertThat(recordedFailure.get().failureCode()).isEqualTo("KEHUZX_TIMEOUT");
        assertThat(recordedFailure.get().agentRunId()).isEqualTo("run_failure_123");
        assertThat(recordedFailure.get().toolName()).isEqualTo("search_customers");
    }

    @Test
    void uniqueCustomerCodeCreatesServerOwnedScopeAndOverridesModelSearchArguments() {
        List<Map<String, Object>> observedArguments = new ArrayList<>();
        KehuzxReadGateway gateway = (name, arguments) -> {
            observedArguments.add(Map.copyOf(arguments));
            if ("search_customers".equals(name)) {
                return mapper.valueToTree(Map.of(
                        "total", 1,
                        "items", List.of(Map.of(
                                "id", "customer-1",
                                "customer_id", "customer-1",
                                "code", CUSTOMER_CODE,
                                "name", "华北餐饮"))));
            }
            return mapper.valueToTree(Map.of("total", 0, "items", List.of()));
        };
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                gateway, properties(), evidence -> {}, mapper, Clock.systemUTC());
        McpRequestContext context =
                new McpRequestContext("run_scoped_123", "run_scoped_123", "followup-agent");
        tools.authorizeRun(context.requestId(), List.of(CUSTOMER_CODE));

        tool(tools, "kehuzx_search_customers").invoke(
                context, Map.of("keyword", "模型编造的客户", "limit", 100));
        tool(tools, "kehuzx_search_demands").invoke(
                context, Map.of("customer_id", "attacker-customer", "keyword", "牛肉"));

        assertThat(observedArguments.get(0))
                .containsExactly(Map.entry("keyword", CUSTOMER_CODE), Map.entry("limit", 10));
        assertThat(observedArguments.get(1))
                .containsEntry("customer_id", "customer-1")
                .containsEntry("keyword", "牛肉");
    }

    @Test
    void customerDetailOrderCreatesAProvenScopeForOrderDetail() {
        List<Map<String, Object>> observedArguments = new ArrayList<>();
        KehuzxReadGateway gateway = (name, arguments) -> {
            observedArguments.add(Map.copyOf(arguments));
            return switch (name) {
                case "search_customers" -> mapper.valueToTree(Map.of(
                        "total", 1,
                        "items", List.of(Map.of(
                                "id", "customer-1", "customer_id", "customer-1",
                                "code", CUSTOMER_CODE, "name", "华北餐饮"))));
                case "get_customer_detail" -> mapper.valueToTree(Map.of(
                        "customer", Map.of("id", "customer-1", "code", CUSTOMER_CODE, "name", "华北餐饮"),
                        "demands", List.of(),
                        "templates", List.of(),
                        "sample_orders", List.of(),
                        "formal_orders", List.of(Map.of(
                                "id", "order-1", "code", "FO-001", "name", "月结订单",
                                "customer_id", "customer-1"))));
                case "get_order_detail" -> mapper.valueToTree(Map.of(
                        "id", "order-1", "code", "FO-001", "name", "月结订单",
                        "customer_id", "customer-1", "items", List.of()));
                default -> throw new AssertionError(name);
            };
        };
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                gateway, properties(), evidence -> {}, mapper, Clock.systemUTC());
        McpRequestContext context =
                new McpRequestContext("run_order_scope", "run_order_scope", "followup-agent");
        tools.authorizeRun(context.requestId(), List.of(CUSTOMER_CODE));

        tool(tools, "kehuzx_search_customers").invoke(context, Map.of("keyword", "华北"));
        tool(tools, "kehuzx_get_customer_detail").invoke(
                context, Map.of("customer_name", "模型不应控制此参数"));
        tool(tools, "kehuzx_get_order_detail").invoke(
                context, Map.of("order_code", "FO-001"));

        assertThat(observedArguments.get(1)).containsExactly(Map.entry("customer_id", "customer-1"));
        assertThat(observedArguments.get(2)).containsExactly(Map.entry("order_code", "FO-001"));
    }

    @Test
    void customerScopeIsImmutableAndASecondSearchNeverReachesTheRemote() {
        java.util.concurrent.atomic.AtomicInteger remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> {
                    remoteCalls.incrementAndGet();
                    return mapper.valueToTree(Map.of(
                            "total", 1,
                            "items", List.of(Map.of(
                                    "id", "customer-1", "customer_id", "customer-1",
                                    "code", CUSTOMER_CODE, "name", "华北餐饮"))));
                },
                properties(),
                evidence -> {},
                mapper,
                Clock.systemUTC());
        McpRequestContext context =
                new McpRequestContext("run_immutable_scope", "run_immutable_scope", "followup-agent");
        tools.authorizeRun(context.requestId(), List.of(CUSTOMER_CODE));

        tool(tools, "kehuzx_search_customers").invoke(context, Map.of("keyword", "华北"));
        assertThatThrownBy(() -> tool(tools, "kehuzx_search_customers").invoke(
                        context, Map.of("keyword", "另一个客户")))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
        assertThat(remoteCalls).hasValue(1);
    }

    @Test
    void zeroCustomerSearchIsTerminalForTheRunAndCannotBeRetriedWithAGuess() {
        java.util.concurrent.atomic.AtomicInteger remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> {
                    remoteCalls.incrementAndGet();
                    return mapper.valueToTree(Map.of("total", 0, "items", List.of()));
                },
                properties(), evidence -> {}, mapper, Clock.systemUTC());
        McpRequestContext context =
                new McpRequestContext("run_zero_terminal", "run_zero_terminal", "followup-agent");
        tools.authorizeRun(context.requestId(), List.of(CUSTOMER_CODE));

        tool(tools, "kehuzx_search_customers").invoke(context, Map.of("keyword", "第一次"));
        assertThatThrownBy(() -> tool(tools, "kehuzx_search_customers").invoke(
                        context, Map.of("keyword", "模型猜的第二次")))
                .isInstanceOf(KehuzxReadException.class);
        assertThat(remoteCalls).hasValue(1);
    }

    @Test
    void activeRunSearchAttemptCannotBeEvictedByOtherRuns() {
        java.util.concurrent.atomic.AtomicInteger remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> {
                    remoteCalls.incrementAndGet();
                    return mapper.valueToTree(Map.of(
                            "total", 1,
                            "items", List.of(Map.of(
                                    "id", "customer-1", "customer_id", "customer-1",
                                    "code", CUSTOMER_CODE, "name", "华北餐饮"))));
                },
                properties(), evidence -> {}, mapper, Clock.systemUTC());
        McpRequestContext protectedContext =
                new McpRequestContext("protected-run", "protected-run", "followup-agent");
        tools.authorizeRun(protectedContext.requestId(), List.of(CUSTOMER_CODE));

        tool(tools, "kehuzx_search_customers").invoke(
                protectedContext, Map.of("keyword", "protected-run"));
        for (int index = 0; index < 4_999; index++) {
            String runId = "noise-run-" + index;
            tools.authorizeRun(runId, List.of(CUSTOMER_CODE));
            tool(tools, "kehuzx_search_customers").invoke(
                    new McpRequestContext(runId, runId, "followup-agent"),
                    Map.of("keyword", runId));
        }

        assertThatThrownBy(() -> tool(tools, "kehuzx_search_customers").invoke(
                        protectedContext, Map.of("keyword", "different-customer")))
                .isInstanceOf(KehuzxReadException.class);
        assertThat(remoteCalls).hasValue(5_000);
    }

    @Test
    void multipleCustomerCodesFailClosedBeforeAnyRemoteRead() {
        java.util.concurrent.atomic.AtomicInteger remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
        KehuzxRemoteReadTools tools = new KehuzxRemoteReadTools(
                (name, arguments) -> {
                    remoteCalls.incrementAndGet();
                    return mapper.createObjectNode();
                },
                properties(), evidence -> {}, mapper, Clock.systemUTC());
        McpRequestContext context =
                new McpRequestContext("run_multiple_codes", "run_multiple_codes", "followup-agent");
        tools.authorizeRun(
                context.requestId(), List.of("KH-260826-001", "KH-260826-002"));

        assertThatThrownBy(() -> tool(tools, "kehuzx_search_customers").invoke(
                        context, Map.of("keyword", "模型任选一个")))
                .isInstanceOf(KehuzxReadException.class);
        assertThat(remoteCalls).hasValue(0);
    }

    private static McpTool tool(KehuzxRemoteReadTools tools, String name) {
        return tools.tools().stream()
                .filter(candidate -> name.equals(candidate.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + name));
    }

    private static KehuzxMcpProperties properties() {
        KehuzxMcpProperties properties = new KehuzxMcpProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create("https://kehuzx.internal/mcp"));
        properties.setAllowedHost("kehuzx.internal");
        properties.setAllowedPort(443);
        properties.setReadToken("read-only");
        properties.setContractVersion("kehuzx-mcp-v1");
        properties.setUpstreamCommit("c6a2418");
        return properties;
    }
}
