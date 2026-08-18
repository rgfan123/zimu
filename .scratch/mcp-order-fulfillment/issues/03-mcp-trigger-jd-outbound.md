# 03 — MCP 暴露"触发京东发货"写工具

**Type:** implementation

**What to build:** 授权 Agent 通过 MCP 对一个已就绪的 Shipment 触发京东云仓建出库单：成功后看到外部引用与同步状态，失败可安全重试且不留下半截业务批次；真实京东写入仍受既有外部授权门禁约束。

**Blocked by:** 01 — 启动 MCP stdio server 并冒烟验证（与 02 相互独立）

Status: resolved

**Claimed by:** dsh-agent

- [x] tools/list 出现新写工具 submit_jd_outbound（shipment ID + 幂等键）。
- [x] tools/call 触发后，同一 Shipment 只产生一张京东出库集成记录；Mock JD 下成功返回外部引用与同步状态。
- [x] 写门闩未开启（app.jd.write-mode 默认 OFF）时返回既有拒绝码且不触网。
- [x] MCP 进程注入的 Agent 身份作为服务端复验主体参与授权，等价于网关复验的 X-Operator 语义；工具参数不接受 operator。
- [x] 相同幂等键重放返回原结果；请求事实变化返回冲突；失败保留可重试诊断。
- [x] 真实 addSoOrder 不在本票验证，维持 05 — 受控创建京东出库单的外部授权门禁。

## Answer

- 实现：`McpWriteTools` 新增 `submit_jd_outbound` 工具（shipment_id + idempotency_key），handler 复用 `executeWrite` 并调用 `ShipmentJdOutboundService.submit`（与 REST `POST /api/v1/shipments/{id}/jd-so-order` 同一用例与幂等 scope）。命令为空 record，请求完全由 Shipment 派生；`McpRequestContext.requireCommandContext()` 使 Agent 身份同时充当 operator 与 authenticatedOperator，满足 `requireAuthorized` 的授权名单校验。已重打包 jar 并部署到 backend 容器。
- 端到端证据（真实 PostgreSQL + Mock JD + 容器内 MCP 进程）：
  - 冒烟：tools/list 返回 30 个工具（新增 submit_jd_outbound）。
  - 正向：种子 shipment 10（订单 9 / fulfillment 9 / JD provider / 结构化收货地址 / provider_skus 映射齐备）→ MCP submit（MCP_AGENT_IDENTITY=smoke-agent + JD_OUTBOUND_AUTHORIZED_OPERATORS=smoke-agent + JD_LOP_WRITE_MODE=ON + JD_LOP_CLIENT_MODE=MOCK）→ 201 返回 erp_delivery_no=202608180006、jd_delivery_no=MOCK-DELIVERY-001、sync_status=SUBMITTED；落库 shipment_jd_outbounds 仅一条记录（shipment_id=10）。
  - 幂等：同键重放返回首次结果，审计 IDEMPOTENT_REPLAY（AGENT, 201）。
  - 门闩：写门闩 OFF → 409 JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED（AGENT 审计 409），不触网。
  - 授权：操作人不在 JD_OUTBOUND_AUTHORIZED_OPERATORS 名单 → 403 JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED；工具参数无 operator 字段。
  - 审计：mcp.submit_jd_outbound 成功/重放/门闩拒绝均为 AGENT 审计。
- 边界：真实 addSoOrder 未调用（Mock JD），维持 05 票外部授权门禁（用户明确授权 + 命名测试 Shipment 后才能真写）。
