# Agent 决策层 map

Type: map
Status: completed

## 目标

在既有 Spring Boot 底座上建立 LangChain4j Agent 决策/调度层：扩展 MCP 工具面，在采购比价、数据查询、意图识别三个关键环节提供受管 Agent，全部只读建议、写操作仍走人工确认。

## 依赖图

```text
01 langchain4j 基础
  └─> 02 Agent Registry + Runtime
        └─> 03 Agent ↔ MCP 工具绑定
              └─> 04 MCP 领域只读工具
                    ├─> 05 采购比价 Agent
                    ├─> 06 数据查询 Agent
                    └─> 08 Agent 可观测性
07 意图识别 Agent（复用） — 依赖 02、08
09 评测基线 — 依赖 05、06、07
```

## 实施顺序

1. 01 → 2. 02 → 3. 03 → 4. 04 → 5. 05/06（可并行）→ 6. 08 → 7. 07 → 8. 09

## Tickets

| ID | 票 | 依赖 | 状态 |
|---|---|---|---|
| 01 | LangChain4j 基础接入 | — | resolved |
| 02 | Agent Registry + Runtime | 01 | resolved |
| 03 | Agent ↔ MCP 工具绑定 | 02 | resolved |
| 04 | MCP 领域只读工具扩展 | — | resolved |
| 05 | 采购比价 Agent | 03、04 | resolved |
| 06 | 数据查询 Agent | 03、04 | resolved |
| 07 | 意图识别 Agent（复用注册） | 02、08 | resolved |
| 08 | Agent 可观测性 | 02、03 | resolved |
| 09 | Agent 评测基线 | 05、06、07 | resolved |
