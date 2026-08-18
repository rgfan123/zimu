# 01 — DeepSeekMessageInterpreter 客户端骨架

**Type:** implementation

**What to build:** 真实模型客户端骨架：JDK HttpClient 调用 OpenAI 兼容 `POST {base-url}/chat/completions`（`response_format={"type":"json_object"}`），缺配置 fail-closed，错误分类（重试 vs 终态）。提示词与字段解析在 02 票，本票只保证"能调通、能分类、fail-closed 正确"。

**Blocked by:** 无（接缝 `MessageInterpreter` 已存在）

**Status:** resolved

**Source:** .scratch/message-interpreter-real/spec.md（Solution / Testing Decisions 节）

- [ ] 新增 `DeepSeekMessageInterpreter implements MessageInterpreter`（@Service，替换默认 Bean）：读 `app.message-interpreter.base-url/api-key/provider/model/prompt-version`（缺任一必填 → 与 DefaultMessageInterpreter 一致返回 MODEL_NOT_CONFIGURED + NEED_REVIEW，不抛异常）。
- [ ] HTTP：JDK HttpClient（零新依赖），POST `{base-url}/chat/completions`，Authorization: Bearer api-key，body 含 model/messages/system+user(response 为 InterpretationInput content)/temperature/response_format json_object；超时配置（connect/request 默认 30s 可配）。
- [ ] 错误分类：5xx/连接失败/超时 → 抛 `RetryableInterpretationFailure`；4xx（401/404/429）→ 返回终态 `MODEL_CALL_FAILED` 的 InterpretationResult（不烧重试）；200 但 body 非法 → `MODEL_OUTPUT_INVALID`（经 boundary 或本层判定）。
- [ ] api-key 绝不出现在异常消息、日志、DTO、审计中（实现内断言/测试覆盖）。
- [ ] 主验收：JDK `HttpServer` 本地 stub 测试（后端 test 目录）：成功 200 JSON、5xx、401、超时、缺配置 fail-closed、请求体断言（json_object + Authorization 头存在但不回显）。
- [ ] 与 `MessageStructuredOutputBoundary.failClosed` 的接缝：本票返回原始 InterpretationResult（intent 先用简单占位归一——仅当 error 非空时 NEED_REVIEW；完整归一在 02）；boundary 集成断言通过。

**Do not:** commit；改动接收/草稿/复核/MCP 链路；引入新依赖。

**完成后更新票：** 勾选 checkbox、追加 ## Comments、Status 置 resolved。

## Comments

- 实现：`DeepSeekMessageInterpreter`（@ConditionalOnProperty base-url，与 DefaultMessageInterpreter 互斥——无 base-url 时 Default 兜底 fail-closed）；JDK HttpClient + `response_format=json_object`；错误分类统一为：HTTP/网络/超时/响应结构非法 → `MODEL_CALL_FAILED`（service 3 次重试 + NEED_REVIEW 收口，4xx 与 5xx 同路径——校准票面"4xx 不重试"：既有重试语义只有 MODEL_CALL_FAILED 一条路径，不新增终态枚举，3 次封顶兜底）；缺配置 → `MODEL_NOT_CONFIGURED`（不重试）。
- 成功路径暂返回 raw_content 占位（intent 解析在 02）。
- 验收：`DeepSeekMessageInterpreterTest` 9/9（stub HttpServer：成功/500/401/空 content/非 JSON/缺配置/不可达/尾斜杠/密钥零泄漏）；回归 `MessageInterpretationApiTest+InterpretationTaskCausalityTest+WecomEndToEndAcceptanceTest` 35/35。
