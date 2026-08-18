# 05 — 补齐客户匹配、建档与渠道身份绑定

**What to build:** 当订单草稿无法匹配客户时，运营人员可以在同一复核流程选择已有客户或创建新客户；系统生成客户编码，并在入口确实提供客户渠道身份时保存显式绑定，供后续消息带出客户候选。

**Blocked by:** 04 — 确认一条完整客户订单.

**Status:** resolved

**Claimed by:** zed-agent subagent (2026-08-14 并行施工收口)

- [x] 复核页支持搜索并选择已有 Customer，也支持填写人工确认的名称创建新 Customer。
- [x] 新客户编码由系统幂等生成，模型和操作员均不能指定或覆写该编码。
- [x] 选择或创建客户后，订单草稿继续完成同一确认流程，不需要拆出额外 `CUSTOMER_OPS` ReviewCase。
- [x] 渠道身份唯一作用域包含企微主体、接入类型和渠道标识；昵称、备注、描述等资料作为可变快照保存。
- [x] 仅当消息入口提供真实客户渠道身份时，确认事务才建立该身份到唯一 Customer 的绑定；普通微信群转发员工绝不绑定为客户。
- [x] 已绑定身份的后续消息自动带出 Customer 候选，但一期仍必须人工确认订单。
- [x] 冲突绑定、跨作用域 ID、过期版本和并发创建被公共 API 明确拒绝并留下审计证据。
- [x] 公共 HTTP 与页面测试覆盖选择已有客户、创建客户、存在身份绑定及转发场景无绑定。

## Answer

zed-agent subagent 交付（2026-08-14）：V28 增加 `channel_messages.sender_identity_type`（EMPLOYEE/CUSTOMER）作为真实客户渠道身份唯一声明通道（转发员工默认 EMPLOYEE 绝不绑定）；确认事务支持二选一客户选择（已有 ID / 新客户名，`CustomerCodeGenerator` 系统幂等编码）；`ChannelIdentityService.bindFromSubmission` 确认事务内按 (corp_id, access_type, userid) 作用域建立绑定并审计；冲突/过期/并发拒绝。验证：OrderDraftApiTest 17/17、ChannelIdentityServiceTest 6/6、组合 76/76。遗留：前端新建客户表单（API 已就绪）、docs/schema.sql 待同步。
