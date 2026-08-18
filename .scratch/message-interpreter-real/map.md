# 真实企微消息解释器

Label: wayfinder:map

## Destination

`DefaultMessageInterpreter`（占位，缺配置 MODEL_NOT_CONFIGURED / 已配置抛未实现）→ `DeepSeekMessageInterpreter`（OpenAI 兼容 chat/completions，json_object 输出，JDK HttpClient）。企微消息从"全部 NEED_REVIEW"变为"真实意图识别 + 草稿生成"。接收/幂等/重试/复核/MCP 链路不变，只换模型接缝实现。

## Notes

- 域：Spring Boot (Java 21) 单体的 `message` 包；接缝 `MessageInterpreter`（src/main/java/cn/zimu/fulfillment/message/MessageInterpreter.java）。
- 传输参考：prototype/customer-order-assistant/app.py（DeepSeek json_object 已验证）；本实现配置独立（MESSAGE_INTERPRETER_*），JDK HttpClient 零新依赖（与 wecom-long-connection 的 JDK WebSocket 惯例一致）。
- 输出边界：`MessageStructuredOutputBoundary.failClosed`（意图枚举校验 + 裁剪）；元数据白名单：`MessageModelMetadataRegistry`（真实三元组必须登记 MESSAGE_INTERPRETER_PUBLIC_*_ALIAS）。
- 既有失败语义：`MODEL_CALL_FAILED` → `RetryableInterpretationFailure`（3 次重试）→ 终态 NEED_REVIEW；4xx 不重试。
- 外部 gate：真实 DeepSeek 密钥（.env 已有 LLM_API_KEY sk-6***）——端到端票用。
- 每次会话先读本 map，再取 frontier 票。

## Decisions

- [01 — DeepSeekMessageInterpreter 客户端骨架](issues/01-deepseek-client.md) — JDK HttpClient POST {base-url}/chat/completions，response_format json_object；缺配置 fail-closed（MODEL_NOT_CONFIGURED）；5xx/网络/超时 → Retryable，4xx → 终态。
- [02 — 提示词 v1 与输出解析](issues/02-prompt-and-parsing.md) — 意图 6 类提示词 + CUSTOMER_ORDER/SUPPLIER_TRACKING 字段 schema；JSON 解析 → InterpretationResult；经 boundary 归一。
- [03 — 元数据白名单与密钥安全](issues/03-metadata-and-secrets.md) — 真实三元组登记 public-metadata-aliases；api-key 零泄漏（日志/错误/DTO/审计）测试。
- [04 — 端到端验收](issues/04-end-to-end-acceptance.md) — 8081 配真实 DeepSeek → 真群文字订单 → CUSTOMER_ORDER 草稿 + ReviewCase；无密钥则标记外部门禁。

## Not yet specified

- 多模态（图片）识别——一期文本识别完成后另开票。
- 提示词版本化/评估集——正确率数据积累后按 spec「未来自动确认能力」另开决策票。

## Out of scope

- 自动确认、改单、取消、多轮追问、Agent 终局工具。
