# 02 — 注册表切 DB 真源 + 删代码定义

**What to build:** Agent 定义的唯一真源从代码变为 `agent_definitions`（03 contract 步）：启动时全量加载 DB 行构造 `AgentRegistry`（`AgentRegistryHolder` volatile 换实例——确认/回滚后无需重启即可感知）；内存 `AgentDefinition` 扩展字段（`version`/`status`/`activatedBy`/`activatedAt`/`allowWrite`/`guardExemptions`/`outputSchema`）；`AgentRegistryChangeAuditor` 补 ACTIVATED/RETIRED 审计事件（复用既有 diff 机制）；删除三个代码定义 Configuration（data-query / procurement-price / intent-recognition，`ProcurementPriceAgentConfiguration` 只摘方法保留可用组件），种子成为唯一来源。

**Blocked by:** 01 — V30 迁移与播种（设计源：meta-agent-platform 票 03）。

**Status:** resolved

- [x] 启动从 DB 加载注册表，无代码定义 bean 残留；启动失败语义（DB 不可用时 fail-closed）不劣于现状
- [x] 草稿确认（测试内直接改 DB 行）后 holder 换实例，运行条件 `status='active' AND enabled=true` 判定正确
- [x] ChangeAuditor 对 ACTIVATED/RETIRED 产生审计事件；AgentRegistryTest / AgentRegistryChangeAuditorTest 全绿
- [x] 删除代码定义后 `Agent*` 测试与基线仍绿（本票不改运行行为）

## Answer

已实现并验证（2026-08-19），经 /code-review（Standards + Spec 双轴）后按发现修复。实现要点：

- **AgentDefinition 版本链扩展**（03）：新增 `version`/`status`(AgentStatus 枚举)/`activatedBy`/`activatedAt`/`allowWrite`/`guardExemptions`/`outputSchema` 七字段；紧凑构造器强制 **active ⇒ activated_by/activated_at 非空**（03「确认事实上行」不变量）。8 参便捷工厂更名 **`ofActiveV1`**（显式 version=1/active/激活事实='system'，仅供测试夹具；生产路径一律经 Repository 全量构造，版本链事实显式传入）。
- **DB 真源 + holder**：`AgentDefinitionRepository`（JdbcTemplate 加载 `status='active'` 行，按 id 序）+ `AgentRegistryHolder`（volatile 引用，`reload()` 对前后实例复用 `AgentRegistryChangeAuditor` 落审计 diff）。启动失败语义不劣于现状：DB 不可用 → 启动失败（fail-closed）。
- **消费方全部改注 holder**（决策 03「确认/回滚后无需重启即可感知」）：`AgentRuntimeFacade` / `DataQueryAgentService` / `IntentRecognitionAgentBridge` / `ProcurementPriceAgent`；slug 常量内联到各消费方（`AGENT_SLUG`）。
- **审计**：`AgentRegistryChangeAuditor` 补 `ACTIVATED`/`RETIRED` 两个 Kind——同 slug 生效版本被替换时旧版本 RETIRED + 新版本 ACTIVATED（版本切换吸收字段级 diff）；新增单元 + 集成断言（holder 换实例后 audit_logs 出现两事件）。
- **删代码定义**（03 两步走第二步）：删除 `DataQueryAgentDefinitionConfiguration` / `IntentRecognitionAgentConfiguration` 整类；`ProcurementPriceAgentConfiguration` 只摘定义 bean，保留 runtime/gateway 装配（bean 改注 holder）。种子成为唯一来源。
- **测试适配**：`AgentSeedFixtures`（与种子身份/白名单一致、system_prompt 为截断节选——完整真源在 DB，注释已如实说明）；`AgentRegistryHolderIntegrationTest`（DB 改行模拟草稿确认 → reload → 新版本生效 + 生命周期审计；enabled 与 status 正交）；`AgentPlatformSeedVerbatimTest` 改为断言 DB 唯一真源 + 上下文无代码定义 bean 残留；观测/桥集成测试改经 `upsertActiveDefinition` 在 DB 注册测试 Agent；AgentEval 基线等 173 例 agent 测试全绿。
- **Spec 评审两处"行为偏离"的处理**：① 评测夹具用截断提示词——跑分器是 stub 模型（脚本化工具调用，不读提示词），基线数字不受影响，且夹具注释已如实声明「完整真源在 DB」；② intent-recognition 的 prompt_version 不再动态镜像 `app.message-interpreter.prompt-version`——DB 真源设计下定义自带 prompt_version，改提示词 = 新版本草稿→确认（03 版本链语义），镜像机制被平台化取代。③ 03 决策的确认审计 `operation=agent.definition.activated, actor=HUMAN` 属确认流程（T11 定义域写端点），本票只交付注册表 diff 的 ACTIVATED/RETIRED（AGENT actor），T11 需按 03 口径补 HUMAN 确认审计。
- **全量测试说明**：本机环境全量套件存在与 T02 无关的抖动（Tomcat 端口占用、12s await 超时；工作区还有并发 WIP——tracking-draft REJECT 等，OrderMapper 已改而测试未同步导致 TrackingDraftApiTest 失败）；唯一稳定失败仍是 `ConnectorApiTest`（zhonghui WIP，T01 已记录）。agent 包 173 例与本票相关测试稳定全绿。
