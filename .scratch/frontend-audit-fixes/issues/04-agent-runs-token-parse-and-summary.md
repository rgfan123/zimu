# 04 — Agent 运行记录：Token 列解析 + 顶部历史汇总

Type: implementation
Status: ready-for-agent
Priority: P1
Requested: 用户 2026-08-28「这里的 token 应该解析进去，然后在最上面汇总历史 token」

## Problem

**A. Token 列把原始 JSON 甩给用户**

`AgentRunsPage.tsx:176-184`：

```tsx
{
  title: 'Token',
  dataIndex: 'token_usage',
  width: 160,
  render: (value: unknown) => (
    <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
      {formatCompactJson(value)}
    </Typography.Text>
  ),
}
```

用户看到的是原样 JSON：

```
{"model_calls":1,"total_tokens":1966,"prompt_tokens":1634,"completion_tokens":332}
```

一个 160px 宽的单元格里塞四行等宽字体 JSON，既读不出量级也无法横向比较。
类型层面 `token_usage: unknown | null`（`agentTypes.ts:125,168`）——从未被结构化过。

**B. 页面顶部没有历史 Token 汇总**

`AgentRunsPage` 全页无 `Statistic` / 汇总区（grep `Statistic|Descriptions|summary|汇总` 零命中）。
运营看不到「这批筛选条件下一共烧了多少 token」，只能逐行读 JSON 心算。

## 现成资产（不要重造，也不要新建后端端点）

| 资产 | 位置 | 说明 |
|---|---|---|
| **汇总端点已存在** | `GET /api/v1/agent-runs/token-usage`（`endpoints.ts:1025` 有其 filter 类型；`agentRunsApi.tokenUsage()`） | `AgentCostPage.tsx:61` 已在用。**本票直接复用，不新增端点** |
| Token 数字格式化 | `agentPresentation.ts:488 formatTokens()` | 已做 `toLocaleString('zh-CN')` 千分位 + null→`—` |
| 结构化字段范式 | `AgentCostPage.tsx:146-193` | 已有 `total_tokens` / `prompt_tokens` / `completion_tokens` / `model_calls` 的成熟展示 |
| 未计量运行数 | `agentTypes.ts:195 runs_without_token_usage` | 汇总时用于诚实标注「N 次运行未记录 token」 |

## Implementation idea

**A. Token 列结构化**

- 为 `token_usage` 定义**运行时安全的解析函数**（放 `agentPresentation.ts`，与 `formatTokens` 同处）：
  ```ts
  export interface RunTokenUsage {
    modelCalls: number | null;
    totalTokens: number | null;
    promptTokens: number | null;
    completionTokens: number | null;
  }
  export function parseTokenUsage(value: unknown): RunTokenUsage | null
  ```
  **必须运行时逐字段校验**（`typeof === 'number' && Number.isFinite`）——`token_usage` 后端类型是
  `unknown`，是不可信的线上 JSON，且历史数据可能缺字段或形状不同。解析失败返回 `null`，
  **不要抛异常、不要用 `as` 断言硬转**。
- 列渲染改为：主显 **总 token**（`formatTokens`，千分位），次行小字给 `入 1,634 / 出 332`。
  `model_calls > 1` 时补一个「N 次调用」标注（多数为 1，恒显是噪声）。
- **解析不出来时显示 `—`，不要回退成打印 JSON**——那正是本票要消灭的东西。
  若历史行普遍解析不出，如实报告，不要为了好看而伪造。
- 列宽相应调整（当前 160px 是为 JSON 留的）。

**B. 顶部历史汇总**

- 页面顶部加汇总条，**跟随当前筛选条件**（mode/agent slug/结果/业务实体/日期区间）——
  汇总的是「你正在看的这批」，不是全局固定值。筛选变化要重新取数。
- 数据走既有 `agentRunsApi.tokenUsage(filter)`，**filter 与列表查询同源**，避免两处口径漂移。
- 展示项：总 Token、入/出分列、模型调用次数、运行次数。
- **诚实标注**：`runs_without_token_usage > 0` 时明确写出「N 次运行未记录 token」——
  这个系统里早期运行是没有 token 留痕的，不标注会让人以为汇总是全量。
- 汇总失败/加载中/空三态齐全，失败给重试，**不要静默显示 0**（0 和「取不到」是两回事）。

## ⚠️ 裁定：窄幅扩展汇总端点（2026-08-28，执行者提出前提冲突后）

执行者核实发现**汇总端点的筛选面窄于列表端点**：

| 参数 | 列表 `GET /agent-runs` | 汇总 `GET /agent-runs/token-usage` |
|---|---|---|
| slug / run_mode / business_entity_type / started_from / started_to | ✅ | ✅ |
| **outcome** | ✅（`AgentRunReadController:37`） | ❌ **缺** |
| **business_entity_id** | ✅（`AgentRunReadController:40`） | ❌ **缺** |

后果：用户筛「结果=失败」或按业务实体 ID 筛时，**汇总会静默忽略该条件、按全量算**，
与列表口径漂移——这正是本轮审计一直在批评的「同屏两块 UI 各说各话」。

**裁定：采纳方案 1，窄幅扩展后端**（不新增端点，只给 `/token-usage` 补两个 **可选** 参数
`outcome`、`business_entity_id`，与列表端点同名同语义），理由：

- 方案 2（汇总不跟随这两项）等于**交付一个会骗人的汇总**；要么加显眼免责说明（很别扭），
  要么用户被误导。宁可改后端。
- 改动面极窄：Controller 两个 `@RequestParam` + `AgentTokenUsageFilter.of(...)` 两个入参 +
  聚合 SQL 的两个可选 where 条件。**无迁移、无表结构变更、无破坏性**（参数可选，老调用行为不变）。
- 本次已建立部署管线（`real-fdf94f5` 刚发过），后端再发一次成本可控。

**执行要求**：
- 参数名与列表端点**逐字一致**（`outcome` / `business_entity_id`），不要另起名字
- 两个参数都 **required = false**，缺省时行为与现在完全一致（老调用零回归）
- 补后端测试覆盖：带 outcome 过滤、带 business_entity_id 过滤、都不带（回归）
- 同步 `docs/openapi.yaml` 与 `docs/api-contract.md`（本仓有 `OpenApiContractConsistencyTest` 契约门禁，不同步会红）

## 不做的事

- 不新增后端端点（只窄幅扩展既有 `/token-usage`）
- 不动 `AgentCostPage`（它已有成熟的成本视图，本票只补运行记录页）
- 不改 `token_usage` 的后端存储结构
- 不做跨页全局汇总（只汇总当前筛选）

## Acceptance Criteria

- [ ] Token 列不再出现原始 JSON；显示总 token（千分位）+ 入/出次行
- [ ] `parseTokenUsage` 运行时逐字段校验，缺字段/形状异常返回 null 而非抛错；有单测覆盖
      （正常、缺字段、字段类型错、null、非对象、多余字段）
- [ ] 解析失败显示 `—`，不回退打印 JSON
- [ ] 顶部汇总条随当前筛选变化，与列表查询同源（**含 outcome 与 business_entity_id**，见「裁定」段）
- [ ] 后端 `/token-usage` 新增的两个参数均为**可选**，缺省时行为与扩展前完全一致（老调用零回归）
- [ ] 后端测试覆盖：带 outcome 过滤、带 business_entity_id 过滤、都不带（回归）
- [ ] `docs/openapi.yaml` 与 `docs/api-contract.md` 已同步（`OpenApiContractConsistencyTest` 必须绿）
- [ ] `runs_without_token_usage > 0` 时明确标注未计量运行数
- [ ] 汇总的加载/失败/空三态齐全；失败可重试且不显示为 0
- [ ] `npm run typecheck && npm test && npm run build` 全绿

## Files likely affected

- `frontend/src/pages/agents/AgentRunsPage.tsx`
- `frontend/src/pages/agents/agentPresentation.ts`（新增 parseTokenUsage）
- `frontend/src/api/agentTypes.ts`（如需收窄 token_usage 类型）
- 对应测试

## 工作区纪律

多会话并行：禁 `git add -A` / `git commit` / `git checkout|restore|stash`。只改点名文件。
backend 的 `mcp/McpServer.java`、`McpWriteGate.java` 是别人的在制品，不要碰。

## Risk

低。纯展示层改动，无写操作、无后端改动。
唯一注意点是 `token_usage` 是 `unknown` 的线上历史数据，解析必须防御性——
这也正是本票要求单测覆盖异常形状的原因。
