package cn.zimu.fulfillment.followup;

import static cn.zimu.fulfillment.mcp.McpToolRegistry.integerProperty;
import static cn.zimu.fulfillment.mcp.McpToolRegistry.schema;
import static cn.zimu.fulfillment.mcp.McpToolRegistry.stringProperty;

import cn.zimu.fulfillment.mcp.McpRequestContext;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Agent-facing wrappers around the five approved Kehuzx read operations. */
@Component
public class KehuzxRemoteReadTools {

    private static final int MAX_ACTIVE_RUNS = 5_000;

    private static final Pattern MOBILE = Pattern.compile("(?<![0-9])1[3-9](?:[ -]?[0-9]){9}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9])");
    private static final Set<String> CUSTOMER_FIELDS = Set.of(
            "id", "customer_id", "code", "name", "customer_name", "company_name",
            "status", "category", "industry", "match_score", "demand_count",
            "sample_order_count", "formal_order_count", "created_at");
    private static final Set<String> DEMAND_FIELDS = Set.of(
            "id", "code", "name", "customer_id", "customer_name", "product_name",
            "product_category", "expected_delivery_date", "created_at");
    private static final Set<String> ORDER_FIELDS = Set.of(
            "id", "code", "name", "type", "customer_id", "customer_name",
            "workflow_status", "workflow_status_label", "is_submitted", "is_suspended",
            "suspended_reason", "delivery_date", "settlement_amount", "item_count",
            "version", "created_at", "updated_at");
    private static final Set<String> ORDER_ITEM_FIELDS = Set.of(
            "id", "product_name", "source_template_id", "source_template_code",
            "source_template_name", "supplier_id", "supplier_name", "source_sample_order_id",
            "quantity_per_unit", "quantity_unit", "unit_count", "total_quantity",
            "unit_price", "pricing_unit");
    private static final Set<String> TEMPLATE_FIELDS = Set.of(
            "id", "code", "name", "product_name", "status", "status_label",
            "unit_price", "pricing_unit", "created_at");

    private final KehuzxReadGateway gateway;
    private final KehuzxMcpProperties properties;
    private final KehuzxReadEvidenceRecorder recorder;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final List<McpTool> tools;
    private final RunScopeStore scopes;

    @Autowired
    public KehuzxRemoteReadTools(
            KehuzxReadGateway gateway,
            KehuzxMcpProperties properties,
            KehuzxReadEvidenceRecorder recorder,
            ObjectMapper mapper) {
        this(gateway, properties, recorder, mapper, Clock.systemUTC());
    }

    KehuzxRemoteReadTools(
            KehuzxReadGateway gateway,
            KehuzxMcpProperties properties,
            KehuzxReadEvidenceRecorder recorder,
            ObjectMapper mapper,
            Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.recorder = recorder;
        this.mapper = mapper;
        this.clock = clock;
        this.scopes = new RunScopeStore(clock, Duration.ofHours(24));
        this.tools = List.of(
                tool("search_customers", "搜索 Kehuzx 客户候选；候选不是客户身份确认。",
                        schema(Map.of(
                                "keyword", stringProperty("名称、编号、公司、联系人或电话关键词"),
                                "customer_name", stringProperty("客户名称精确过滤"),
                                "company_name", stringProperty("公司名称精确过滤"),
                                "contact_name", stringProperty("联系人精确过滤"),
                                "phone", stringProperty("电话精确过滤"),
                                "status", stringProperty("客户状态"),
                                "limit", integerProperty("返回条数，1-100")), List.of())),
                tool("get_customer_detail", "读取一个已选定 Kehuzx 客户及关联需求、模板和订单。",
                        schema(Map.of(
                                "customer_id", stringProperty("客户 ID"),
                                "customer_code", stringProperty("客户编号"),
                                "customer_name", stringProperty("客户名称")), List.of())),
                tool("search_demands", "搜索 Kehuzx 客户需求候选。",
                        schema(Map.of(
                                "customer_id", stringProperty("客户 ID"),
                                "keyword", stringProperty("需求或产品关键词"),
                                "limit", integerProperty("返回条数，1-100")), List.of())),
                tool("search_orders", "搜索 Kehuzx 样品单和正式订单候选。",
                        schema(Map.of(
                                "type", stringProperty("sample 或 formal"),
                                "customer", stringProperty("客户名称或编号"),
                                "status", stringProperty("流程状态"),
                                "keyword", stringProperty("订单、编号或产品关键词"),
                                "date_from", stringProperty("交付日期起 YYYY-MM-DD"),
                                "date_to", stringProperty("交付日期止 YYYY-MM-DD"),
                                "limit", integerProperty("返回条数，1-100")), List.of())),
                tool("get_order_detail", "读取一个已选定 Kehuzx 订单的明细与流程事实。",
                        schema(Map.of(
                                "order_code", stringProperty("订单编号"),
                                "order_name", stringProperty("订单名称")), List.of())));
    }

    public List<McpTool> tools() {
        return tools;
    }

    /** Install the server-owned customer codes allowed to establish this Agent run's scope. */
    public void authorizeRun(String runId, List<String> customerIdentifiers) {
        scopes.authorize(runId, customerIdentifiers == null ? List.of() : customerIdentifiers);
    }

    /** Release in-memory scope after the durable draft/evidence transaction has finished. */
    public void completeRun(String runId) {
        scopes.complete(runId);
    }

    private McpTool tool(String remoteName, String description, ObjectNode inputSchema) {
        return new McpToolRegistry.SimpleTool(
                "kehuzx_" + remoteName,
                description + " 返回内容带 KEHUZX 来源、契约版本、上游提交与查询时间。",
                inputSchema,
                (context, arguments) -> invoke(context, remoteName, arguments)) {
            @Override
            public boolean externallyDiscoverable() {
                return false;
            }
        };
    }

    private JsonNode invoke(McpRequestContext context, String remoteName, Map<String, Object> arguments) {
        JsonNode data;
        Map<String, Object> effectiveArguments;
        try {
            effectiveArguments = scopedArguments(
                    context.requestId(), remoteName, arguments == null ? Map.of() : arguments);
            data = sanitizeToolPayload(remoteName, gateway.call(remoteName, effectiveArguments));
            updateAndValidateScope(context.requestId(), remoteName, data);
        } catch (KehuzxReadException failure) {
            recorder.recordFailure(new KehuzxReadFailure(
                    context.requestId(),
                    remoteName,
                    failure.code().name(),
                    properties.getContractVersion(),
                    properties.getUpstreamCommit(),
                    clock.instant()));
            throw failure;
        }
        Instant queriedAt = clock.instant();
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("source", "KEHUZX");
        envelope.put("contract_version", properties.getContractVersion());
        envelope.put("upstream_commit", properties.getUpstreamCommit());
        envelope.put("queried_at", queriedAt.toString());
        if ("search_customers".equals(remoteName)) {
            String authorized = scopes.customerIdentifier(context.requestId());
            if (authorized != null) {
                envelope.put("authorized_customer_code", authorized);
            }
        }
        envelope.set("data", data);
        recorder.record(new KehuzxReadEvidence(
                context.requestId(),
                remoteName,
                digest(mapper.valueToTree(effectiveArguments).toString()),
                digest(envelope.toString()),
                envelope.deepCopy(),
                properties.getContractVersion(),
                properties.getUpstreamCommit(),
                queriedAt));
        return envelope;
    }

    private Map<String, Object> scopedArguments(
            String runId, String remoteName, Map<String, Object> requested) {
        if ("search_customers".equals(remoteName)) {
            String customerIdentifier = scopes.beginCustomerSearch(runId);
            if (customerIdentifier == null) {
                throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
            }
            return Map.of("keyword", customerIdentifier, "limit", 10);
        }
        CustomerScope scope = scopes.get(runId);
        if (scope == null) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
        }
        return switch (remoteName) {
            case "get_customer_detail" -> Map.of("customer_id", scope.customerId());
            case "search_demands" -> {
                Map<String, Object> fixed = new LinkedHashMap<>(requested);
                fixed.put("customer_id", scope.customerId());
                fixed.put("limit", Math.min(integer(requested.get("limit"), 20), 100));
                yield Map.copyOf(fixed);
            }
            case "search_orders" -> {
                String customer = scope.customerCode() == null
                        ? scope.customerName()
                        : scope.customerCode();
                if (customer == null || customer.isBlank()) {
                    throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
                }
                Map<String, Object> fixed = new LinkedHashMap<>(requested);
                fixed.put("customer", customer);
                fixed.put("limit", Math.min(integer(requested.get("limit"), 20), 100));
                yield Map.copyOf(fixed);
            }
            case "get_order_detail" -> {
                String code = string(requested.get("order_code"));
                String name = string(requested.get("order_name"));
                if ((code == null || !scope.orderCodes().contains(code))
                        && (name == null || !scope.orderNames().contains(name))) {
                    throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
                }
                yield code != null && scope.orderCodes().contains(code)
                        ? Map.of("order_code", code)
                        : Map.of("order_name", name);
            }
            default -> throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
        };
    }

    private void updateAndValidateScope(String runId, String remoteName, JsonNode data) {
        if ("search_customers".equals(remoteName)) {
            JsonNode items = data.path("items");
            if (items.isArray() && items.size() == 1) {
                JsonNode customer = items.get(0);
                String id = customer.path("customer_id").asText(customer.path("id").asText(""));
                String code = text(customer, "code");
                String authorized = scopes.customerIdentifier(runId);
                if (!id.isBlank() && authorized != null && authorized.equalsIgnoreCase(code)) {
                    scopes.put(runId, new CustomerScope(
                            id,
                            code,
                            text(customer, "name", "customer_name"),
                            Set.of(),
                            Set.of()));
                } else {
                    scopes.clearScope(runId);
                    throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
                }
            } else {
                scopes.clearScope(runId);
            }
            return;
        }
        CustomerScope scope = scopes.get(runId);
        if (scope == null) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
        }
        if ("get_customer_detail".equals(remoteName)) {
            requireOwner(scope, data.path("customer").path("id").asText(""));
            Set<String> codes = new LinkedHashSet<>(scope.orderCodes());
            Set<String> names = new LinkedHashSet<>(scope.orderNames());
            collectOrderKeys(data.path("sample_orders"), codes, names);
            collectOrderKeys(data.path("formal_orders"), codes, names);
            scopes.put(runId, scope.withOrders(Set.copyOf(codes), Set.copyOf(names)));
            return;
        }
        if ("search_demands".equals(remoteName)) {
            requireOwnedItems(scope, data.path("items"));
            return;
        }
        if ("search_orders".equals(remoteName)) {
            requireOwnedItems(scope, data.path("items"));
            Set<String> codes = new LinkedHashSet<>(scope.orderCodes());
            Set<String> names = new LinkedHashSet<>(scope.orderNames());
            data.path("items").forEach(order -> {
                addText(codes, order.path("code"));
                addText(names, order.path("name"));
            });
            scopes.put(runId, scope.withOrders(Set.copyOf(codes), Set.copyOf(names)));
            return;
        }
        if ("get_order_detail".equals(remoteName)) {
            requireOwner(scope, data.path("customer_id").asText(""));
        }
    }

    private static void requireOwnedItems(CustomerScope scope, JsonNode items) {
        if (!items.isArray()) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
        items.forEach(item -> requireOwner(scope, item.path("customer_id").asText("")));
    }

    private static void requireOwner(CustomerScope scope, String ownerId) {
        if (!scope.customerId().equals(ownerId)) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        try {
            return Math.max(1, Integer.parseInt(String.valueOf(value)));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void addText(Set<String> target, JsonNode value) {
        if (value.isTextual() && !value.asText().isBlank()) {
            target.add(value.asText());
        }
    }

    private static void collectOrderKeys(
            JsonNode orders, Set<String> codes, Set<String> names) {
        if (!orders.isArray()) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
        orders.forEach(order -> {
            addText(codes, order.path("code"));
            addText(names, order.path("name"));
        });
    }

    private JsonNode sanitize(JsonNode value) {
        if (value == null || value.isNull()) {
            return mapper.nullNode();
        }
        if (value.isArray()) {
            var result = mapper.createArrayNode();
            value.forEach(item -> result.add(sanitize(item)));
            return result;
        }
        if (value.isTextual()) {
            String safe = MOBILE.matcher(value.asText()).replaceAll("***");
            safe = EMAIL.matcher(safe).replaceAll("***");
            return mapper.getNodeFactory().textNode(ID_CARD.matcher(safe).replaceAll("***"));
        }
        if (!value.isObject()) {
            return value.deepCopy();
        }
        ObjectNode result = mapper.createObjectNode();
        value.properties().forEach(entry -> {
            if (!sensitive(entry.getKey())) {
                result.set(entry.getKey(), sanitize(entry.getValue()));
            }
        });
        return result;
    }

    private JsonNode sanitizeToolPayload(String toolName, JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
        ObjectNode result = mapper.createObjectNode();
        switch (toolName) {
            case "search_customers" -> copySearchResult(raw, result, CUSTOMER_FIELDS);
            case "search_demands" -> copySearchResult(raw, result, DEMAND_FIELDS);
            case "search_orders" -> copySearchResult(raw, result, ORDER_FIELDS);
            case "get_customer_detail" -> {
                result.set("customer", allowObject(raw.path("customer"), CUSTOMER_FIELDS));
                result.set("demands", allowArray(raw.path("demands"), DEMAND_FIELDS));
                result.set("templates", allowArray(raw.path("templates"), TEMPLATE_FIELDS));
                result.set("sample_orders", allowArray(raw.path("sample_orders"), ORDER_FIELDS));
                result.set("formal_orders", allowArray(raw.path("formal_orders"), ORDER_FIELDS));
            }
            case "get_order_detail" -> {
                ObjectNode order = allowObject(raw, ORDER_FIELDS);
                order.set("items", allowArray(raw.path("items"), ORDER_ITEM_FIELDS));
                result = order;
            }
            default -> throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
        }
        return result;
    }

    private void copySearchResult(JsonNode raw, ObjectNode target, Set<String> itemFields) {
        if (!raw.path("total").isNumber() || !raw.path("items").isArray()) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
        target.put("total", raw.path("total").asInt());
        target.set("items", allowArray(raw.path("items"), itemFields));
    }

    private ArrayNode allowArray(JsonNode values, Set<String> fields) {
        if (!values.isArray() || values.size() > 100) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.add(allowObject(value, fields)));
        return result;
    }

    private ObjectNode allowObject(JsonNode source, Set<String> fields) {
        if (!source.isObject()) {
            throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        }
        ObjectNode result = mapper.createObjectNode();
        fields.forEach(field -> {
            JsonNode value = source.get(field);
            if (value != null && !value.isContainerNode()) {
                result.set(field, sanitize(value));
            }
        });
        return result;
    }

    private static boolean sensitive(String field) {
        String normalized = field == null ? "" : field.toLowerCase(java.util.Locale.ROOT);
        return Set.of(
                        "phone", "mobile", "wechat", "address", "contact_name",
                        "receiver", "recipient", "raw_payload", "raw_content",
                        "token", "secret", "credential", "password", "notes", "remark")
                .stream()
                .anyMatch(normalized::contains);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record CustomerScope(
            String customerId,
            String customerCode,
            String customerName,
            Set<String> orderCodes,
            Set<String> orderNames) {
        CustomerScope withOrders(Set<String> codes, Set<String> names) {
            return new CustomerScope(customerId, customerCode, customerName, codes, names);
        }
    }

    private static final class RunScopeStore {
        private final Clock clock;
        private final Duration ttl;
        private final LinkedHashMap<String, RunState> states = new LinkedHashMap<>();

        private RunScopeStore(Clock clock, Duration ttl) {
            this.clock = clock;
            this.ttl = ttl;
        }

        synchronized CustomerScope get(String runId) {
            removeExpired();
            RunState state = states.get(runId);
            return state == null ? null : state.scope();
        }

        synchronized void put(String runId, CustomerScope scope) {
            removeExpired();
            RunState state = states.get(runId);
            if (state == null) {
                throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
            }
            states.put(runId, new RunState(
                    scope, state.customerIdentifiers(), state.searchAttempted(), state.expiresAt()));
        }

        synchronized void authorize(String runId, List<String> customerIdentifiers) {
            removeExpired();
            if (states.containsKey(runId)) {
                throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
            }
            if (states.size() >= MAX_ACTIVE_RUNS) {
                throw new KehuzxReadException(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
            }
            Set<String> normalized = customerIdentifiers.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.strip().toUpperCase(java.util.Locale.ROOT))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            states.put(runId, new RunState(
                    null, Set.copyOf(normalized), false, clock.instant().plus(ttl)));
        }

        synchronized String beginCustomerSearch(String runId) {
            removeExpired();
            RunState state = states.get(runId);
            if (state == null || state.searchAttempted()) {
                return null;
            }
            states.put(runId, new RunState(
                    state.scope(), state.customerIdentifiers(), true, state.expiresAt()));
            return state.customerIdentifiers().size() == 1
                    ? state.customerIdentifiers().iterator().next()
                    : null;
        }

        synchronized String customerIdentifier(String runId) {
            removeExpired();
            RunState state = states.get(runId);
            return state != null && state.customerIdentifiers().size() == 1
                    ? state.customerIdentifiers().iterator().next()
                    : null;
        }

        synchronized void clearScope(String runId) {
            RunState state = states.get(runId);
            if (state != null) {
                states.put(runId, new RunState(
                        null, state.customerIdentifiers(), state.searchAttempted(), state.expiresAt()));
            }
        }

        synchronized void complete(String runId) {
            states.remove(runId);
        }

        private void removeExpired() {
            Instant now = clock.instant();
            while (!states.isEmpty()
                    && !states.firstEntry().getValue().expiresAt().isAfter(now)) {
                states.remove(states.firstEntry().getKey());
            }
        }

        private record RunState(
                CustomerScope scope,
                Set<String> customerIdentifiers,
                boolean searchAttempted,
                Instant expiresAt) {}
    }
}
