# 03 — 元数据白名单与密钥安全

**Type:** implementation

**What to build:** 真实 provider/model/prompt-version 三元组登记进 `public-metadata-aliases`（否则 `MessageModelMetadataRegistry` 拒绝持久化/投影折叠 sentinel）；api-key 全链路零泄漏验证。

**Blocked by:** 02 — 提示词 v1 与输出解析

**Status:** resolved

**Source:** .scratch/message-interpreter-real/spec.md（元数据白名单/安全节）

- [ ] 配置文档与接线：`MESSAGE_INTERPRETER_PUBLIC_PROVIDER_ALIAS / MODEL_ALIAS / PROMPT_VERSION_ALIAS` 登记真实三元组（deepseek/deepseek-chat/wecom-interpret-v1），并写入 `.env.example` 与 `backend/.env.acceptance.local`（真实值，git-ignored）。
- [ ] 集成测试：解释成功后 `message_interpretations` 的 provider/model/prompt_version 持久化成功（三元组在白名单）；未登记的三元组被 registry 折叠为 sentinel（复用既有语义，补本场景断言）。
- [ ] 密钥零泄漏：api-key 不出现在——异常消息、日志输出（logback 无打印）、DTO/API 响应、审计 payload、错误码 message（stub 测试断言 5xx/4xx 错误文本不含 key 片段）。
- [ ] `application.yml` 增加 `message-interpreter.base-url/api-key` 键与占位注释（值走环境变量，yml 不写明文）。

**Do not:** commit；改动接收/草稿/复核链路。

**完成后更新票：** 勾选 checkbox、追加 ## Comments、Status 置 resolved。

## Comments

- application.yml 增加 `base-url/api-key/request-timeout-ms`（环境变量 MESSAGE_INTERPRETER_*，yml 不写明文）；.env.example 补 9 个键（含 PUBLIC_*_ALIAS 登记 deepseek/deepseek-chat/wecom-interpret-v1）；backend/.env.acceptance.local 已接线真实值（密钥复用 .env LLM_API_KEY，git-ignored）。
- 验收：`DeepSeekInterpretationMetadataTest` 4/4（登记三元组允许持久化 + 投影不变；未登记拒绝 + 折叠 sentinel；none 三元组仅错误态允许；解释器失败结果不泄漏密钥且被 registry 放行）；既有 `MessageInterpretationApiTest` 已覆盖登记→持久化链路（test-provider aliases）。
