# 11 — 发布受限 MCP Adapter

**What to build:** 为未来 Agent 提供企业微信消息、草稿、候选和人工复核的受限 MCP 能力，使 Agent 可以查询、重新解释和提交非终局建议，同时从协议层证明它不能确认任何业务事实。

**Blocked by:** 05 — 补齐客户匹配、建档与渠道身份绑定; 06 — 处理不完整、多商品和多收货地址订单; 07 — 接收图片并形成可复核订单草稿; 10 — 处理批量与部分发货回传.

**Status:** resolved

**Claimed by:** zed-agent subagent (2026-08-14 并行施工收口)

- [x] MCP Adapter 与 REST/UI 共用消息、解释、草稿、候选和 ReviewCase 应用用例，不直写业务表或复制匹配规则。
- [x] 工具发现包含查询消息提交/媒体元数据/解释历史、查询订单与运单草稿、查询候选和 ReviewCase。
- [x] 写工具仅包含触发重新解释、提交草稿修改建议、补充材料和显式提交人工复核。
- [x] MCP 写操作使用认证上下文中的 Agent 身份、幂等键、期望版本与 AuditLog，工具参数不能伪造 operator。
- [x] 建议和材料只追加派生/证据信息，不能修改已确认 Customer、SKU、Order、Shipment 或 Tracking。
- [x] 工具列表明确不存在确认订单、确认/批量确认运单、创建 Customer、绑定渠道身份、关闭/驳回复核、改单或取消订单工具。
- [x] MCP 协议验收覆盖工具发现、读写成功、认证失败、版本冲突、幂等重放、审计以及禁止工具缺席。
- [x] MCP 服务的配置和凭据不出现在工具描述、响应或日志中。

## Answer

zed-agent subagent 交付（2026-08-14）：手写轻量 MCP Server（JSON-RPC 2.0 over stdio，**零新增依赖**——pom 无 Spring AI，一期协议面小且测试可验证性更好）。17 个工具 = 13 读（消息/提交/解释/媒体/草稿/候选/ReviewCase）+ 4 写（reinterpret、草稿建议、补充材料、提交复核），写操作复用既有应用用例（reinterpret/supplement 幂等与版本语义与 REST 一致）；Agent 身份经 `MCP_AGENT_IDENTITY` 环境变量注入、工具参数无 operator 字段不可伪造，未配置一律 401；18 个禁止工具显式缺席断言；媒体凭据与配置名不泄露。验证：McpProtocolAcceptanceTest 14/14（握手/发现/读写/认证失败/版本冲突/幂等重放/AGENT 审计/泄露防护）、全量后端 493/493。遗留：真实 Agent 客户端（如 Claude Desktop）联调属部署验收范畴。
