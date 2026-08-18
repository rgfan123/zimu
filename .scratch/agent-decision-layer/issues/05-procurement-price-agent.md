# 05 — 采购比价 Agent

**What to build:** 针对采购工单/SKU 的比价建议 Agent：只读调用 04 票的价格/库存/采购工具，输出结构化比价建议；低置信度或信息不全时明确要求人工，绝不触发写操作。

**Blocked by:** 03 — Agent ↔ MCP 工具绑定；04 — MCP 领域只读工具扩展

**Status:** resolved

## 行为

输入（结构化）：`procurement_ticket_id` 或 `sku_id` + 可选数量。

Agent 依次（工具调用序列可观测）：
1. `get_procurement_ticket` 获取缺口与上下文（或 `get_sku`）；
2. `search_skus` / `get_sku` 获取目标 SKU 进货价/零售价；
3. `list_provider_skus` 获取各履约方外部编码与映射；
4. `get_inventory_overview` / `get_inventory_detail` 确认可用库存。

输出（结构化记录，AI Service 严格 schema）：
```json
{
  "target_sku": "SKU-...",
  "requested_quantity": "number",
  "inventory": {"available": "number", "shortage": "number"},
  "candidates": [{"provider_code": "...", "price": "12.34", "price_basis": "sku_commercial_price|provider_sku", "note": "..."}],
  "recommendation": {"provider_code": "...", "reason": "..."},
  "missing_fields": ["..."],
  "confidence": 0.0,
  "requires_human": true
}
```

策略：
- 无候选 / 无价格 / 字段缺失 / 低置信度 → `requires_human=true`，只给出可复核的事实摘要；
- 只读工具集：该 Agent 的 `tool_names` 白名单不含任何写工具，运行时强制；
- 建议不落业务表，仅进入 Agent 运行审计与可观测记录。

## 非范围

- 自动发起采购、自动下单、修改工单；
- 供应商报价抓取（一期无报价数据源，只比 `SkuCommercialPrice` + `ProviderSku`；范围已确认，2026-08-16）。

## 验收标准

- [x] 结构化输出 schema 校验通过率 100%（固定评测集）；
- [x] Agent 白名单只含只读工具，无任何写工具可被调用；
- [x] 缺价格/缺候选/低置信度全部 `requires_human=true`；
- [x] 每次运行留下完整工具调用序列审计（08 票启用后核验）；
- [x] 评测集跑分记录在 09 票基线中；
- [x] 价格输出 decimal-string SCALE=2。

## 验证原则

- 用固定评测集验证，不以“看起来合理”为验收；
- 写操作不变式必须有自动化断言（Agent 永不调用写工具）。

## Answer

**2026-08-16 收票。** 全部验收项完成，未改动 01/02/03/04 已交付文件（含 `AgentRegistryConfiguration.java`），未新增 Flyway 迁移、未落任何业务表。

**交付内容（新包 `cn.zimu.fulfillment.agent.procurement`，9 个主类 + 4 个测试类）：**

- `ProcurementPriceAgentConfiguration`：独立 `@Configuration` 注册 `AgentDefinition` bean（slug=`procurement-price-agent`，`prompt_version=procurement-price-v1`，`model_ref=app.agent`，enabled=true），自动进入 02 票注册表；`tool_names` 只含 04 票 11 个只读工具，不含任何写工具；system prompt 含工具调用序列与输出规则；
- `ProcurementPriceAgent`：服务编排（注册表解析/enabled 判定/run_id/AGENT 审计 `agent.procurement-price-agent.run`），输入 `ProcurementPriceInput`（ticket_id 或 sku_id + 可选数量，`INVALID_PARAMETERS` 不进模型），建议不落业务表——随 `responsePayload.recommendation_summary`（可复核事实：candidates 价格/缺失项/置信度）进 AuditLog；
- `ProcurementPriceAgentRuntime` + `ProcurementPriceGateway`：AiServices 结构化输出，按票 schema（snake_case `@JsonProperty`，兼容 LangChain4j 文本指令的 camelCase `@JsonAlias`）；未配置模型 fail-closed（AGENT_MODEL_NOT_CONFIGURED）；schema 不符 → AGENT_OUTPUT_INVALID；HTTP 失败 → AGENT_MODEL_CALL_FAILED；
- `ProcurementPricePolicy`：策略确定性落地——无候选/无价格（含价格非 SCALE=2 视为缺价格）/字段缺失/低置信度（<0.6）/库存未知 → `requires_human=true` 且 `recommendation=null`（只给事实）；价格统一 `SkuCommercialPrice` SCALE=2 规范化；
- 工具调用序列可观测：行为 1→4 步（get_procurement_ticket/get_sku → search_skus/get_sku → list_provider_skus → get_inventory_overview/get_inventory_detail）在 stub 端到端中逐帧断言；08 票启用后按 run_id 核验完整审计链。

**评测集（09 票基线种子，`ProcurementPriceEvalTest` 内嵌 fixture，版本 `procurement-eval-v1`，7 例）：**

- 正常比价（ticket 输入，2 候选+推荐）、正常比价（sku 输入、无数量）、camelCase 模型输出兼容（真实模型按文本指令输出的命名路径）、无候选、缺价格、低置信度+字段缺失、schema 不符（负例，稳定 AGENT_OUTPUT_INVALID）。

**写操作不变式（自动化断言）：** 定义层 `tool_names` 与写工具清单交集为空（单元硬编码对照 + Testcontainers 对照真实 `McpWriteTools`）；绑定层 `AgentToolBinding` 暴露工具恰为白名单；协议层 stub 首帧 exposed tools 恰为 11 个只读工具且无写工具名；白名单外工具经绑定执行器调用稳定拒绝（MCP_INTERNAL_ERROR）。

**测试与回归（backend）：** `mvn -q test-compile` 通过；新增 4 测试类 34 测试全绿（Policy 13 / Eval 7 / Agent 编排 11 / 写操作不变式 3，模型调用全部走本地 JDK HttpServer stub，无真实 key）；回归 `-Dtest='Agent*,ProcurementPrice*'` 全绿；全量 `mvn -q test` 99 类 665 测试 0 失败 0 错误 7 skipped。

**执行备注（如实报告）：** 交付期间发现并发会话（06 票）在同一 backend 目录并行工作——中途出现 `DataQueryAgentDefinitionTest` 编译中断与全量测试 Testcontainers 单 fork 资源争抢（EOF/连接关闭类无关错误），均经隔离复跑验证为环境干扰：受影响测试类单独运行全部通过，最终全量复跑全绿。写操作不变式集成测试（Testcontainers）因并发会话对 `RequestContextFilter` 鉴权策略的进行中重构（test-fixtures profile）需要 `@Import(TestRequestAuthenticationConfiguration.class)`（测试侧 fixture，非生产代码）方可加载上下文；该导入仅存在于本票测试类。

**遗留事项：** 08 票启用后按 `run_id` 核验工具调用序列落表与脱敏；`@Import(TestRequestAuthenticationConfiguration.class)` 依赖并发会话测试侧 fixture，其重构完成后如移除该 fixture 需同步调整本票集成测试；09 票跑分器可基于本票评测集与指标（schema 通过率/requires_human 召回/写工具零调用）继续版本化；`recommendation_summary` 进 AuditLog 为 08 票前的过渡观测路径。

## Comments

**2026-08-16 code review 修复轮（硬缺陷 A1）。** Review 发现：`ProcurementPriceAgent.recommendationSummary()` 内嵌套 map 用 `Map.of` 组装，`Candidate.price()`/`providerCode()` 可为 null（缺价格场景，见 `ProcurementPricePolicyTest.missingPriceForcesRequiresHuman`），`Map.of` 遇 null 抛 NPE 且发生在 `recordAudit` try 块内被 `catch (RuntimeException ignored)` 吞掉 → 缺价格核心场景下该次运行审计整体不落，违反"每次运行留下完整工具调用序列审计"与 Answer 声称的 `recommendation_summary` 进 AuditLog。

**修复内容（`ProcurementPriceAgent.java`，仅本缺陷相关，未动 06 的 DataQuery*/AgentRuntimeFacade）：**

- 嵌套 map 全部改为 `LinkedHashMap` 逐键 `put` 的 null 安全组装：候选摘要抽 `candidateSummary()`（provider_code/price/price_basis 可空照常落库）、推荐摘要抽 `recommendationSummary()`（provider_code/reason 可空）；`recommendationSummary()` 不再使用任何 `Map.of`/`Map.ofEntries`。
- 修正 javadoc：原 :130 注释只覆盖顶层"可空值用 LinkedHashMap"，已改为明确"顶层与嵌套一律用 LinkedHashMap，避免 Map.of/Map.ofEntries 对 null 的 NPE 在 recordAudit 的 try 内被吞掉而丢审计"。

**新增测试（`ProcurementPriceAgentTest.missingPriceStillRecordsAuditPayloadWithNullCandidatePrice`）：** 复刻缺价格场景（requires_human=true、recommendation=null、candidate price=null），断言 audit payload 完整落库——status=SUCCESS、`recommendation_summary` 存在、requires_human=true、missing_fields 含 price、candidates 含 price=null 项且 price_basis 保留、recommendation 键不携带。测试类 11 → 12 个测试。

**验证（backend）：** `mvn -q test-compile` 通过；`mvn test -Dtest='ProcurementPrice*'` 35 全绿（AgentTest 12 / Eval 7 / Invariant 3 / Policy 13）；回归 `mvn test -Dtest='Agent*'` 79 全绿。未 commit、未 push。
