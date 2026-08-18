# 01 — LangChain4j 动态输出 schema 调研

**Type:** research
**Status:** resolved
**Blocked by:** —

## Question

定义数据化后，运行时需按 Agent 定义里携带的 JSON schema 约束模型输出。当前实现是静态 AiServices 接口 + record 类型（`AgentStructuredOutput` / 各业务 Agent 专属 gateway）。调研（查本地 `~/.m2` 的 langchain4j jar 与官方文档/源码，版本见 `backend/pom.xml` 的 langchain4j-bom）：

1. AiServices 结构化输出是否必须静态接口方法签名 + record 类？能否在运行时按动态 JSON schema 约束输出？
2. `langchain4j-open-ai` 对 `response_format` 的支持：`json_object` / `json_schema` 各版本 API（类名、方法签名）；DeepSeek 等 OpenAI 兼容端点是否支持 `json_schema` 模式？
3. 若不支持动态 schema，给出候选路径并比较：a) 运行时生成 record 类（编译/字节码）；b) 降级 `json_object` + 运行时 JSON schema 校验（扩展 `AgentStructuredOutput` 思路）；c) `JsonNode`/`Map` 输出 + 手动校验。
4. 结论必须带证据：版本号、类名、API 签名、官方链接。

输出：结论写为 `## Answer` 追加到本票文件并置 `Status: resolved`；详细调研文档写 `.scratch/meta-agent-platform/research/01-langchain4j-dynamic-schema.md`。不要运行任何 git 命令（分支整理由主 session 做）。

## Answer

**版本**：`backend/pom.xml` 的 `langchain4j-bom` = **1.19.0**（本地 `~/.m2` 的 `langchain4j` / `langchain4j-core` / `langchain4j-open-ai` 均为 1.19.0，已用 javap/unzip 核实）。

**a) AiServices 结构化输出是否必须静态接口 + record，能否动态？**
- 是：AiServices 的输出 schema 由**接口方法返回类型（record/POJO）反射生成**（`DefaultAiServices$1` → `ServiceOutputParser.jsonSchema(Type)` → `JsonSchemas.jsonSchemaFrom(Type)` → `ResponseFormat(type=JSON, jsonSchema=...)`），解析由 `PojoOutputParser` 反射完成；1.19.0 的 `AiServices` 公开 API **没有任何动态 schema 入口**（`AiServiceContext.returnType` 可变、`chatRequestTransformer` 可改写请求，但解析仍绑定返回类型，属变通）。
- 但**低层完全支持动态**：`ChatRequest.builder().responseFormat(ResponseFormat(JSON, JsonSchema))` + `ChatModel.chat(ChatRequest)`；关键入口 `JsonRawSchema.from(String)` 可直接吃定义携带的 JSON schema 字符串。官方教程即用此路径演示，不依赖接口/record。

**b) langchain4j-open-ai 的 response_format 支持（1.19.0）**
- `json_object` ✅：`ResponseFormat.JSON`（无 schema）→ `OpenAiUtils.toOpenAiResponseFormat` 映射 `{"type":"json_object"}`。
- `json_schema` ✅：`ResponseFormat(JSON, JsonSchema)`（root 须为 `JsonObjectSchema`/`JsonRawSchema`）→ 映射 `{"type":"json_schema","json_schema":{name,strict,schema}}`；模型侧 `OpenAiChatModel.builder().responseFormat(ResponseFormat)`、`responseFormat(String)`、`strictJsonSchema(Boolean)`、`supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)`。注意：自定义请求参数必须用 `OpenAiChatRequestParameters.builder()`（`OpenAiChatModel.doChat` 会 checkcast）。
- **DeepSeek 不支持 json_schema**：官方 JSON Output 文档仅 `{'type':'json_object'}`（api-docs.deepseek.com/guides/json_mode/）；社区一手证据——向 api.deepseek.com 发 json_schema 返回 400 `"This response_format type is unavailable now"`（crewAI issue，官方 PR #5991 对 DeepSeek 剥离 json_schema）。OpenAI 原生自 2024-08 起支持 json_schema + strict。

**c) 推荐：b) json_object + 运行时 JSON Schema 校验（schema-first，供应商能力自适应）**
- 运行时用低层 `ChatModel.chat(ChatRequest)`：支持 json_schema 的供应商（OpenAI 原生）传 `JsonRawSchema` 动态 schema（可加 strict）；DeepSeek 等降级 `ResponseFormat.JSON`（json_object）。
- 输出统一为 JsonNode/Map 容器（吸收 c 的输出形态），`AgentStructuredOutput` 保留为通用信封/兼容层；**客户端一律做 JSON Schema 校验兜底**（新增 networknt 校验器），失败映射 `AGENT_OUTPUT_INVALID`（沿用现有错误语义）。
- 供应商 json_schema 能力入 `AgentModelProperties`/能力注册表；a) 运行时生成 record 不做（成本高、收益低，仅将来确需 Java 强类型时再议）；c) 不单独采用（无 schema 校验，丢失现有保证）。

**详细证据（类名、API 签名、字节码映射逻辑、文档链接）见** `.scratch/meta-agent-platform/research/01-langchain4j-dynamic-schema.md`。
