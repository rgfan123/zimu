# 06 — 数据查询 Agent

**What to build:** 自然语言数据查询 Agent：用户用自然语言提问，Agent 选择正确的只读工具（订单/库存/采购/主数据）取数并给出结构化答案；只读，不写。

**Blocked by:** 03 — Agent ↔ MCP 工具绑定；04 — MCP 领域只读工具扩展

**Status:** resolved

## 行为

输入：自然语言问题（+ 可选会话上下文 `thread_id`）。

Agent 流程：
1. 解析问题 → 选择工具与参数（工具选择可观测）；
2. 调用只读工具（04 票 + 既有 `McpReadTools` 中非 PII 投影）；
3. 汇总为结构化答案。

输出（结构化记录）：
```json
{
  "answer": "人话摘要，含数字",
  "sources": [{"tool": "...", "key_args": {"...": "..."}, "row_count": 0}],
  "confidence": 0.0,
  "requires_human": true,
  "clarification_needed": ["..."]
}
```

策略：
- 歧义问题先要求澄清（`clarification_needed`），不猜参数；
- 需要客户/收货人 PII 的查询直接转人工（Agent 无 PII 工具）；
- 白名单只含只读工具。

示例评测查询（09 票基线的种子）：
- “最近 7 天有多少缺货的订单行”
- “SKU-xxx 的进货价和零售价是多少”
- “采购工单 P-123 还差多少数量”
- “某履约方本月共接收多少运单回执”

## 非范围

- 写操作；PII 查询；SQL 直连（只经 MCP 白名单工具）。

## 验收标准

- [ ] 固定评测查询集上工具选择正确率、答案数字正确率达到基线（09 票定义）；
- [ ] 白名单只含只读工具；
- [ ] 歧义输入进入澄清路径而非猜测；
- [ ] 请求 PII 时明确拒绝/转人工；
- [ ] 每次运行留下工具调用序列审计。

## 验证原则

- 评测集可重复运行；数字正确性以数据库事实核对，不以“读起来对”验收。

## Answer

**2026-08-16 收票。** 交付数据查询 Agent（slug=data-query-agent），未改动任何既有文件
（01-04 票交付文件与 `AgentRegistryConfiguration` 零改动；本票全部为新增独立文件）。

### 交付内容（`backend/src/main/java/cn/zimu/fulfillment/agent/` 新增 7 个文件）

- `DataQueryAgentDefinitionConfiguration`：独立 `@Configuration` 注册 `AgentDefinition` bean
  （slug=`data-query-agent`，提示词版本 `data-query-v1`，model_ref=`app.agent`，enabled）。白名单 13 个
  只读工具 = 04 票 11 个领域工具 + `McpReadTools` 非 PII 投影（`list_interpretations`、
  `list_message_media`）；不含任何写工具，不含客户/收货人 PII 投影工具
  （草稿/候选/渠道消息原文/复核详情全部排除）。
- `DataQueryAgentOutput`：票 JSON schema 的结构化输出记录
  （answer/sources[{tool,key_args,row_count}]/confidence/requires_human/clarification_needed），
  AiServices 以 JSON Schema 约束；不满足结构 → `AGENT_OUTPUT_INVALID`。
- `DataQueryAgentGateway`：AiServices 结构化输出网关（模式同 `AgentGateway`，输出 schema 更丰富；
  02 票「业务 Agent 定义更丰富记录」语义，不动 01 票最小 schema 运行时）。
- `DataQueryAgentGuard`：确定性策略门（两层兜底，不依赖模型自觉）：
  ① 问题级——PII 关键词（客户/收货人/收件人/手机/电话/姓名/地址/身份证）→ 转人工；
  占位/歧义（`SKU-xxx`、工单号 `P-123`、`某履约方` 等）→ 澄清，不猜参数；
  ② 工具参数级——模型仍以占位值猜参数时执行器拒绝并回传 `CLARIFICATION_REQUIRED`。
- `DataQueryAgentService`：执行服务。输入自然语言问题（+可选 thread_id）→ 策略门 →
  模型选择工具并调用（`AgentToolBindingFactory` 绑定，run_id 即工具调用上下文关联键）→
  结构化答案。未配置模型 fail-closed（`AGENT_MODEL_NOT_CONFIGURED`）；每次运行生成唯一 run_id
  并落 AGENT 审计（operation=`agent.data-query-agent.run`，responsePayload 含
  `tool_call_sequence` 工具调用序列，满足「每次运行留下工具调用序列审计」）。
- `DataQueryAgentToolCall` / `DataQueryRunResult`：工具调用记录与运行结果（含稳定失败码）。

### 评测查询集（09 票基线种子，`DataQueryAgentEvalFixture`，内嵌代码 fixture）

- 票内示例 4 条：`最近 7 天有多少缺货的订单行`（可答，`list_procurement_tickets`）；
  `SKU-xxx…` / `采购工单 P-123…` / `某履约方本月共接收多少运单回执`（占位/歧义 → 澄清路径）。
- 可答落地变体 2 条（数字与数据库事实核对）：`SKU-EVAL-000001 的进货价和零售价是多少`
  （`search_skus`，价格对照 `app.skus`）；`采购工单 9005 还差多少数量`
  （`get_procurement_ticket`，缺口对照 `app.procurement_ticket_items` 求和）。
- PII 拒绝路径 1 条：`查一下客户张三的收货地址` → `PII_GUARDED` 转人工。

### 测试（新增 4 个测试文件，23 个测试全绿）

- `DataQueryAgentDefinitionTest`（10）：注册表定义、写工具零引用不变式、PII 投影工具零引用、
  白名单全覆盖、PII/歧义/占位判定表驱动、真实 SKU 编号不误判。
- `DataQueryAgentServiceTest`（10）：空输入澄清、3 条占位查询澄清、PII 转人工、AGENT_NOT_FOUND/
  AGENT_DISABLED/未配置模型 fail-closed、审计（run_id/thread_id/白名单/模型元数据 none 投影）。
- `DataQueryAgentServiceIntegrationTest`（3，Testcontainers + JDK HttpServer stub 模型，不依赖真实
  key）：固定评测集端到端——澄清/PII 路径零模型调用零工具调用；3 条可答查询工具选择正确、
  答案数字与直接数据库查询的事实逐一核对（缺货行数=3、进货价 12.34/零售价 25.60、
  缺口 23.500）；暴露给模型的工具恰为白名单 13 个；占位参数兜底拦截转澄清；审计工具调用序列。
- `DataQueryAgentEvalFixture`：内嵌评测集 fixture（09 票基线种子）。

### 回归

- `mvn -q test-compile` 通过；`DataQueryAgent*` 23/23 绿；
- `mvn test -Dtest='Agent*'` 55/55 绿（01-04 既有 Agent 测试无回归）；
- 全量 `mvn test`：81+ 测试类 / 662 测试，0 失败 0 错误 7 skipped（surefire fork 关闭 30s
  警告为容器类测试已知无害信息，与 04 票记录一致）。

### 遗留事项

- 工具调用序列审计已随 AGENT 审计落盘；完整可观测（token/序列 API）留 08 票深化。
- 09 票基线可基于 `DataQueryAgentEvalFixture` + 集成测试 runner 出指标（工具选择正确率/数字
  正确率当前实现下为 100% 确定性结果）。
- 与 `AgentRuntimeFacade` 的关系：门面底层运行时 schema 固定为 01 票最小
  `AgentStructuredOutput`（未修改），本 Agent 按 02 票语义走自身网关；两者审计 operation
  命名一致。05 票比价 Agent 可复用同一「业务 Agent 专属网关 + 策略门 + 审计」模式。
- 评测期间 05 票（采购比价）并行开发曾短暂产生未编译测试文件，系其他 subagent 中途状态，
  非本票改动，随后自行消失；本票未触碰任何 05 相关文件。
