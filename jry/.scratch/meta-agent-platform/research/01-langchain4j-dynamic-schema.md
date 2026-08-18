# 01 — LangChain4j 动态输出 schema 调研（详细版）

> 票：`.scratch/meta-agent-platform/issues/01-langchain4j-dynamic-schema.md`
> 调研日期：以本仓库为准（环境时间线）
> 证据来源：本地 `~/.m2` jar 反编译（`javap` / `unzip -p`）、LangChain4j 官方文档、DeepSeek 官方 API 文档、社区一手 issue/PR。

---

## 0. 结论速览

1. **版本事实**：`backend/pom.xml` 的 `langchain4j-bom` 固定为 **1.19.0**；本地 `~/.m2/repository/dev/langchain4j/` 下 `langchain4j`、`langchain4j-core`、`langchain4j-open-ai` 均为 1.19.0（已核实）。
2. **问题 a**：**AiServices 的结构化输出确实绑定静态接口方法返回类型（record/POJO），1.19.0 没有公开 API 注入动态 JSON Schema**。但**核心请求层完全支持运行时动态 schema**：`ChatRequest.builder().responseFormat(ResponseFormat(JSON, JsonSchema))` + `ChatModel.chat(ChatRequest)`，且 `JsonRawSchema.from(String)` 可直接吃"定义里携带的 JSON schema 字符串"。官方 Structured Outputs 教程即用这条低层路径演示，不依赖接口/record。
3. **问题 b**：`langchain4j-open-ai` 1.19.0 完整支持 `response_format` 两种模式：`json_object`（`ResponseFormat` 不带 schema）与 `json_schema`（带 `JsonSchema`），映射在 `OpenAiUtils.toOpenAiResponseFormat`。**DeepSeek（api.deepseek.com 兼容端点）官方只文档化 `json_object`；社区一手证据表明发 `json_schema` 会被 DeepSeek 以 HTTP 400 `"This response_format type is unavailable now"` 拒绝**（crewAI issue，官方 PR 对 DeepSeek 直接剥离 json_schema）。OpenAI 原生自 2024-08 起支持 `json_schema`（strict structured outputs）。
4. **问题 c（推荐）**：**以 b) 为主** —— schema 数据化后，运行时按供应商能力表构造 response_format：支持 `json_schema` 的供应商（OpenAI 原生）传动态 schema（`JsonRawSchema`），DeepSeek 等降级 `json_object`；**无论哪种模式，客户端一律做 JSON Schema 校验兜底**（兼容端点即使接受 json_schema 也不保证严格）。输出统一为通用 JsonNode/Map 容器（吸收 c 的输出形态），不再为每个 Agent 定义静态 record gateway；`AgentStructuredOutput` 保留为通用信封/兼容层。**a) 运行时生成 record 不做**（字节码/动态编译成本高、收益低，仅当业务强类型消费时才值得）。

---

## 1. 版本事实（证据）

```xml
<!-- backend/pom.xml -->
<dependencyManagement>
  <dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-bom</artifactId>
    <version>1.19.0</version>
    <type>pom</type>
    <scope>import</scope>
  </dependency>
</dependencyManagement>
```

本地 m2（`ls ~/.m2/repository/dev/langchain4j/`）：

```
langchain4j / langchain4j-bom / langchain4j-core / langchain4j-http-client /
langchain4j-http-client-jdk / langchain4j-open-ai
langchain4j/1.19.0、langchain4j-core/1.19.0、langchain4j-open-ai/1.19.0
```

职责划分（与结论相关）：
- `langchain4j-core-1.19.0.jar`：`ChatRequest` / `ChatRequestParameters` / `ResponseFormat` / `JsonSchema` 等**provider 无关请求层**。
- `langchain4j-1.19.0.jar`：`AiServices` / `AiServiceContext` / `ServiceOutputParser` / `JsonSchemas` / `DefaultOutputParserFactory` 等 **AiServices 层**。
- `langchain4j-open-ai-1.19.0.jar`：`OpenAiChatModel` / `OpenAiChatRequestParameters` / `OpenAiUtils`（response_format 映射）。

---

## 2. 问题 a：AiServices 结构化输出是否必须静态接口 + record？能否动态？

### 2.1 AiServices 结构化输出机制（字节码证据，1.19.0）

`dev.langchain4j.service.DefaultAiServices$1`（动态代理调用处理器）的调用链：

1. 取接口方法返回类型 `Type` → `ServiceOutputParser.jsonSchema(Type)` → `Optional<JsonSchema>`。
2. `JsonSchemas.jsonSchemaFrom(Type)` 内部（`javap -c dev.langchain4j.service.output.JsonSchemas`）：
   - `isPojo(Type)` 为真时才生成 schema；
   - `JsonSchema.builder().name(class.getSimpleName()).rootElement(JsonSchemaElementUtils.jsonObjectOrReferenceSchemaFrom(Class, ...))` —— **schema 由返回类型的 Class 反射字段生成**。
3. 若 schema 存在：`ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(schema).build()` → `AiServiceParamsUtil.chatRequestParameters(Method, args, toolContext, responseFormat)` → 写入 `ChatRequest.parameters(...)`。
4. 响应解析：`ServiceOutputParser` → `DefaultOutputParserFactory`：
   - 内置解析器表（Enum/List/Set 等）；
   - 兜底 `new PojoOutputParser(Class)`（Jackson 反序列化为返回类型的 Class）。
   - 解析失败抛 `dev.langchain4j.service.output.OutputParsingException` —— 正是现有 `AgentGateway`（返回 `Result<AgentStructuredOutput>`）由 `LangChain4jAgentRuntime` 映射为 `AGENT_OUTPUT_INVALID` 的那条路径。
5. 能力门：处理器内 `chatModel.supportedCapabilities().contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA)`（`DefaultAiServices$1` 字节码中 `getstatic Capability.RESPONSE_FORMAT_JSON_SCHEMA` + `Set.contains`）；OpenAI 模型默认**不含**该能力，除非 builder 显式配置（见 3.1）。

### 2.2 AiServices 公开 API 里没有"动态 schema"入口

`javap dev.langchain4j.service.AiServices`（1.19.0，全量方法列表）中与输出格式相关的只有：`chatRequestTransformer(UnaryOperator<ChatRequest>)` / `chatRequestTransformer(BiFunction<ChatRequest,Object,ChatRequest>)`；**没有** `outputFormat(...)` / `outputJsonSchema(...)` 之类的方法。

`AiServiceContext`（public 字段）：`public java.lang.Class<?> returnType` —— 输出类型唯一来源。变通（不推荐作为产品方案）：
- 改 `context.returnType`：仅影响解析目标 Class，**无法注入任意 schema**；
- `chatRequestTransformer` 可在请求发出前改写 `responseFormat`（`request.toBuilder().responseFormat(dynamic)`），但**输出解析仍绑定 returnType**——动态 schema 与返回类型必须结构一致，否则解析失败。只适合"schema 与固定 record 1:1"的存量场景，不解决"任意动态 schema"。

### 2.3 结论 a

- **AiServices 结构化输出 = 静态接口方法签名 + record/POJO 返回类型**（反射生成 schema、反射解析），1.19.0 无公开动态 schema API —— 是。
- **运行时按动态 JSON schema 约束输出：可以，但在低层 `ChatRequest` 层**，不在 AiServices 层：

```java
// 官方 Structured Outputs 教程（docs.langchain4j.dev/tutorials/structured-outputs/）原样思路
ResponseFormat responseFormat = ResponseFormat.builder()
        .type(ResponseFormatType.JSON)          // TEXT 或 JSON
        .jsonSchema(JsonSchema.builder()
                .name("Person")                 // OpenAI 要求 schema 必须有 name
                .rootElement(JsonObjectSchema.builder()
                        .addStringProperty("name")
                        .addIntegerProperty("age")
                        .required("name", "age")
                        .build())
                .build())
        .build();
ChatRequest request = ChatRequest.builder()
        .messages(userMessage)
        .responseFormat(responseFormat)
        .build();
ChatResponse response = chatModel.chat(request);
String json = response.aiMessage().text();      // 模型输出为原始 JSON 文本，自行反序列化
```

**动态性要点**：`JsonSchemaElement` 的根元素可以是 `JsonObjectSchema`（编程构建）或 **`JsonRawSchema.from(String)`**（直接解析 JSON schema 字符串，1.19.0 存在，见 3.1）——"Agent 定义里携带的 JSON schema"可以直接字符串灌入，无需任何编译期类型。

---

## 3. 问题 b：langchain4j-open-ai 的 response_format 支持

### 3.1 API 证据（1.19.0，javap 核实）

**core 包（langchain4j-core-1.19.0.jar）**

```java
// dev.langchain4j.model.chat.request.ResponseFormat
public class ResponseFormat {
    public static final ResponseFormat TEXT;
    public static final ResponseFormat JSON;
    public ResponseFormatType type();
    public JsonSchema jsonSchema();
    public static ResponseFormat.Builder builder();   // .type(ResponseFormatType) .jsonSchema(JsonSchema) .build()
}

// dev.langchain4j.model.chat.request.ResponseFormatType（枚举：只有两个值，json_schema 模式由"携带 jsonSchema"决定）
public enum ResponseFormatType { TEXT, JSON }

// dev.langchain4j.model.chat.request.json.JsonSchema
public class JsonSchema {
    public String name();
    public JsonSchemaElement rootElement();
    public static JsonSchema.Builder builder();       // .name(String) .rootElement(JsonSchemaElement) .build()
}

// dev.langchain4j.model.chat.request.json.JsonRawSchema —— 动态 schema 关键入口
public class JsonRawSchema implements JsonSchemaElement {
    public static JsonRawSchema from(String schema);  // 直接吃原始 JSON schema 字符串
    public String schema();
    // builder: .schema(String) .build()
}

// 其他元素：JsonObjectSchema(.properties/.required/.additionalProperties/.definitions)、
// JsonStringSchema / JsonIntegerSchema / JsonNumberSchema / JsonBooleanSchema / JsonEnumSchema /
// JsonArraySchema / JsonAnyOfSchema / JsonReferenceSchema / JsonNullSchema

// dev.langchain4j.model.chat.request.ChatRequest$Builder
public ChatRequest.Builder responseFormat(ResponseFormat);

// dev.langchain4j.model.chat.request.DefaultChatRequestParameters$Builder
public T responseFormat(ResponseFormat);
public T responseFormat(JsonSchema);   // 便捷方法 = ResponseFormat(type=JSON, jsonSchema=...)（字节码已确认）
```

**openai 包（langchain4j-open-ai-1.19.0.jar）**

```java
// dev.langchain4j.model.openai.OpenAiChatRequestParameters extends DefaultChatRequestParameters
//   —— 继承 responseFormat(...) 设置；builder 继承 DefaultChatRequestParameters$Builder

// dev.langchain4j.model.openai.OpenAiChatModel$OpenAiChatModelBuilder
public OpenAiChatModelBuilder responseFormat(ResponseFormat);
public OpenAiChatModelBuilder responseFormat(String);     // "json_object" / "json_schema"（记录到 responseFormatString）
public OpenAiChatModelBuilder strictJsonSchema(Boolean);  // OpenAI strict 模式（additionalProperties:false 等）
public OpenAiChatModelBuilder supportedCapabilities(Capability...);  // 含 RESPONSE_FORMAT_JSON_SCHEMA

// dev.langchain4j.model.chat.Capability（core）
public enum Capability { RESPONSE_FORMAT_JSON_SCHEMA }
```

**response_format 序列化映射**（`OpenAiUtils.toOpenAiResponseFormat(ResponseFormat, Boolean strict)` 反编译）：

| 输入 | 发送到端点的 `response_format` |
|---|---|
| `null` 或 `type==TEXT` | 不发送 |
| `type==JSON` 且 `jsonSchema==null` | `{"type": "json_object"}` |
| `type==JSON` 且 root 为 `JsonObjectSchema` / `JsonRawSchema` | `{"type": "json_schema", "json_schema": {"name": ..., "strict": ..., "schema": <由 JsonSchemaElementUtils.toMap 生成>}}` |
| root 不是 object 型 | 抛 `IllegalArgumentException` |

- `JsonSchemaElementUtils.toMap` 对 `JsonRawSchema` 分支：取 `JsonRawSchema.schema()` 字符串并按 JSON 解析为 Map（字节码确认 `instanceof JsonRawSchema` → `getSchema()`）。
- `OpenAiChatModel.supportedCapabilities()`：`responseFormatString.equals("json_schema")` 时才把 `RESPONSE_FORMAT_JSON_SCHEMA` 并入返回集。
- **实现注意**：`OpenAiChatModel.doChat` 会把 `ChatRequest.parameters()` 强转 `OpenAiChatRequestParameters`（字节码 `checkcast`）。所以自定义请求参数必须用 `OpenAiChatRequestParameters.builder()...build()`，传裸 `DefaultChatRequestParameters` 会 `ClassCastException`。

**版本口径**：本仓库仅锁定 1.19.0；`ResponseFormat`/`JsonSchema`/`responseFormat(...)` 是 1.19.0 已稳定的 provider 无关 API（官方教程即用）。更早版本的 API 演进以官方 CHANGELOG 为准（<https://github.com/langchain4j/langchain4j/blob/main/CHANGELOG.md>）。

### 3.2 DeepSeek 等 OpenAI 兼容端点是否支持 json_schema

**官方文档（DeepSeek API Docs — JSON Output）**：<https://api-docs.deepseek.com/guides/json_mode/>

- 仅文档化 `response_format = {'type': 'json_object'}`，并要求：提示词里含 "json" 字样、给出 JSON 示例、合理设置 `max_tokens`。
- **官方文档未提供 `json_schema` 模式**（当前页面全文检索不到 json_schema 用法）。

**社区一手证据（DeepSeek 兼容端点拒绝 json_schema）**：

- crewAI 官方 issue（复现于 `deepseek/deepseek-v4-pro` + `base_url=https://api.deepseek.com`）：
  - 发送 `response_format` 的 json_schema 变体 → `Error code: 400 - {'error': {'message': 'This response_format type is unavailable now', ...}}`。
  - 修复 PR：*fix: strip json_schema response_format for DeepSeek and other unsupported providers*（<https://github.com/crewAIInc/crewAI/pull/5991>），即 DeepSeek 一律剥离 json_schema。
- DeepSeek-V3.2 宣传的"结构化输出"提升指模型遵循 schema 的**生成能力**，不等同于端点原生支持 `response_format.type=json_schema`。

**对照：OpenAI 原生**：自 2024-08 起支持 `response_format: {type: "json_schema", json_schema: {...}}`（structured outputs，含 strict 模式）：<https://platform.openai.com/docs/guides/structured-outputs>。

### 3.3 结论 b

- `langchain4j-open-ai` 1.19.0：`json_object` ✅（`ResponseFormat.JSON` 无 schema）；`json_schema` ✅（`ResponseFormat(JSON, JsonSchema)`，含 `JsonRawSchema` + strict）。
- DeepSeek 兼容端点：**只支持 `json_object`，不支持 `json_schema`**（官方未文档化 + 社区 400 证据）。运行时必须按供应商能力降级。

---

## 4. 问题 c：候选路径比较与推荐

### 4.1 三条路径比较

| 路径 | 实现成本 | 运行时动态性 | 输出保证 | 风险/代价 |
|---|---|---|---|---|
| **a) 运行时生成 record**（ByteBuddy/ASM 字节码或 `javax.tools` 动态编译） | 高：类加载器管理、schema↔record 双维护、Jackson 绑定动态类、每 Agent 缓存 | 完全动态 | 强类型、编译期友好 | 复杂度高；最终消费方仍多半用反射/泛型访问，收益低；版本升级（如 Java 记录序列化）易碎 |
| **b) json_object + 运行时 JSON Schema 校验**（扩展 `AgentStructuredOutput` 思路） | 中：接入一个 JSON Schema 校验器（如 `com.networknt:json-schema-validator` / `com.github.erosb:everit-json-schema`），schema 本身数据化 | 完全动态 | 校验失败明确报错 → 复用 `AGENT_OUTPUT_INVALID` 错误映射 | 校验器依赖一个；DeepSeek 的 json_object 非严格（需 prompt 里含 "json" 提示，官方要求） |
| **c) JsonNode/Map 输出 + 手动校验** | 低：无需新依赖 | 完全动态 | 仅"是合法 JSON"级保证，schema 约束靠手写 | schema 演进要改代码，**丢失现有 AiServices 提供的 schema 级保证**，不推荐单独采用 |

补充：路径 b 在 OpenAI 原生上可**升级**为动态 `json_schema`（约束更强，`strictJsonSchema(true)` 时 OpenAI 保证 100% 合法），DeepSeek 等降级 `json_object`；二者之上统一再做客户端校验（兼容端点即使接受 json_schema 也不保证严格）——即 b 是"供应商能力自适应 + 校验兜底"。

### 4.2 推荐（b 为主，schema-first）

1. **输出约束走低层 `ChatModel.chat(ChatRequest)`**（不再为每个 Agent 建静态 gateway）：
   - 支持 `json_schema` 的供应商：`JsonRawSchema.from(定义.schema)` → `JsonSchema.builder().name(定义.schemaName).rootElement(raw).build()` → `ResponseFormat(JSON, jsonSchema)`；
   - DeepSeek 等：`ResponseFormat.JSON`（json_object）；
   - 参数用 `OpenAiChatRequestParameters.builder().responseFormat(...).build()`（强转约束）。
2. **统一输出容器**：`JsonNode`/`Map`（吸收路径 c 的输出形态），`AgentStructuredOutput` 保留为通用信封/兼容层；不再为业务 Agent 定义专属 record gateway。
3. **客户端 JSON Schema 校验兜底**：新增校验器依赖；校验失败映射 `AGENT_OUTPUT_INVALID`（与现有错误语义一致）。校验器选择：`com.networknt:json-schema-validator`（活跃、支持 draft-07/2020-12）优先。
4. **供应商能力表**：`json_schema` 支持与否入 `AgentModelProperties`/能力注册表（openai → json_schema+strict；deepseek → json_object；其余 OpenAI 兼容 → json_object 默认）。
5. **a) 不做**，留作将来"确需 Java 强类型消费"时的演进选项；**c) 不单独采用**（无 schema 校验，丢失现有保证）。

---

## 5. 证据来源与参考链接

**本地 jar（~/.m2，反编译核实的类/方法）**
- `~/.m2/repository/dev/langchain4j/langchain4j-core/1.19.0/langchain4j-core-1.19.0.jar`
- `~/.m2/repository/dev/langchain4j/langchain4j/1.19.0/langchain4j-1.19.0.jar`
- `~/.m2/repository/dev/langchain4j/langchain4j-open-ai/1.19.0/langchain4j-open-ai-1.19.0.jar`

**官方文档**
- LangChain4j — Structured Outputs 教程（低层 ResponseFormat/JsonSchema + AiServices record 两种用法）：<https://docs.langchain4j.dev/tutorials/structured-outputs/>
- LangChain4j CHANGELOG：<https://github.com/langchain4j/langchain4j/blob/main/CHANGELOG.md>
- DeepSeek API Docs — JSON Output（仅 json_object）：<https://api-docs.deepseek.com/guides/json_mode/>
- OpenAI — Structured Outputs（json_schema，2024-08 起）：<https://platform.openai.com/docs/guides/structured-outputs>

**社区一手证据**
- crewAI：DeepSeek 拒绝 json_schema 的 400 错误 + 修复 PR（strip json_schema for DeepSeek）：<https://github.com/crewAIInc/crewAI/pull/5991>（issue 镜像：<https://www.stepcodex.com/en/issue/bug-openai-api-call-fails-with>）
- DeepSeek-V3.2 结构化输出讨论（模型生成能力，非端点 json_schema）：<https://blog.milvus.io/ai-quick-reference/does-deepseekv32-support-structured-json-mode>
