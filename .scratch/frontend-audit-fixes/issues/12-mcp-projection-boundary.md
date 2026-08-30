# 12 — MCP 对外投影边界：禁止 DTO 原样透传，且模块默认必须 fail-safe

Type: implementation
Status: 已实现（工作区未提交）
Priority: **P0**（第 2 节）/ P1（第 1、3 节）
Requested: Jerry 2026-08-28「你这让我很担心 去查看所有的 mcp 设计」

## 审计范围

全部 38 个 MCP 工具（30 只读 + 8 写），逐个查返回方式与 DTO 内容。
生产 `MCP_MODULES=masterdata,inventory,orders-read`，实际暴露 11 个。

## 结论一：当前暴露的 11 个工具没有凭据泄露

- `fulfillment_providers.config` 实测**不含任何 token/secret/key/password**，
  只有业务标识（仓号/店铺号/货主号/承运商号/企微群 ID）。
- 且 `FulfillmentProviderJdConfig.status()`（`sku/FulfillmentProviderJdConfig.java:113-124`）
  对 `SECRET_KEYS = {"pin"}` 只给 `present: true`、**不给值**。这是有意设计，是稳的。
- `search_orders` / `get_order` 吐 `receiver_name`（`McpOrdersReadTools.java:146/175`），
  **不含手机号与地址**——有界的选择。

**唯一的存储转储是 `search_product_archive`**（见票 11）。

## 结论二（P0）：`MCP_MODULES` 为空 = 全部模块，而 `messages` 模块会直接公开客户 PII

`backend/src/main/resources/application.yml:262-264`：

```yaml
# 分模块暴露（env MCP_MODULES，逗号分隔）：空 = 全部模块（向后兼容）
modules: ${MCP_MODULES:}
```

**空值 = 全开**，这是个 fail-open 默认。而 `messages` 模块（5 个工具，当前未暴露）里：

- `get_order_draft` / `list_order_drafts`（`McpReadTools.java:237/241`）是
  `return json(orderDrafts.detail(...))` 原样透传，而 `OrderDraftDetailDto`
  含 **`customerName` / `receiverName` / `receiverPhone` / `receiverAddress`**——**全套 PII**
- `list_channel_messages`（`:195`）透传 `ChannelMessageSummaryDto.content`，
  即客户原始消息正文，本业务里必然含姓名、电话、地址

也就是说：**`MCP_MODULES` 一旦丢失或被覆盖为空，公网 30000 立刻从 11 个工具放大到 30 个，
其中包含全套客户 PII，无需任何代码变更。** 部署 runbook 里那条「改 override 后必须
双 grep 确认 `MCP_MODULES` 还在」的纪律，防的就是这个——但纪律不该是唯一防线。

**✅ Jerry 2026-08-28 拍板：改为 fail-safe——空值 = 不暴露任何模块。**
要开必须显式列出。生产已显式设置 `MCP_MODULES=masterdata,inventory,orders-read`（实测），
所以切换对生产无感；但**部署前必须再确认一次，不能反过来**。
开发/测试环境若依赖「空=全开」，需一并补显式配置。

## 结论三（P1）：真正的系统性缺陷是「透传 by default」

`json(x)` 就是 `objectMapper.valueToTree(x)`（`McpDomainReadTools.java:745`、
`McpReadTools.java:408`）。30 个只读工具里**大部分走这条路**：

```
McpReadTools:        195 199 203 207 237 241 247 251 320 324   ← 10 个透传
McpDomainReadTools:  283 292 298 345 353 361 365 369           ← 8 个透传
显式投影的只有:      skuNode / providerSkuNode / archiveSheetNode / order 两个
```

**后果**：一个工具安不安全，取决于它背后的 DTO 恰好干净不干净，而不取决于任何强制边界。
`InventoryOverviewItem` 恰好是干净的领域 record，所以没事；
`OrderDraftDetailDto` 恰好含 PII，所以一旦启用就出事；
`ProductArchiveSheet` 恰好是 Excel 拓本，所以 Bot 以为自己在读表格。
**这是抽奖，不是设计。**

而且透传是**未来时的风险**：谁给 `OrderDraftDetailDto` 加个字段，
那个字段就自动出现在 MCP 对外响应里，没有任何评审关口。
`archiveSheetNode` 的注释已经意识到了这点（「逐字段投影而不是整体 valueToTree，
避免记录新增字段时默认外泄未经审阅的内容」），但这条纪律**只在那一个函数里生效**。

**建议**：把它升级成全局规则——**每个对外工具必须有显式投影函数，禁止 `json(dto)` 直接返回**。
配一个测试：遍历注册表里所有工具，断言其 handler 不直接返回服务层 DTO
（或更实际：对每个工具的响应做字段快照测试，新增字段必须改测试才能通过，
从而强制评审）。

## 范围与顺序

1. **先做结论二**（P0，改默认值 + 部署前确认），一行配置 + 测试
2. **再做结论三**（P1，字段快照测试先行，把现状钉住；再逐个补投影）
3. `search_product_archive` 的投影重做见**票 11**，不在本票重复

## 不做的事

- 🚫 不动写工具门闩（`McpWriteGate` / `McpServer`）——那是别的会话的在制品，且设计是稳的
  （HTTP 面结构性拿不到开启态实例）
- 🚫 不动 `pin` 掩码等既有的正确投影
- 🚫 不改内部管理台路径（前端 47 列展示与导出必须继续全保真）
- 🚫 不执行任何生产 SQL、不改生产 env（改 env 是部署动作，由 Jerry 与部署会话执行）

## Acceptance Criteria

- [ ] `app.mcp.modules` 空值语义改为 fail-safe，并有测试断言「空 = 不暴露」
- [ ] 有测试断言 `messages` 模块未在默认配置下注册
- [ ] 全工具字段快照测试：任一工具响应新增字段会导致测试失败，强制评审
- [ ] 禁止 `json(dto)` 直返的规则有测试或 lint 落地（至少覆盖只读工具）
- [ ] 既有 11 个暴露工具的响应形状零回归
- [ ] 部署前与部署会话确认生产 `MCP_MODULES` 已显式设置（实测已设置）

## Files likely affected

- `backend/src/main/resources/application.yml`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpToolRegistry.java`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpReadTools.java`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java`
- MCP 相关测试

## Risk

中。改默认值会影响所有未显式配置的环境（开发/测试）。
好消息是生产已显式设置 `MCP_MODULES=masterdata,inventory,orders-read`，
所以切换对生产是无感的——但**必须在部署前确认，不能反过来**。
