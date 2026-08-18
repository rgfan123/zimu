# 01 — LangChain4j 基础接入

**What to build:** 在 Spring Boot 单体中引入 LangChain4j 作为 Agent 运行时基础：依赖、OpenAI 兼容模型配置、结构化输出与 fail-closed 默认，并沿用现有模型元数据治理模式。

**Blocked by:** 无

**Status:** resolved

## 范围

- `pom.xml` 增加 `langchain4j` BOM（核心 + `langchain4j-open-ai`，OpenAI 兼容协议对接 DeepSeek 等现有供应商）。
- 新增 `app.agent.*` 配置（`base-url` / `api-key` / `model` / `provider` / `request-timeout-ms`），参考 `app.message-interpreter.*` 的互斥注册模式（`MessageInterpreterConfiguration`）：未配置时注册 fail-closed 的 Default Agent 工厂，不连接任何模型。
- 结构化输出能力就绪（AI Services 或结构化输出记录），保证 Agent 输出可 schema 校验。
- 模型元数据治理：将 `MessageModelMetadataRegistry` 的服务端 allowlist 模式（provider/model/prompt-version 白名单投影）扩展到 Agent 运行元数据，Agent 结果公共投影默认 `none/none/none`，白名单命中才暴露真实值。
- 新增 `AgentModelProperties` / 配置 bean，不改动既有 `app.message-interpreter.*` 行为。

## 非范围

- Agent 定义与注册表（02 票）；
- Agent 工具绑定（03 票）；
- 任何业务 Agent。

## 验收标准

- [ ] `mvn -DskipTests test-compile` 通过，无新依赖冲突；
- [ ] 配置了 `app.agent.*` 时模型可调用（结构化输出 roundtrip 测试，本地可用 mock 或跳过真实外呼）；
- [ ] 未配置时应用正常启动，Agent 运行时显式失败（fail-closed），不影响既有企微/复核用例；
- [ ] 模型元数据投影遵循 allowlist：未白名单的 provider/model/prompt-version 一律投影为 `none`；
- [ ] 切换供应商/模型只需改配置，不修改 Agent 业务代码；
- [ ] 全部既有测试（如 `mvn test` 现有套件）保持绿。

## 验证原则

- 不以“模型回答看起来正确”作为唯一验收；
- 关键行为（fail-closed、元数据投影、配置切换）必须有自动化测试。

## Comments

- 不引入 LangGraph / Python sidecar；LangChain4j 是 Java 一等公民，与现有单体同进程。

## Answer

**状态：** 已收尾（上一个 subagent 被打断后由本 subagent 修复+补齐+验证）。

### 交付内容

- `backend/pom.xml`：`langchain4j-bom` 1.19.0 + `langchain4j` + `langchain4j-open-ai`（BOM 统一版本，无版本冲突）。
- `backend/src/main/java/cn/zimu/fulfillment/agent/`（11 个类，全部保留既有结构收尾）：
  - `AgentRuntime` / `DefaultAgentRuntime` / `LangChain4jAgentRuntime` / `AgentRuntimeConfiguration`：互斥注册（base-url 非空 → LangChain4j 真实模型；空 → fail-closed 兜底），fail-closed 返回 `AGENT_MODEL_NOT_CONFIGURED` + none/none/none，不连接任何模型。
  - `AgentGateway`（AiServices 结构化输出，package-private 接口）+ `AgentStructuredOutput`（`summary`/`reasoning` 最小 schema）：模型输出不可解析 → `AGENT_OUTPUT_INVALID`，HTTP/网络失败 → `AGENT_MODEL_CALL_FAILED`，api-key 只进传输头、不进结果/异常。
  - `AgentModelProperties`（`app.agent.*` 传输四元组 + `configured()`，不改动 `app.message-interpreter.*`）。
  - `AgentModelMetadataRegistry`：对照 `MessageModelMetadataRegistry` 语义完整实现 allowlist 投影（`allows`/`publicProjection`/别名可发布性校验），未白名单一律折叠 none/none/none。
  - `application.yml`：`app.agent.*` 配置段 + `public-metadata-aliases` 已接线。

### 修复的编译问题

- `dev.langchain4j.openai.OpenAiChatModel` → `dev.langchain4j.model.openai.OpenAiChatModel`（已在 jar 内验证）。
- `dev.langchain4j.service.OutputParsingException` → `dev.langchain4j.service.output.OutputParsingException`（已在 jar 内验证）。
- 修复后 `mvn -q test-compile` 通过，无其他编译错误。

### 测试（backend/src/test/java/cn/zimu/fulfillment/agent/，共 14 个用例全绿）

- `DefaultAgentRuntimeTest`（3）：未配置 fail-closed none/none/none 且无网络调用；已配置但无客户端时显式抛错；失败结果三元组可被 registry 放行。
- `AgentModelMetadataRegistryTest`（5）：登记三元组放行并投影真实值；未登记折叠 none；none 三元组仅带失败码可持久化；不可发布别名被忽略；null 拒绝。
- `LangChain4jAgentRuntimeTest`（6）：JDK HttpServer 本地 stub 结构化输出 roundtrip（请求体含 `"model"` 与用户输入、`Bearer` 头正确）；输出不可解析 → `AGENT_OUTPUT_INVALID`；500 → `AGENT_MODEL_CALL_FAILED` 且不泄漏 key/原始错误；未配置 fail-closed 且 stub 零命中；换 provider/model 只改配置即生效（qwen/qwen-max）；空 userInput 拒绝。

### 全量回归结果

- 新增 agent 测试 14/14 通过。
- `message` 包 15 个测试类 88 用例全部通过（0 fail / 0 error）。
- 全量 `mvn -q test`：537 run / 0 failures / 18 errors / 7 skipped。18 个 error 全部集中在 `ReadOnlyExternalIdempotencyApiTest`（3）与 `BusinessWriteAuthenticationApiTest`（15）的 ApplicationContext 加载失败（根因是 Testcontainers postgres 连接被拒/30s 超时，属单 fork 跑 79 类 + ~20 个容器的资源耗尽，与本次改动无关）：两类的**隔离运行均全绿**（3/3、15/15），其余 519 用例（含全部带容器 message/acceptance/fulfillment API 测试）通过。

### 遗留事项

- 全量单 fork 运行存在 Testcontainers 资源压力（偶发 context 加载失败），建议后续拆 `-DforkCount` 或按包分组 CI 跑，非本票代码问题。
- 结构化输出当前走 AiServices 的「严格 JSON 格式指令 + PojoOutputParser」路径（请求体未带 `response_format: json_schema`）；若后续需要模型侧 schema 强约束，可在 02 票随业务 Agent 记录评估 OpenAI 结构化输出能力。
- 业务 Agent 注册表（02）、工具绑定（03）不在本票范围，`AgentGateway`/`AgentStructuredOutput` 为最小基础 schema，业务记录由 02 票扩展。
