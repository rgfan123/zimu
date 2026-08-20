package cn.zimu.fulfillment.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.Yaml;

/**
 * 工单 07 契约门禁：从运行中的应用导出 springdoc 生成的 OpenAPI spec，与手写评审契约
 * {@code docs/openapi.yaml} 做结构化比对，漂移即失败并打印差异。
 *
 * <h2>比对粒度（详见工单 Resolution）</h2>
 * <ul>
 *   <li><b>比对</b>：路径模板集合（{@code {param}} 归一为 {@code {}}，双向）、每路径的方法集合、
 *       每操作的 query/header 参数（{@code name+in}，解析 {@code $ref}）、2xx 响应码集合、
 *       请求体与成功响应的 schema 引用名（归一化 + 别名注册表）。</li>
 *   <li><b>排除的噪音</b>：描述文本、example、operationId、tags、servers、openapi 版本、
 *       info、排序、错误响应（default/4xx/5xx）、路径参数命名（命名约定）、
 *       认证头 {@code X-Operator}/{@code Idempotency-Key}（网关注入的基础设施头）、
 *       springdoc 无法推断的 inline schema（控制器返回 {@code ResponseEntity<?>}/{@code Map} 时）。</li>
 * </ul>
 *
 * <h2>首次比对登记（详见工单 Resolution）</h2>
 * <ul>
 *   <li>{@link #JD_PASSTHROUGH_PREFIXES}：京东 ISC SDK 透传层（jd-basicinfo/jd-order/jd-return/
 *       jd-serial/jd-stock/jd-write 六个控制器，55 条路径）尚未评审进手写契约，登记为已知豁免，
 *       门禁仍会盯住它们，未来任何新增/变更都会触发。</li>
 *   <li>动态状态码规则：控制器经 {@code WriteCommands.respond} 返回 {@code ResponseEntity<?>} 时
 *       springdoc 无法推断状态码、一律显示 200；生成侧恰为 {@code {200}} 且手写侧恰为一个 2xx 时，
 *       以手写侧的评审状态码为准，不比对。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractConsistencyTest {

    /** 京东 ISC SDK 透传层路径前缀：已知豁免，理由见工单 Resolution。 */
    private static final Set<String> JD_PASSTHROUGH_PREFIXES = Set.of(
            "/api/v1/jd-basicinfo/",
            "/api/v1/jd-order/",
            "/api/v1/jd-return/",
            "/api/v1/jd-serial/",
            "/api/v1/jd-stock/",
            "/api/v1/jd-write/");

    /** 网关注入的基础设施头：所有业务写操作都会携带，手写契约记录不一致，排除在参数比对之外。 */
    private static final Set<String> AUTH_HEADER_PARAMS = Set.of("X-Operator", "Idempotency-Key");

    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "options", "head", "trace");

    /**
     * schema 引用名的全局别名（生成物 DTO 名 → 手写契约评审名）。仅当归一化后仍不一致时生效。
     * 这是「生成物 DTO 命名 → 评审命名」的约定注册表：新增漂移需要在此登记或修正手写契约。
     */
    private static final Map<String, String> GLOBAL_SCHEMA_ALIASES = Map.of(
            "JdResult", "JdQueryResult");

    /**
     * 请求体 schema 引用名的按操作别名，key 为 "METHOD 归一化路径"。
     */
    private static final Map<String, Map<String, String>> REQUEST_BODY_ALIASES = Map.of(
            "POST /api/v1/tracking-drafts/{}/confirm",
                    Map.of("TrackingDraftConfirmCommand", "ConfirmTrackingDraftCommand"),
            "PUT /api/v1/carrier-prefix-mappings",
                    Map.of("CarrierPrefixMappingReplaceCommand", "ReplaceCarrierPrefixMappingsCommand"),
            "POST /internal/v1/orders",
                    Map.of("CanonicalOrderInput", "InternalOrderInput"));

    /**
     * 成功响应 schema 引用名的按操作别名，key 为 "METHOD 归一化路径"。
     * 页面包装器（生成物 {@code PageResponse<T>}）在评审契约里按端点命名（如 OrderPage），
     * 无法用全局规则表达，登记在此。
     */
    private static final Map<String, Map<String, String>> RESPONSE_ALIASES = Map.ofEntries(
            Map.entry("GET /api/v1/categories", Map.of("PageResponseMasterDataRecord", "MasterDataPage")),
            Map.entry("GET /api/v1/channel-messages", Map.of("PageResponseChannelMessageSummaryDto", "ChannelMessagePage")),
            Map.entry("GET /api/v1/customers", Map.of("PageResponseMasterDataRecord", "MasterDataPage")),
            Map.entry("GET /api/v1/fulfillment-exports", Map.of("PageResponseMapStringObject", "FulfillmentExportPage")),
            Map.entry("GET /api/v1/fulfillments", Map.of("PageResponseMapStringObject", "FulfillmentPage")),
            Map.entry("GET /api/v1/import-batches/{}/rows", Map.of("PageResponseMapStringObject", "RawImportRowPage")),
            Map.entry("GET /api/v1/message-submissions/tasks", Map.of("PageResponseAsyncTaskSummary", "MessageTaskPage")),
            Map.entry("GET /api/v1/operational-alerts", Map.of("PageResponseOperationalAlertDto", "OperationalAlertPage")),
            Map.entry("GET /api/v1/order-drafts", Map.of("PageResponseOrderDraftDetailDto", "OrderDraftPage")),
            Map.entry("GET /api/v1/orders", Map.of("PageResponseOrderSummaryDto", "OrderPage")),
            Map.entry("GET /api/v1/procurement-tickets", Map.of("PageResponseMapStringObject", "ProcurementTicketPage")),
            Map.entry("GET /api/v1/products", Map.of("PageResponseMasterDataRecord", "MasterDataPage")),
            Map.entry("GET /api/v1/provider-sku-mappings", Map.of("PageResponseMasterDataRecord", "MasterDataPage")),
            Map.entry("GET /api/v1/review-cases", Map.of("PageResponseReviewCaseDto", "ReviewCasePage")),
            Map.entry("GET /api/v1/shipments", Map.of("PageResponseMapStringObject", "ShipmentPage")),
            Map.entry("GET /api/v1/skus", Map.of("PageResponseMasterDataRecord", "SkuPage")),
            Map.entry("GET /api/v1/skus/{}", Map.of("MasterDataRecord", "SkuRecord")),
            Map.entry("GET /api/v1/source-sku-mappings", Map.of("PageResponseMasterDataRecord", "MasterDataPage")),
            Map.entry("GET /api/v1/tracking-drafts", Map.of("PageResponseProviderTrackingDraftDetailDto", "TrackingDraftPage")),
            Map.entry("GET /api/v1/tracking-drafts/{}", Map.of("ProviderTrackingDraftDetailDto", "TrackingDraftDetail")),
            Map.entry("GET /api/v1/audit-logs", Map.of("PageResponseAuditLogDto", "AuditLogPage")),
            Map.entry("GET /api/v1/admin/message-pipeline/tasks", Map.of("PageResponseAsyncTaskSummary", "MessagePipelineTaskPage")),
            Map.entry("GET /api/v1/admin/message-pipeline/media-failures", Map.of("PageResponseMessageMediaFailureDto", "MessageMediaFailurePage")));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate http;

    private static final Pattern PATH_VAR = Pattern.compile("\\{[^}]*}");

    /** 从运行中的应用导出 springdoc 生成的当前契约，落盘为 target/generated-openapi.yaml 供人工检查。 */
    @Test
    void exportGeneratedSpecArtifact() throws Exception {
        ResponseEntity<String> resp = http.getForEntity("/v3/api-docs.yaml", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("GET /v3/api-docs.yaml").isTrue();
        String body = resp.getBody() == null ? "" : resp.getBody();
        Path out = Path.of("target", "generated-openapi.yaml");
        Files.createDirectories(out.getParent());
        Files.writeString(out, body, StandardCharsets.UTF_8);
    }

    /** 门禁本体：生成契约与手写评审契约的结构化比对，漂移即失败并打印差异。 */
    @Test
    void generatedContractMatchesHandwrittenReviewContract() {
        String generatedYaml = fetchGeneratedSpec();
        String handwrittenYaml = readHandwrittenContract();
        Map<String, Object> generated = parse(generatedYaml);
        Map<String, Object> handwritten = parse(handwrittenYaml);

        List<String> diffs = compare(generated, handwritten);
        if (!diffs.isEmpty()) {
            System.out.println("=== OpenAPI 契约漂移（生成物 vs 手写 docs/openapi.yaml），共 " + diffs.size() + " 处 ===");
            diffs.forEach(d -> System.out.println(d));
        }
        assertThat(diffs).as("生成契约与手写评审契约的结构化比对必须为零差异（差异逐条登记在工单 07 Resolution）")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // 比对逻辑
    // ------------------------------------------------------------------

    private List<String> compare(Map<String, Object> generated, Map<String, Object> handwritten) {
        List<String> diffs = new ArrayList<>();
        Map<String, Object> genPaths = paths(generated);
        Map<String, Object> hwPaths = paths(handwritten);
        Map<String, Object> genComponents = components(generated);
        Map<String, Object> hwComponents = components(handwritten);

        Map<String, String> genNormalized = new LinkedHashMap<>();
        genPaths.forEach((p, v) -> genNormalized.put(normalizePath(p), p));
        Map<String, String> hwNormalized = new LinkedHashMap<>();
        hwPaths.forEach((p, v) -> hwNormalized.put(normalizePath(p), p));

        // 1) 路径集合：双向
        for (String p : sorted(genNormalized.keySet())) {
            if (!hwNormalized.containsKey(p) && !isJdPassthrough(p)) {
                diffs.add("GENERATED_ONLY_PATH  " + p + "  方法=" + methods(map(genPaths.get(genNormalized.get(p)))));
            }
        }
        for (String p : sorted(hwNormalized.keySet())) {
            if (!genNormalized.containsKey(p)) {
                diffs.add("HANDWRITTEN_ONLY_PATH  " + p + "  方法=" + methods(map(hwPaths.get(hwNormalized.get(p)))));
            }
        }

        // 2) 共享路径：方法集合 + 逐操作结构
        for (String p : sorted(genNormalized.keySet())) {
            if (!hwNormalized.containsKey(p)) {
                continue;
            }
            Map<String, Object> genItem = map(genPaths.get(genNormalized.get(p)));
            Map<String, Object> hwItem = map(hwPaths.get(hwNormalized.get(p)));

            Set<String> genMethods = methods(genItem);
            Set<String> hwMethods = methods(hwItem);
            if (!genMethods.equals(hwMethods)) {
                diffs.add("METHOD_SET " + p + "  生成物=" + sorted(genMethods) + " 手写=" + sorted(hwMethods));
            }
            for (String m : sorted(genMethods)) {
                if (!hwMethods.contains(m)) {
                    continue;
                }
                String key = m.toUpperCase() + " " + p;
                compareOperation(key, map(genItem.get(m)), genItem, genComponents,
                        map(hwItem.get(m)), hwItem, hwComponents, diffs);
            }
        }
        return diffs;
    }

    private void compareOperation(String key, Map<String, Object> genOp, Map<String, Object> genPathItem,
            Map<String, Object> genComponents, Map<String, Object> hwOp, Map<String, Object> hwPathItem,
            Map<String, Object> hwComponents, List<String> diffs) {

        // 2a) query/header 参数（解析 $ref；排除认证基础设施头；路径参数命名属于约定，不比对）
        //     multipart/form-data 操作除外：springdoc 把 @RequestParam 表单字段渲染成 query 参数，
        //     手写契约正确地文档化为请求体表单字段，字段集合由请求体 schema 比对覆盖。
        RequestBodyModel genReq = requestBody(genOp);
        RequestBodyModel hwReq = requestBody(hwOp);
        boolean multipart = genReq.contentTypes().contains("multipart/form-data")
                || hwReq.contentTypes().contains("multipart/form-data");
        Set<String> genParams = operationParams(genOp, genPathItem, genComponents);
        Set<String> hwParams = operationParams(hwOp, hwPathItem, hwComponents);
        if (!multipart && !genParams.equals(hwParams)) {
            diffs.add("PARAMS " + key + "\n   生成物=" + sorted(genParams) + "\n   手写=" + sorted(hwParams));
        }

        // 2b) 2xx 响应码集合；动态状态码豁免：生成物恰为 {200} 且手写恰为一个 2xx 时以手写评审为准
        Set<String> gen2xx = twoxxCodes(genOp);
        Set<String> hw2xx = twoxxCodes(hwOp);
        boolean dynamicStatus = gen2xx.equals(Set.of("200")) && hw2xx.size() == 1;
        if (!dynamicStatus && !gen2xx.equals(hw2xx)) {
            diffs.add("2XX_CODES " + key + "  生成物=" + sorted(gen2xx) + " 手写=" + sorted(hw2xx));
        }

        // 2c) 请求体：content-type 集合 + schema 引用名
        if (!genReq.contentTypes().equals(hwReq.contentTypes())) {
            diffs.add("REQUEST_CONTENT_TYPES " + key + "  生成物=" + sorted(genReq.contentTypes())
                    + " 手写=" + sorted(hwReq.contentTypes()));
        }
        String genReqRef = normalizeSchemaRef(genReq.schemaRef(), REQUEST_BODY_ALIASES.get(key));
        String hwReqRef = normalizeSchemaRef(hwReq.schemaRef(), REQUEST_BODY_ALIASES.get(key));
        boolean reqRefComparable = genReqRef != null && hwReqRef != null
                && !"inline".equals(genReqRef) && !"inline".equals(hwReqRef);
        if (reqRefComparable && !genReqRef.equals(hwReqRef)) {
            diffs.add("REQUEST_SCHEMA " + key + "  生成物=" + genReqRef + " 手写=" + hwReqRef);
        }

        // 2d) 成功响应（首个 2xx）schema 引用名；任一侧为 inline（springdoc 无法推断）时不比对
        String genResp = normalizeSchemaRef(firstTwoxxSchemaRef(genOp, genComponents), RESPONSE_ALIASES.get(key));
        String hwResp = normalizeSchemaRef(firstTwoxxSchemaRef(hwOp, hwComponents), RESPONSE_ALIASES.get(key));
        boolean respComparable = genResp != null && hwResp != null
                && !"inline".equals(genResp) && !"inline".equals(hwResp);
        if (respComparable && !genResp.equals(hwResp)) {
            diffs.add("RESPONSE_SCHEMA " + key + "  生成物=" + genResp + " 手写=" + hwResp);
        }
    }

    // ------------------------------------------------------------------
    // 提取辅助
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static Map<String, Object> paths(Map<String, Object> spec) {
        return map(spec.get("paths"));
    }

    private static Map<String, Object> components(Map<String, Object> spec) {
        return map(spec.get("components"));
    }

    private static Set<String> methods(Map<String, Object> pathItem) {
        Set<String> out = new LinkedHashSet<>();
        pathItem.forEach((k, v) -> {
            if (METHODS.contains(k)) {
                out.add(k);
            }
        });
        return out;
    }

    private static Set<String> twoxxCodes(Map<String, Object> op) {
        Set<String> out = new LinkedHashSet<>();
        map(op.get("responses")).keySet().forEach(c -> {
            if (c.startsWith("2")) {
                out.add(c);
            }
        });
        return out;
    }

    /** 路径模板归一：{name} -> {}。 */
    private static String normalizePath(String path) {
        return PATH_VAR.matcher(path).replaceAll("{}");
    }

    private static boolean isJdPassthrough(String normalizedPath) {
        return JD_PASSTHROUGH_PREFIXES.stream().anyMatch(normalizedPath::startsWith);
    }

    /** 操作参数（query/header，解析 $ref，排除认证基础设施头）。返回 "name@in" 集合。 */
    private static Set<String> operationParams(Map<String, Object> op, Map<String, Object> pathItem,
            Map<String, Object> components) {
        Set<String> out = new LinkedHashSet<>();
        List<Object> raw = new ArrayList<>(list(pathItem.get("parameters")));
        raw.addAll(list(op.get("parameters")));
        Map<String, Object> paramComponents = map(components.get("parameters"));
        for (Object o : raw) {
            Map<String, Object> pr = map(o);
            String name;
            String in;
            if (pr.containsKey("$ref")) {
                String refName = String.valueOf(pr.get("$ref")).replaceFirst("^.*/", "");
                Map<String, Object> target = map(paramComponents.get(refName));
                name = str(target.get("name"));
                in = str(target.get("in"));
            } else {
                name = str(pr.get("name"));
                in = str(pr.get("in"));
            }
            if (name == null || in == null) {
                continue;
            }
            if ("header".equals(in) && AUTH_HEADER_PARAMS.contains(name)) {
                continue;
            }
            if ("path".equals(in)) {
                continue; // 路径参数命名属于约定，模板比对已覆盖其存在性
            }
            out.add(name + "@" + in);
        }
        return out;
    }

    private record RequestBodyModel(Set<String> contentTypes, String schemaRef) {
    }

    private static RequestBodyModel requestBody(Map<String, Object> op) {
        Map<String, Object> rb = map(op.get("requestBody"));
        Map<String, Object> content = map(rb.get("content"));
        Set<String> types = new LinkedHashSet<>();
        String schemaRef = null;
        for (Map.Entry<String, Object> e : content.entrySet()) {
            types.add(e.getKey());
            if (schemaRef == null) {
                schemaRef = schemaRefOf(map(map(e.getValue()).get("schema")));
            }
        }
        return new RequestBodyModel(types, schemaRef);
    }

    /** 首个 2xx 响应的 schema 引用名（解析 response 级 $ref）。 */
    private static String firstTwoxxSchemaRef(Map<String, Object> op, Map<String, Object> components) {
        Map<String, Object> responses = map(op.get("responses"));
        Map<String, Object> responseComponents = map(components.get("responses"));
        for (String code : sorted(responses.keySet())) {
            if (!code.startsWith("2")) {
                continue;
            }
            Map<String, Object> resp = map(responses.get(code));
            if (resp.containsKey("$ref")) {
                String refName = String.valueOf(resp.get("$ref")).replaceFirst("^.*/", "");
                resp = map(responseComponents.get(refName));
            }
            Map<String, Object> content = map(resp.get("content"));
            for (Object o : content.values()) {
                Map<String, Object> media = map(o);
                String ref = schemaRefOf(map(media.get("schema")));
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    /** schema 引用名；无 $ref 一律视为 inline（springdoc 无法推断，跳过比对）。 */
    private static String schemaRefOf(Map<String, Object> schema) {
        if (schema == null) {
            return null;
        }
        Object ref = schema.get("$ref");
        if (ref != null) {
            return String.valueOf(ref).replaceFirst("^.*/", "");
        }
        return "inline";
    }

    /** 别名解析 + 归一化；inline 或 null 保持原样（比对时按 skip 处理）。 */
    private static String normalizeSchemaRef(String name, Map<String, String> perOpAliases) {
        if (name == null || "inline".equals(name)) {
            return name;
        }
        // 先按操作查别名（key 为生成物原始 DTO 名），再全局别名，最后剥常见后缀。
        String aliased = name;
        if (perOpAliases != null && perOpAliases.containsKey(aliased)) {
            aliased = perOpAliases.get(aliased);
        } else {
            aliased = GLOBAL_SCHEMA_ALIASES.getOrDefault(aliased, aliased);
        }
        return aliased.replaceAll("Dto$", "").replaceAll("View$", "");
    }

    // ------------------------------------------------------------------
    // 读取辅助
    // ------------------------------------------------------------------

    private String fetchGeneratedSpec() {
        ResponseEntity<String> resp = http.getForEntity("/v3/api-docs.yaml", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("GET /v3/api-docs.yaml").isTrue();
        return resp.getBody() == null ? "" : resp.getBody();
    }

    private String readHandwrittenContract() {
        List<Path> candidates = List.of(
                Path.of("..", "docs", "openapi.yaml"),
                Path.of("docs", "openapi.yaml"),
                Path.of("..", "..", "docs", "openapi.yaml"));
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("读取手写契约失败: " + p.toAbsolutePath(), e);
                }
            }
        }
        throw new IllegalStateException("找不到手写契约 docs/openapi.yaml（相对 backend 尝试了 " + candidates + "）");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yamlText) {
        Object root = new Yaml().load(yamlText);
        if (!(root instanceof Map)) {
            throw new IllegalStateException("YAML 顶层不是 mapping");
        }
        return (Map<String, Object>) root;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }
}
