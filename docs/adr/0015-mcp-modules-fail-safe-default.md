# ADR 0015：MCP 分模块暴露的空值语义改为 fail-safe

- 日期：2026-08-28
- 状态：已接受
- 决策者：Jerry、Claude（主导者）、Codex

## 背景

`app.mcp.modules`（env `MCP_MODULES`，逗号分隔）决定 MCP 注册表暴露哪些模块的工具。
一期为向后兼容，把**空值定义为「全部模块」**——不配置即等于「注册即暴露」。

2026-08-28 的 MCP 设计审计（全部 38 个工具逐个查返回方式与 DTO 内容）发现，
这个默认是 fail-open，且后果被两件事放大：

1. **生产的 MCP HTTP 面发布在 `0.0.0.0:30000`**（经 nginx），凭 Bearer token 访问。
   生产显式设了 `MCP_MODULES=masterdata,inventory,orders-read`，实际暴露 11 个只读工具。
2. **未暴露的 `messages` 模块里有原样透传 PII 的工具**：
   `get_order_draft` / `list_order_drafts` 是 `json(orderDrafts.detail(...))` 直返，
   而 `OrderDraftDetailDto` 含 `customerName` / `receiverName` / `receiverPhone` /
   `receiverAddress`；`list_channel_messages` 透传客户消息原文。

也就是说：**该环境变量一旦丢失或被覆盖为空，公网面立刻从 11 个工具放大到 30 个，
其中含全套客户 PII，且不需要任何代码变更。** 此前唯一的防线是部署 runbook 里
「改 override 后双 grep 确认 `MCP_MODULES` 还在」这条人工纪律。

## 决策

**空值语义翻转为「不暴露任何模块」。** 要暴露必须显式列出。

同时新增**启动期自检**：若 `app.mcp.enabled=true` 而解析出的模块集为空，
构造 `McpToolRegistry` 时抛 `IllegalStateException`，容器起不来。

自检选 fail-fast 而非 WARN 的理由：语义翻转把配置丢失的失败模式从
「PII 外泄」变成「机器人全哑」。外泄是响亮的（有人会看到不该看到的数据），
**哑是静默的**——没人会立刻发现，等运营察觉「机器人怎么不说话了」已过去很久。
让它在部署那一刻由部署者当场看见，成本远低于让业务同事第二天来问。
这与本类既有的「未知模块名启动期 fail-fast」同源，不是新范式。

## 考虑过的方案

- **维持「空 = 全开」，继续靠部署脚本 grep**：改动为零，但纪律只活在一条脚本路径里，
  换个部署方式或手改 override 就守不住，而失守的代价是客户 PII 上公网。
- **空值改「不开」但只 WARN 不 fail**：容器仍能起来，避免一次配置笔误挡住整个后端。
  但 WARN 会淹没在启动日志里，等于把「静默失败」换成「几乎静默失败」。
- **给 `messages` 模块的工具补显式投影，保留 fail-open**：治了本次这一个洞，
  但没治「透传 by default」这个结构——下一个含 PII 的 DTO 加进来还会重演。
  该项作为独立工作保留（全工具字段快照测试），与本 ADR 并行而非替代。

## 影响与风险

- **生产无感**：已显式设置 `MCP_MODULES=masterdata,inventory,orders-read`（实测），
  切换前后暴露的 11 个工具不变。**但部署前必须再确认一次，不能反过来。**
- **开发/测试环境若依赖「空 = 全开」会受影响**，需一并补显式配置。
- **运维纪律的失败模式反向了**：双 grep 这条现在两个方向都要守——
  变量丢失不再导致 PII 外泄，而是导致 MCP 面一个工具都没有。部署清单需同步改写。
- 启动期自检只在 `app.mcp.enabled=true` 时生效；整体关闭 MCP 仍是合法状态。
