# 真实企微消息解释器（DeepSeek OpenAI 兼容客户端）

Type: feature
Status: ready-for-agent

## Problem Statement

企微长连接已真实接收消息（验收实例 SUBSCRIBED，消息落库正常），但 `DefaultMessageInterpreter` 是占位实现：未配置时每条消息返回 `MODEL_NOT_CONFIGURED`（FAILED + NEED_REVIEW），配置了也直接抛 "未实现模型客户端"。订单提取识别链路（消息 → 提交 → 解释 → 草稿 → 复核 → 成单）中唯一缺失的环节就是真实模型客户端。

## Solution

新增 `DeepSeekMessageInterpreter` 实现既有 `MessageInterpreter` 接缝（@Service 替换默认 Bean），复用仓库惯例（JDK 21 内置 HttpClient，零新依赖——与 wecom-long-connection 的 JDK WebSocket 选择一致）：

- **传输**：OpenAI 兼容 `POST {base-url}/chat/completions`，`response_format={"type":"json_object"}`，DeepSeek 已支持（order-assistant prototype 同款协议，app.py L411/L454 已验证）。
- **配置**（独立前缀，不复用 order-assistant 的 LLM_*，保持接缝自包含）：
  - `app.message-interpreter.base-url` / `MESSAGE_INTERPRETER_BASE_URL`
  - `app.message-interpreter.api-key` / `MESSAGE_INTERPRETER_API_KEY`
  - 沿用既有 `provider` / `model` / `prompt-version`（MESSAGE_INTERPRETER_PROVIDER/MODEL/PROMPT_VERSION）
  - 缺配置时 fail-closed：返回 `MODEL_NOT_CONFIGURED`（与 DefaultMessageInterpreter 现状一致），readiness 语义不变。
- **错误分类**：模型 5xx/网络/超时 → 抛 `RetryableInterpretationFailure`（既有 3 次重试 + 最终 NEED_REVIEW 收口）；4xx（鉴权/模型不存在）→ 非重试终态（`MODEL_CALL_FAILED` 记录，不烧重试）。
- **提示词 v1**（`prompt-version` 关联常量）：意图 6 类（CUSTOMER_ORDER / SUPPLIER_TRACKING / ORDER_CHANGE / ORDER_CANCEL / NON_BUSINESS / NEED_REVIEW）；CUSTOMER_ORDER 输出 receiver/customer/items（product_name/quantity/unit/specification）/address/settlement 原值；SUPPLIER_TRACKING 输出 lines（name/tracking_no/quantity/judgment）；模型不得输出内部 ID。
- **输出边界**：解释器返回 `InterpretationResult`，structuredOutput 只含模型原始 JSON；经既有 `MessageStructuredOutputBoundary.failClosed` 归一（意图枚举校验 + 输出裁剪 + MODEL_OUTPUT_INVALID）。
- **元数据白名单**：真实 provider/model/prompt-version 三元组必须登记进 `public-metadata-aliases`（`MESSAGE_INTERPRETER_PUBLIC_*_ALIAS`），否则 `MessageModelMetadataRegistry` 拒绝持久化/投影折叠为 sentinel——配置文档化 + 测试覆盖。
- **安全**：api-key 只经环境变量注入，绝不进入日志、错误信息、审计 payload 或 DTO；HTTP 客户端不透出 Authorization 头到任何响应。

## User Stories

1. 作为订单运营人员，我希望企微文字订单消息被自动识别为 CUSTOMER_ORDER 并生成草稿，而不是全部落 NEED_REVIEW。
2. 作为履约运营人员，我希望第三方回传消息被自动识别为 SUPPLIER_TRACKING 并生成逐行运单草稿。
3. 作为审计人员，我希望解释记录保存真实 provider/model/prompt-version（白名单登记），失败时保留错误码。
4. 作为系统管理员，我希望模型密钥只通过环境变量注入，页面、日志与接口响应都看不到。
5. 作为系统管理员，我希望模型临时不可用时消息进入既有重试与 NEED_REVIEW 收口，而不是静默丢失。
6. 作为系统管理员，我希望更换模型只改配置不改业务代码（MessageInterpreter 接缝契约不变）。

## Testing Decisions

- 主验收接缝：`MessageInterpreter.interpret(InterpretationInput)` 公共接口；HTTP 用 JDK `HttpServer` 本地 stub（成功 JSON / 非 JSON / 5xx / 超时 / 缺配置），不依赖真实网络。
- 断言：请求体包含 response_format json_object、Authorization 不透出；意图归一与结构化输出经 boundary 校验；Retryable vs fatal 错误分类正确。
- 元数据：真实三元组登记后持久化成功、未登记则折叠 sentinel（既有 registry 语义复用）。
- 端到端（外部 gate）：8081 验收实例配置真实 DeepSeek 密钥后，真群文字订单消息 → CUSTOMER_ORDER 草稿 + ReviewCase；无真实密钥时报告外部门禁，不把 stub 成功宣称为生产可用。

## Out of Scope

- 图片/多模态识别（媒体证据仅随消息原值落库，识别仍走文本；多模态留给后续票）。
- 多轮对话、追问、自动改单/取消。
- Agent 自动确认订单/运单（终局工具边界不变）。
- 提示词自动优化/评估集。

## Further Notes

- order-assistant prototype 的 DeepSeek 调用（DEFAULT_SYSTEM_PROMPT、json_object、urllib/httpx）是真实可用参考，但本解释器配置独立（MESSAGE_INTERPRETER_*），不共享 prototype 进程。
- `.env` 已有 `LLM_API_KEY`（DeepSeek sk-6***）——端到端票可复用该密钥值到 MESSAGE_INTERPRETER_API_KEY，但两者语义独立。
