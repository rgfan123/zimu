Parent spec: #120（D3/D4）。本票由原「比价建议后端使能」重写而来——spec #120 落地后按 ADR 0007-0010 细化，原票面（触发端点+持久化+调度的粗粒度描述）作废，以下为准。

## What to build（纯后端，勿碰 frontend/）

- [ ] **建议引擎（纯规则、零 LLM）**：对每个启用的盯盘品，取当日报盘（#121 表）→ 报盘 ≥5 各剔最高最低 1 个、<5 不剔标"样本不足" → 推荐中间带最低价厂 + 次选 + 对昨日中间价涨跌 → 写建议表（含剔除明细与理由）
- [ ] **调度**：每日 08:00（配置可调）触发采集（#122 脚本）+ 建议生成；手动触发端点（不限频，用户拍板）。⚠️ 前置：先配 ThreadPoolTaskScheduler（现单线程共用，见 scan-hardening 05-backlog 第 1 条），否则会顶掉 InterpretationWorker
- [ ] **留痕**：每次采集+建议生成记一次 `procurement-price-agent` agent_run（含手动触发），复用 AgentRuntimeFacade/JdbcAgentObservability
- [ ] 建议不产生任何业务写副作用（不建工单、不填价格，ADR 0010）
- [ ] 规则全分支测试：≥5/=5/<5/并列极值/仅 1 个报盘/换算待核项不参与计算

## Blocked by

- #121（数据层）、#122（爬虫）
