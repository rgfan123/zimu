# 04 — 端到端验收（真实 DeepSeek）

**Type:** implementation

**What to build:** 8081 验收实例配置真实 DeepSeek 密钥后，企微真群文字订单消息走完全链路：接收 → 解释（真实意图）→ 草稿 → ReviewCase。无真实密钥时如实标记外部门禁。

**Blocked by:** 03 — 元数据白名单与密钥安全

**Status:** ready-for-agent

**Source:** .scratch/message-interpreter-real/spec.md（Testing Decisions 端到端节）

- [ ] 8081 验收实例以 `MESSAGE_INTERPRETER_PROVIDER=deepseek / MODEL=deepseek-chat / PROMPT_VERSION=wecom-interpret-v1 / BASE_URL / API_KEY`（复用 .env 的 DeepSeek 密钥）重启；readiness 确认模型配置就绪。
- [ ] 用本地 stub 或真实调用验证一次 CUSTOMER_ORDER 识别（若不便在真群发消息：直接调解释链路公共接缝或发一条真群消息；真群消息优先）。
- [ ] 真群/模拟文字订单消息 → `message_submissions.status=DRAFTED`、`message_interpretations` intent=CUSTOMER_ORDER（provider/model/prompt_version 持久化）、`order_drafts` + `review_cases(OPEN)` 各 1。
- [ ] 非业务消息（如"您好"）→ NON_BUSINESS，不创建草稿与待办。
- [ ] 模型临时失败路径：stub 500 → 3 次重试后 NEED_REVIEW 收口（复用既有测试或运行验证）。
- [ ] 无真实密钥/密钥失效时：报告标记外部门禁（模型不可用），不把 stub 成功宣称为生产可用。
- [ ] 更新 wecom-message-intake 13 票 checkbox 6 状态（若真实链路通过，真实企微验收补齐）。

**Do not:** commit；改动生产业务代码（本票只配置与验证，缺陷修复除外）。

**完成后更新票：** 勾选 checkbox、追加 ## Comments、Status 置 resolved。
