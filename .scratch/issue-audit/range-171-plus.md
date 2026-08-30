# Issue 审计 · #171 及以上（open）

- 审计时间：2026-08-29
- 代码基线：`/Users/jerry/zimu-work/integration`，分支 `jry/integration-20260828`（含 `master`，领先 124 个提交；生产 `flyway_schema_history` 最新为 **V84**，与该分支一致 → 该分支即生产真相线）
- 范围：`#171–#198` 共 27 张 open 票（`#177` 是 PR 不是 issue）
- 口径：只读代码 / git 历史 / 生产库只读；「看起来做了」不算

---

## 一、已关闭（3 张）

### #172 — source-order-agent-file-intake: 01 可配置 Agent 执行预算替代固定 8 轮

**关闭依据**：PR #177，合并提交 `8e2ca85c`（已在 `master` 与集成分支）。

- 固定 8 轮常量在 `agent/` 包已不存在，改由 `agent/AgentExecutionBudget.java`（record：`maxModelCalls` / `maxToolCalls` / `maxDuration` / `maxRepeatedToolCalls`，`:15-28` 四项都有下界校验）承载
- 四项约束的执行点：`agent/LangChain4jRuntimeAdapter.java:109`（模型调用数 + 截止时间）、`:123-124`（工具调用数）、`:146`（重复工具调用）、`:199-207`（单次模型调用也被剩余时间硬截）
- 未显式传预算走服务端默认值：`LangChain4jRuntimeAdapter.java:93-95`；默认值来自 `application.yml:255-258`（`AGENT_MAX_MODEL_CALLS:24` / `AGENT_MAX_TOOL_CALLS:64` / `AGENT_EXECUTION_TIMEOUT_MS:300000` / `AGENT_MAX_REPEATED_TOOL_CALLS:2`），可按调用方分别配置
- seam 测试 `backend/src/test/java/cn/zimu/fulfillment/agent/LangChain4jRuntimeAdapterTokenAccountingTest.java`：`validToolChainCanContinueBeyondEightTurnsWhenBudgetAllows`（直接钉死「不再是 8 轮」）、`modelCallBudgetExhaustionHasAStableFailureCode`、`repeatedIdenticalToolCallIsRejectedAsNoProgress`、`deadlineStopsLaterToolSideEffectsInTheSameModelTurn`、`toolTurnsAreCountedNotOnlyTheFinalTurn`（观测累计所有轮次）；另 `LangChain4jRuntimeAdapterTest.java:194 / :153`（预算耗尽 vs 输出非法两类语义分开）

### #173 — source-order-agent-file-intake: 02 202 异步附件存证与已知模板导入

**关闭依据**：同 PR #177（正文写了 `Closes #173`，因为不是合到默认分支，GitHub 没自动关）。

- 入口：`file/SourceOrderIntakeController.java:30`（`POST /api/v1/source-order-intake-jobs`，multipart）、`:36` `Idempotency-Key`、`:52` `requireIdempotencyKey`；202 见 `file/SourceOrderIntakeService.java:71`
- 存证 / 受控下载：`file/SourceOrderIntakeFileStore.java`；`SourceOrderIntakeController.java:61` `GET /{job_id}/file`
- 恢复：`file/SourceOrderIntakeWorker.java:42` `@Scheduled` + `:50` `tasks.claim(TASK_TYPE, owner, lease)`（`lease-seconds:120`）
- 测试 `backend/src/test/java/cn/zimu/fulfillment/file/SourceOrderIntakeApiTest.java` 9 个用例，逐条对上验收：MIME/扩展名/魔数一致性、体积上限稳定错误码、损坏工作簿先留证、重复内容复用 job、同幂等键不同内容拒绝、已知模板后台导入、legacy xls 走 workbook parser、worker 重试耗尽收口、失败原件受控下载
- 前端改异步 + 轮询：`frontend/src/api/endpoints.ts:772/775/777`、`frontend/src/pages/fulfillment/SalesOutboundPage.tsx:85/98/130/164/174`
- 迁移 `db/migration/V72__source_order_intake_jobs.sql`
- **生产实证**：生产库 `app.source_order_intake_jobs` 存在且有 3 条任务记录 → 已上线并被真实跑过

### #178 — 会话 Agent 分流 01：消息按会话绑定路由到 Agent（只读问答打通）

**关闭依据**：`846fae8d`（实现）+ `ee98aa85` / `e3b69f06`（测试），经 `88cc2622` 合入集成分支。

- 路由层：`message/WecomChatAgentRoutingService.java`（477 行）+ `message/WecomChatAgentWorker.java`，前置在解释/分流之前；出口 `connector/wecom/WecomChatAgentReplyDispatcher.java`
- 未绑定 → `:93-94` `AGENT_BINDING_MISSING` 回落原 `INTERPRET_MESSAGE` 任务；业务意图 → `:100`；超时/异常/外呼未知 → `:117-120` / `:157-159` / `:167-170` / `:311`，统一 `:327 fallback()` + `:353` 审计日志
- 只读工具强制：`agent/AgentToolBindingFactory.java:112-144 bindReadOnlyModules`（`readOnly()==true` 且在允许模块内才暴露，其余入 `denied`）；旁路兜底在 `agent/AgentToolInvoker.java:113` 返回 `TOOL_NOT_AUTHORIZED` 并留观测
- 端到端：`backend/src/test/java/cn/zimu/fulfillment/message/WecomChatAgentRoutingIntegrationTest.java`（448 行 / 10 用例），六条验收逐条对上，含 `unboundConversationKeepsOriginalTaskAndIntakeBytesUnchanged`（未绑定逐字节不变）与 `boundQuestionRunsReadOnlyAgentAndDeliversAnswerWithoutIntakeCase`（不再产生复核工单）
- 权限拒绝测试：`AgentToolBindingFactoryTest.sessionRouteExposesOnlyReadOnlyMasterdataInventoryToolsAndRecordsWriteDenial`

---

## 二、犹豫过、但没关（1 张）

### #193 — jd-mapping-gate: 06 A 族文案精确化

**为什么犹豫**：4 条验收里有 2 条其实已经满足，而且是本组票里唯一有实质代码的一张。

- 已满足：占位符单位修复 `sku/ShipmentJdSkuMappingGateService.java:331` `COALESCE(sk.unit, ol.unit_snapshot)`（+ `:321-326` 注释、组件侧 `:386-391`），并且**有测试钉死** —— `backend/src/test/java/cn/zimu/fulfillment/sku/ShipmentJdSkuMappingGateApiTest.java:685-698 treatsSkuUnitAsAuthoritativeWhenOrderLineUnitSnapshotIsJustASourcePlaceholder`（`:698` 直接把 `unit_snapshot` 置成 `'来源数量单位'`），正是票面点名「必须有」的那条回归；「缺映射」与「映射已停用」也确实是两条码（`:466` `MAPPING_MISSING` / `:471` `MAPPING_INACTIVE`）
- **没关的理由**：票面第一条验收（也是标题的前半句）没做 —— `ShipmentJdSkuMappingGateService.java:369` 仍是 `sku != null && sku.active()` 取反，`:373-382 loadSkuState()` 在 `skus` 行整行不存在时返回 null，于是 `:464` 照样报 `INTERNAL_SKU_INACTIVE`。**「不存在」和「已停用」仍然共用一句话**，标题写的 bug 原样在。第四条（`UNIT_CONVERSION_MISSING` 文案讲清哪个 SKU 的什么单位）也没做，`:483-485` 仍是通用句
- 已满足的那两条都是 `ef37bea1`（2026-08-27）带来的既有成果，本来就是票面「别再修一遍」那段说的东西 —— 不构成「本票做完了」

---

## 三、确实还没做（23 张）

### source-order-agent-file-intake（#171、#174–#176）

| 票 | 现状 |
|---|---|
| **#171** spec | 子票 #172/#173 已完成，但 #174/#175/#176 一行没写 —— PR #177 正文自己写了 `Scope boundary: This PR intentionally does not implement #174, #175, or #176`。按「spec 子票全完才关」规则保持 open |
| **#174** 未识别模板提取为候选批次 | 全仓 `TemplateProfile` / `trust_template` / `AutomaticRelease` **零命中**；`file/SourceOrderIntakeProcessor.java` 只有确定性解析链，没有 Agent fallback 与证据提取 |
| **#175** 模板信任与候选批次 MCP 放行 | 同上，无 TemplateProfile 实体、无 `trust_template` 意图、MCP 无 release 工具 |
| **#176** 受信模板整批自动成单与京东放行 | 同上，前置票都没做 |

### 会话 Agent 分流（#179、#180）

| 票 | 现状 |
|---|---|
| **#179** 回复权限约束 Agent 的嘴 | `connector/wecom/WecomChatAgentReplyDispatcher.java` 的类注释里白纸黑字写着「会话 reply_mode 是否允许 Agent 开口属于 #179，后续应只在本组件增加策略门禁」—— 该出口当前**没有任何 `RECEIPTS_ONLY` 判定**；`WecomChatAgentRoutingService` 虽然注入了 `WecomChatReplyPolicyService`，但只用在 `:152 outboundChatId()` 与 `:195/:243 assignedAgent()`，与 `reply_mode` 无关。全仓 `RECEIPTS_ONLY` 的消费方只有 controller 的入参校验 |
| **#180** 会话管理页显示分流实况 | `frontend/src/pages/agents/ReplyPolicyPage.tsx:101-143` 只有「会话 / 类型 / 最近活跃 / 服务 Agent / 回复权限 / 操作」六列；「最近活跃」取的是 `last_seen_at`（会话目录的活跃时间，与 Agent 是否应答无关）。没有「最近 Agent 应答时间 / 近 7 日应答次数 / 近 7 日回落次数」，也没有「已绑定，尚未应答过任何消息」的显式提示 |

### MCP 发货闭环（#181）

**#181** — 票面要的四个工具 `refresh_platform_orders` / `list_import_batches` / `get_import_batch` / `confirm_import_batch` 在 `backend/src/main/java/cn/zimu/fulfillment/mcp/` 下**一个都不存在**（全仓 grep 零命中；`get_import_batch_progress` 是另一个既有工具，别看混）。当前写工具只有 `confirm_order_draft` / `submit_jd_outbound` / `create_agent_draft` / `reinterpret_submission` / `submit_order_draft_suggestion` / `submit_review_request` / `submit_supplementary_material`（`mcp/McpWriteTools.java`）。票面点名要先设计的写工具开闸机制也没有：**`McpWriteGate` 这个类在本分支根本不存在**。规格原件仍在 `.scratch/mcp-order-fulfillment/issues/04-mcp-refresh-platform-orders.md`。

### bundle-in-drafts（#182–#188，7 张全未做）

| 票 | 现状 |
|---|---|
| **#182** 草稿行能承载礼包 | `app.order_draft_lines` **没有 `bundle_id` 列** —— 建表在 `db/migration/V8__add_message_pipeline.sql:111-125`（只有 `sku_id`），之后所有迁移里再没出现过 `order_draft_lines`；**生产库 `information_schema` 实查也只有 12 列，无 `bundle_id`**。结构上仍存不下礼包，票面描述的现状原样在 |
| **#183** 单品 / 礼包两级选择器 | 前端全仓 grep `单品` **零命中**；`frontend/src/pages/workbench/OrderDraftReviewPanel.tsx` 与 `orderDraftReview.ts` 里 `礼包` / `bundle` 均零命中 |
| **#184** 解释器商品自动匹配到礼包 | 候选解析 `message/WecomOrderDraftFactory.java:211 skuCandidates()` 只跑 `SKU_CANDIDATE_SQL`，返回体只有 `sku_id/sku_code/product_name/...`，没有礼包候选、没有类型标记；`:275` 仍无条件落 `line_N_sku` 到 `missing_fields` |
| **#185** 三个只读礼包工具 | `list_bundles` / `get_bundle` / `find_bundle_candidates` 全仓零命中 |
| **#186** `simulate_bundle` / `suggest_substitutes` | 全仓零命中 |
| **#187** `explain_unmapped_bundle` | 全仓零命中 |
| **#188** `draft_bundle` / `revise_bundle_draft` + 写闸接线 | 两个工具零命中；`McpWriteGate` 类在本分支不存在（票面说它「已写好但是死字段」—— 那份代码不在这条线上） |

> 分支排查：`jry/draft-bundle-line` 与 `jry/bundle-mcp-read` 都是**空壳**（`git log --oneline jry/integration-20260828..<branch>` 均为空，指针停在 `0e1c2a41`，该提交已在集成分支上）。
>
> 工作区里未提交的 `frontend/src/pages/product/BundleMappingsPanel.tsx` / `bundleMappings.ts` / `backend/.../product/SourceBundleMappingPatch.java` 是**「来源礼包映射」**（把来源渠道的礼包引用映射到主数据礼包，见 `bundleMappings.ts:1-2` 文件注释与 `SourceBundleMappingPatch.java:10-18`），跟 bundle-in-drafts 是两个主题，别当成本组票的进度。

### jd-mapping-gate（#189–#197，9 张全未做）

全组零落地。硬信号：`git log --all --grep` 搜 `mapping-gate` / `处置族` / `disposition` / `就绪度` 均为空；门禁主文件 `backend/src/main/java/cn/zimu/fulfillment/sku/ShipmentJdSkuMappingGateService.java` 最后一次被触碰就是票面自己点名的 `ef37bea1`（2026-08-27），之后无提交。四值处置族枚举 `LOCAL_CONFIG` / `LOCAL_DATA` / `EXTERNAL_TRANSIENT` / `EXTERNAL_FACT` 全仓 **0 处命中**。票面引用的规格 `.scratch/jd-mapping-gate-diagnosis/spec.md` 在本工作区**不存在**。

| 票 | 现状一句话 |
|---|---|
| **#189** 处置族成为一等字段 | `ShipmentJdSkuMappingGateService.java:643-659 issue()` 只产 `code`/`message`/`missing_field`，无族；`fulfillment/ShipmentJdStockCheckService.java:176` 仍硬编码 `code = "JD_SKU_MAPPING_GATE_BLOCKED"`（`:172-173` 注释还写着「口径不变」）；`mapping_issue_code` 不但没取消，反而长出了新消费方（`connector/schedule/AutoShipReasons.java:36/122/153`、前端 `pages/workbench/stockBlockerCases.ts:72`）；`jdSkuMappingReview.ts:48/59` 仍是票面点名禁止的 `?? '需进一步核对'`；对账测试 `frontend/test/enumLabelReconciliation.test.ts:12-22` 只有 R1–R7 + A1–A3，没有读门禁源码的规则 |
| **#190** 京东映射就绪度视图 | `sku/` 包只有两个 POST 核对端点（`JdSkuMappingCheckController.java:27/:38`），无只读就绪度查询；`SkuMappingsPage.tsx:725-730` 只有 `sku` / `bundle` 两个 Tab，页内仍是票面点名有盲区的「京东件数换算候选」 |
| **#191** 下一步随原因变化 | `ShipmentJdSkuMappingGateService.java:613-631` 的 `gateMaintenance()` / `mappingMaintenance()` 仍**无条件**返回 `OPEN_SKU_MAPPING`；责任团队 `:587` 写死 `SKU_OPS`、库存侧 `ShipmentJdStockCheckService.java:481` 写死 `FULFILLMENT_OPS`（票面说的「真相派到别人队列」原样在）；标题仍恒为 `reasonLabels.ts:34 JD_SKU_MAPPING_BLOCKED: '京东商品映射未通过'`；`pages/workbench/orderFacts.ts` 无 `JD_SKU_MAPPING` 事实组 |
| **#192** 明细完整性契约 | `ShipmentJdSkuMappingGateApiTest.java` 无「BLOCKED ⇒ 明细非空且每条带 code」不变式测试；`ShipmentJdStockCheckService.java:162-168` 兜底仍在编那条通用文案 blocker（没有抛出、没有审计）；「本次未查询京东库存」无任何显式标注（`Probe.blocked(...)` 的 status 在 `:250/:518` 被 `overallObservation` 丢弃） |
| **#193** A 族文案精确化 | 见上文第二节（2/4，已实现的两条都是 `ef37bea1` 的既有成果） |
| **#194** 事件流能回答「为什么」 | `ShipmentJdSkuMappingGateService.java:156-163` 的 `JD_SKU_MAPPING_CHECKED` payload 仍只有计数，无 issue code 集合与族分布；无按原因分组的可跑 SQL 测试；`review_cases.detail` 仍就地覆盖（`:591`） |
| **#195** 告警码可见 | `GOODS_STATUS_UNKNOWN`（`:549`）/ `NAME_MISMATCH`（`:554`）在两张前端标签表（`jdSkuMappingReview.ts:12-27`、`reasonLabels.ts:84-99`）里都没有中文标签；`api/types.ts:1129-1136` 的门禁结果只有 `warning_count: number`，不含 warnings 数组，前端根本无从渲染 |
| **#196** 外部瞬时故障告警 + 有限重试 | `sku/JdSkuMappingCheckService.java:163-166` 明写「SKU 映射告警暂不能落库…已降级审计通道」；`ShipmentJdSkuMappingGateService` 构造器（`:46-61`）压根没有 `OperationalAlertService` 依赖；`sku/JdGoodsReadOnlyVerifier.java:44-58` 单次调用、无重试/退避；C 族文案未改。唯一意外满足的是硬约束「内层幂等键每次全新」（`ShipmentJdStockCheckService.java:107` `"jd-stock-gate-" + UUID.randomUUID()`），但缺票面要求的解释性注释 |
| **#197** 零检查判阻断 | `ShipmentJdSkuMappingGateService.java:153` 仍是 `passed = blockingIssues == 0`，对 `checkedMappings` 无下限断言，`:431-440 validateGate()` 只校验 provider 类型与 items 非空；组件加载 `:391` 仍是内连接 `JOIN app.skus`，无行数比对；两个 fail-open 场景无测试 |

### mcp-surface（#198）

**#198** — 按指示**不关**（正在做）。旁证：`mcp/McpToolRegistry.java` 仍是单一 `app.mcp.modules` 一份配置同时喂协议面与 Agent 面（`:55-85` 一次过滤后 `byName` 直接给两条消费路径），空值语义在本分支还是「全部已知模块」（`:88-92` 注释），与票面说的「另一会话已改成不开」还没合流；分支 `jry/mcp-surface-split` 目前也是空壳（指针停在 `2b817fdb`，已在集成分支上）。

### product-ops-mcp（#199–#202，审计期间新建）

这四张票是**本次审计开始之后**才建的（`createdAt` 2026-08-29T10:41–10:43Z，我取快照是 10:28Z），不在原始范围内，一并快速核了一眼：**全未做**，保持 open。

- `export_product_catalog`（#200）、`upsert_sku` 类写工具（#201）、`masterdata-write` 模块（#202）在 `backend/src` / `frontend/src` 全仓**零命中**
- `search_skus`（#199）作为既有工具是存在的，但票面要的是「精确码匹配 + 品类/标签/状态筛选」的增强，没有深挖，按未做处理

---

## 四、给主人的两条提醒

1. **#189 的方向可能需要你再拍一次板。** 票面写「前端其实有一张覆盖 14 个阻断码的中文标签表，不要另起第二张」—— 但现在已经有两张了：`pages/workbench/jdSkuMappingReview.ts:12-27`（喂 `detail.affected_shipment_items`）和 `constants/reasonLabels.ts:84-99`（喂 blockers），而且 `reasonLabels.ts:71-83` 的注释专门论证了「为什么要单开 `MAPPING_ISSUE_LABELS`」，并把 `mapping_issue_code` 当成长期契约在用（`stockBlockerCases.ts:26-33`、`blockerReasonLabel()`、后端 `AutoShipReasons.java`）。#189 要做的不是在空地上盖房，而是**拆掉一套刚建好、带完整设计论证的既有链路**。动工前值得确认这个反转是不是你现在想要的。

2. **`#189–#197` 引用的规格文件 `.scratch/jd-mapping-gate-diagnosis/spec.md` 在这个工作区拿不到**（`.scratch/` 下 32 个子目录都没有它），`#189` 提到的 `.scratch/frontend-audit-fixes/issues/09-jd-stock-blocker-ux.md` 也不存在（该目录只有 01–05）。要开工的话得先把那份诊断规格找回来。
