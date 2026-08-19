# Agent 平台化：实施地图 map

Type: map
Status: charting（实施阶段）

## Destination

把 `.scratch/meta-agent-platform/` 已完成的设计（12/12 决策票 resolved）落地为可运行的 Agent 平台：定义数据化（DB 真源 + 版本链 + 草稿确认）、四路径收敛到薄 Adapter（LangChain4j 运行时）、MCP 权限隔离、门禁守卫、评测数据化（INVARIANT 基线 + QUALITY/promptfoo）、Meta-Agent（自然语言建 Agent）、管理 REST API（/api + /internal 镜像，202 异步）。每批迁移前后跑基线比对，保持 CI 绿。

## Notes

- **设计真源**：`.scratch/meta-agent-platform/map.md`（Decisions so far + Schema 增量）与 12 张决策票的 `## Answer`（03 数据模型 / 04 Runtime Adapter / 05 门禁守卫 / 06 Meta-Agent / 07 评测 / 08 权限 / 12 REST），实施细节一律以决策票 Answer 为准。
- **修正记录（T01 显式承担）**：03 的 Schema 增量遗漏了 `output_schema` 列——04 决策要求定义携带输出 JSON schema（动态约束路径），`agent_definitions` 须增加 `output_schema JSONB`。
- 红线（实施必须遵守）：写操作人工确认（Meta-Agent 只能写草稿）；业务 Agent 写工具零调用不变式（meta-agent 是唯一例外：allow_write=true + 三重约束）；密钥/凭据绝不进 DB、日志、DTO；服务端 allowlist 投影 provider/model/prompt-version；审计与观测失败隔离；评测基线门禁（AgentEvalBaselineTest）每批迁移前后比对。
- 基座已提交（9152d01）；本 effort 的每个完成票应独立提交（参照 03「播种两步走」的提交纪律：迁移与删代码分开 commit）。
- 跑分器与迁移约束：换低层 ChatRequest 本身会改工具调用序列——任何改序列的批次必须按 09 流程重钉基线并记录。

## 依赖图

```text
T01 迁移与播种 ──> T02 注册表切 DB ──> T04 Adapter 骨架/A 路径 ──> T05 B/C 收敛 ──> T06 意图桥
                                      │                          └────────────> T09 QUALITY
T01 ────────────> T03 评测数据化 ─────┘
T02 ────────────> T07 MCP 权限隔离 ──> T08 门禁守卫引擎 ──> T10 Meta-Agent 工具面 ──┐
                                                                                    ├─> T13 Meta 端点
T02/T03 ────────> T12 读端点 + /internal 镜像                                        │
T05/T08/T03 ────> T11 异步任务基建 + 写端点 ────────────────────────────────────────┘
```

## 实施顺序（预期）

T01 →（T02 ‖ T03 并行）→（T04 → T05 → T06）‖（T07 → T08）→ T09 / T10 → T11 / T12 → T13。
每票完成独立提交；T04/T05 迁移前后跑 `mvn -q test -Dtest='AgentEval*'` 比对。

## Tickets

| ID | 票 | 依赖 | 状态 |
|---|---|---|---|
| 01 | V33 迁移：三表落地与播种（含 output_schema 修正；版本号因 V30–V32 已占用顺延） | — | resolved |
| 02 | 注册表切 DB 真源 + 删代码定义 | 01 | resolved |
| 03 | INVARIANT 评测数据化 + 基线门禁读 DB | 01 | resolved |
| 04 | Runtime Adapter 骨架 + 通用门面（A 路径） | 02、03 | resolved |
| 05 | B/C 路径收敛（采购比价/数据查询） | 04 | open |
| 06 | D 路径意图桥适配 | 05 | open |
| 07 | MCP 权限隔离（读写元数据 + 调用期复核 + stdio 只读） | 02 | open |
| 08 | 门禁引擎 + 运行期 PII 守卫 | 07 | open |
| 09 | QUALITY 链路：promptfoo 执行器 + 异步评测 | 03、05 | open |
| 10 | Meta-Agent 工具面（list_agent_tools + 定义写工具） | 02、08 | open |
| 11 | 异步任务基建 + 定义域写端点（202/confirm/reject/set-enabled/rollback） | 03、05、08 | open |
| 12 | 读端点 + /internal 只读镜像 | 02、03 | open |
| 13 | Meta-Agent REST 端点（202 闭环） | 10、11 | open |
