# 10 — MCP 工具面与调用契约

**Type:** grilling
**Status:** open
**Blocked by:** 07

## Question

用户的原话是「留出 MCP 方便后续暴露给礼包整理 agent」。本票定暴露哪些工具、入参出参长什么样。

### 现状（已核实，不必重查）

- MCP **已经存在**：`backend/src/main/java/cn/zimu/fulfillment/mcp/` 下有 `McpToolRegistry`（唯一工具源）、`McpServer`（stdio 面）、`McpReadTools` / `McpDomainReadTools` / `McpWriteTools`。
- 已有相关只读工具：`list_products`、`get_sku`、`list_categories`、`list_provider_skus`、`get_inventory_overview`、`get_inventory_detail`。
- `.scratch/meta-agent-platform` 票 08 已定：**stdio 面一期收紧为只读**；Agent 面走绑定期白名单 + `AgentToolInvoker.execute` 调用期复核双层强制。
- **红线**：业务 Agent 写工具零调用不变式（`meta-agent` 是唯一例外）。礼包 Agent 是业务 Agent → **只能拿只读工具**。

所以本票是**加工具**，不是新建 server，也不是重设权限机制。

### 待决策点

1. **工具粒度**：一个大工具 `quote_bundle_cost`（进组件清单+区域，出完整拆解），还是拆成若干细工具（`get_sku_cost` / `estimate_packaging` / `quote_freight` / `get_customer_region_profile`）？
   - 细工具让 Agent 能自己组合推理、解释过程更透明；大工具减少往返、结果更一致。倾向**大工具做主入口 + 细工具做补充**，但要定清楚。
2. **入参 schema**：组件清单怎么表达（`sku_code` + 数量？还是允许名称模糊匹配——注意名称匹配会引入不确定性，与红线冲突）；区域（省份 / 客户 id / 都不传）；装箱方案（自动 / 指定）；客户采购价（用于算毛利，可选）。
3. **出参 schema**：承载票 07 的分层拆解 + 票 09 的可信度标记。**推断值与确定值必须可区分**。是否返回主数据版本号以支持重放。
4. **失败与澄清**：组件找不到、净重缺失、区域无法判定——用什么 outcome 表达？沿用平台的 `SUCCESS / NEEDS_INPUT / REJECTED / FAILED`（见 meta-agent-platform 票 04）。
5. **stdio 面是否暴露**：这些工具只给内部 Agent 用，还是也开给 stdio 外部客户端？（stdio 面只读，成本数据算不算敏感？）
6. **反向组合工具**（票 12）在本票是否预留位置，还是等 12 定完再加。
7. **与既有工具的重叠**：`get_inventory_detail` 已有，票 08 的临期信息是扩展它还是新开工具？

### 与其他票的关系

- 依赖票 07（成本引擎口径决定出参）。
- 决策 11：Agent 只返回方案，落草稿由应用层做——本票的工具**全部只读**，不提供任何写入口。
