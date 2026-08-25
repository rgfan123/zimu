# Spec — 岗位工作台：把「报告台」收成四个可交付的作业面

**GitHub:** https://github.com/rgfan123/zimu/issues/103

**Effort slug**: `zimu-workbench`
**基线**: `codex/issue-89-operator-map-v2` @ `69f259f`（含 master 全部内容，63 提交待并入 trunk）
**范围决定**: 四个工作台写全；对象页只写「用哪三个共享组件拼 / 筛什么 / 从哪跳进来」；未被重构碰到的页面不写

---

## Problem Statement

系统的后端履约闭环是完整的：平台拉取 → 导入批次 → 整批确认 → 自动提交京东出库 → 运单回填 → 来源回填表 → 在线推送或离线 Excel。前端却没有任何一个页面对应「一个人一天要做完的事」。

具体表现，全部有代码依据：

1. **默认落地页没有主动作**。`DashboardPage.tsx` 现在（`69f259f`，319 行）已经是调度台——KPI、原因卡、关注行都带 `status/reason_code/responsible_team` 跳到预筛的复核队列（#96 `e3e6b87`）。但它仍然没有一个「开始今天的活」的按钮：`POST /api/v1/platform-orders/refresh` 存在，前端没有任何高频入口调用它。人得先知道该去哪，才能开始工作。

2. **一天的工作被切在三个板块里**。发货员要走 `/fulfillment/sales-outbound`（标签「文件作业」）→ `/workbench/reviews` → `/fulfillment/shipments` → `/fulfillment/outbound-recon`（`hideInMenu`）。这四步是**一条**动线，却分属两个一级板块，其中一个还是隐藏页。导航按技术归类，不按人归类。

3. **岗位词汇存在，但前端不消费**。`app.internal_operators`（V48）已经有 `responsible_team`，`OperatorResolver` 能按团队解析到人；`ReviewCase.responsible_team` 已经是复核队列的一等筛选维度（`REVIEWS_TEAM_PARAM`），`queuePresentation.ts` 已经定义了权威的四个团队（`CUSTOMER_OPS` 客户运营 / `SKU_OPS` 商品运营 / `ORDER_OPS` 订单运营 / `FULFILLMENT_OPS` 履约运营）。**前端从不按团队预筛任何东西**——每个人都看到所有人的待办。

4. **采购能力已接线但没有作业面**。`POST /api/v1/procurement-price-agent/compare` 存在，`/procurement/price-compare` 页面存在——但 `hideInMenu: true`（#98 降级），且 Agent 输出不是任何页面的第一屏。`ProcurementPricePolicy.enforce` 强制 `requires_human=true`，所以它天生是「建议 + 人决定」的形态，正好适合做工作台首屏，却被藏起来了。

5. **对账能力已接线但没有作业面**。`GET /api/v1/outbound-recon`（#74）已经返回内外事实并排 + 逐字段差异判定（`Comparison.state` 七态）。前端 `/fulfillment/outbound-recon` 也是 `hideInMenu`。财务每天要问的「今天发出去的和平台记的对不上的有哪些」，得先知道单号才能查——**只有点查，没有批量列表**。

6. **前端自造了一张 severity 派生表**。`DashboardPage.tsx` 里 `CRITICAL_REASONS` 硬编码 7 个原因码。而 `DashboardController.attention()` 的 SQL 已经返回 severity（复核事项恒 `'RED'`，运营提醒用真实的 `YELLOW`/`RED`）。前端在重算一个已经从后端拿到的字段。

结果：后端能力完整，人却在页面之间找活干。这次要解决的**只是这个**——不是补后端能力。

---

## Solution

新增一个一级板块「我的工作台」，含四个入口，每个入口是一条完整动线的作业面。**既有 URL 一个都不改、一个都不删**；`/workbench/reviews` 与 `/workbench/alerts` 从「作业中心」移入「我的工作台」——这是导航归属变更，不是 URL 变更，`docs/agents/navigation-admission.md` 的「降级 ≠ 删除、任何 URL 不得 404」因此仍然满足。

| 入口 | URL | 主要 responsible_team | 后端依赖 |
|---|---|---|---|
| 发货工作台 | `/workbench/shipping`（新） | `FULFILLMENT_OPS` | 全部已存在 |
| 复核收件箱 | `/workbench/reviews`（既有，移动） | 按岗位预筛 | 全部已存在 |
| 采购工作台 | `/workbench/procurement`（新） | `SKU_OPS` | `/procurement-price-agent/compare` 已存在 |
| 对账工作台 | `/workbench/recon`（新） | 财务（无既有 team 值，见决定 D4） | `/outbound-recon` 已存在；金额缺口见 Out of Scope |

侧边栏顶部（品牌下方）放岗位下拉。它**只做两件事**：决定四个入口里哪个是默认落地页；给复核收件箱一个默认的 `responsible_team` 预筛。它不隔离数据、不改变审计归属——切到任何岗位都能看到全部数据，只是默认视图不同。

新增三个页面 + 一个导航板块 + 一个岗位下拉，**零新增 API、零新增数据表、零后端改动**。

---

## User Stories

### 岗位选择器

1. 作为任何一个使用者，我想在侧边栏顶部看到当前岗位，这样我知道现在这些默认视图是按谁的活筛的。
2. 作为一个使用者，我想切换岗位后立刻跳到那个岗位的工作台，这样切换本身就是「换一份活干」而不是「换一个筛选条件」。
3. 作为一个使用者，我想刷新页面后岗位还在，这样我不用每天早上重选一次。
4. 作为一个使用者，我想把当前页面的链接发给同事时，他打开看到的和我看到的一样，即使我们岗位不同，这样链接不会因为对方的岗位设置而说谎。
5. 作为一个使用者，我想在第一次进入、还没选过岗位时，看到一个明确的「请选择岗位」而不是一个空白的默认值，这样我不会误以为某个岗位是系统给我指派的。
6. 作为一个使用者，我想知道岗位选择不代表权限，这样我不会以为切到财务就看不到发货数据。

### 发货工作台 `/workbench/shipping`

7. 作为发货员，我想在第一屏看到一个「开始今日订单同步」按钮，这样我不用先想今天该从哪开始。
8. 作为发货员，我想在那个按钮旁边看到每个平台今天还剩几次可拉取额度，这样我不会浪费掉每天 ≤2 次的合规配额。
9. 作为发货员，我想在配额已用尽时看到按钮变成不可点并说明下次可拉取时间，这样我不会反复点击并怀疑系统坏了。
10. 作为发货员，我想在拉取过程中看到进行中状态、结束后看到「本次新增 N 个批次 / M 条订单」，这样我知道刚才那一下有没有生效。
11. 作为发货员，我想在拉取到零条时看到「没有新订单」而不是空白，这样我知道是真的没有，而不是页面没加载。
12. 作为发货员，我想在拉取失败时看到是哪个平台失败、失败原因码，以及其他平台是否成功，这样我知道要不要重试、重试哪一个。
13. 作为发货员，我想看到一条从「已自动导入」到「回填失败」的分段链路，每段带条数，这样我一眼知道今天的活卡在哪一段。
14. 作为发货员，我想点击任意一段直接跳到那一段对应的既有列表页（带筛选参数），这样我不用自己去菜单里找对应页面。
15. 作为发货员，我想在链路里看到聚福宝只报告拉取数量、不入库这件事被显式标出来，这样我不会以为聚福宝的订单丢了。
16. 作为发货员，我想看到当前批次的「待人工复核」条数，以及「整批确认」按钮只在该数清零时可点，这样我不会在还有阻断时提交。
17. 作为发货员，我想在「整批确认」不可点时看到还差什么（复核未清零 / 有被拒绝行 / 导出未完整），这样我知道要去处理什么才能继续。
18. 作为发货员，我想在整批确认成功后，就地看到京东出库提交的结果条数与失败条数，这样我不用切页面确认自动提交有没有跑。
19. 作为发货员，我想在有京东出库提交失败时看到一个重试按钮，这样失败不需要找人。
20. 作为发货员，我想在重放同一个确认（幂等命中）时看到「本批次已确认过」而不是又一次成功提示，这样我不会以为自己重复提交了业务事实。
21. 作为发货员，我想看到运单自动回填这件事当前是开还是关，这样我知道等运单是在等系统还是在等我。
22. 作为发货员，我想在自动回填关闭时看到一句明确的说明和它的配置项名，这样我知道该找谁开。
23. 作为发货员，我想在没有任何进行中批次时看到「今天还没有导入批次，先做一次同步」并直接给出那个按钮，这样空态本身是可操作的。
24. 作为发货员，我想在任一区块的数据加载失败时只看到那个区块报错、其余区块照常显示，这样一个接口挂掉不会让我整天没法干活。

### 复核收件箱 `/workbench/reviews`（既有页，加岗位预筛）

25. 作为一个有岗位的复核人，我想进入收件箱时默认只看到我这个团队的待办，这样我不用每次手动筛。
26. 作为一个复核人，我想清楚看到「当前已按我的团队筛选」以及一个「看全部」的切换，这样我不会漏掉别人没认领的活。
27. 作为一个复核人，我想在 URL 里带了 `responsible_team` 时以 URL 为准、忽略我的岗位默认值，这样别人分享给我的链接不会被我的岗位悄悄改写。
28. 作为一个复核人，我想按「为什么需要人」看到分组折叠的队列，这样同一类判断可以连着做完。
29. 作为一个复核人，我想每组标题上有条数、组可以折叠，且折叠状态在本次会话内保持，这样我处理长队列时不用反复卷动。
30. 作为一个复核人，我想点开一条后在抽屉里完成处理、关闭后列表原位刷新，这样我不会每处理一条就丢失滚动位置。
31. 作为一个复核人，我想只看到这条事项**实际允许**的动作按钮，这样我不会点一个注定被后端拒绝的按钮。
32. 作为一个复核人，我想在一条事项被别人先处理掉（版本冲突）时看到「该事项已被处理」并自动刷新该行，这样我不会覆盖别人的决定。
33. 作为一个复核人，我想在我这个团队今天没有待办时看到「你这个团队没有待办」并附一个「看全部」，这样空态不会让我以为系统坏了。

### 采购工作台 `/workbench/procurement`

34. 作为采购，我想第一屏就是 Agent 的比价结论，而不是一张要我先填条件的查询表单，这样我从「看结论」开始而不是从「构造查询」开始。
35. 作为采购，我想每条结论都带「为什么值得关注 / 建议 / 数据来源 / 更新时间 / 运行标识」，这样我能判断这条建议可不可信。
36. 作为采购，我想看到被剔除的候选和剔除理由，这样我知道 Agent 没有偷偷少看了什么。
37. 作为采购，我想每条建议都明确标着「需要人确认」，这样我不会以为它已经生效了。
38. 作为采购，我想 Agent 的任何输出都不能直接改采购工单，只能作为我做决定的依据，这样确定性业务结果始终由人产生。
39. 作为采购，我想在模型未配置 / 未注册 / 未启用时看到那个稳定错误码和一句人话解释，而不是一个空列表，这样我不会以为「没有可优化的」。
40. 作为采购，我想在输入非法时看到具体是哪个字段不合法，这样我能自己改对。
41. 作为采购，我想从一条建议一键跳到对应的采购工单页，这样我能立刻去执行。
42. 作为采购，我想看到当前待处理的采购工单条数与最久未动的那条等了多久，这样我知道有没有活压着。
43. 作为采购，我想在没有任何待比价对象时看到「当前没有需要比价的采购工单」，这样空态是结论不是故障。
44. 作为采购，我想 Agent 每一次运行都留下记录，即使是我手动点的，这样自动与人工留痕一致。

### 对账工作台 `/workbench/recon`

45. 作为财务，我想按单号查一笔出库的内外事实并排结果，这样我不用在两个系统之间来回抄。
46. 作为财务，我想三种单号（系统出库单号 / 京东单号 / 订单号）都能查，这样我拿到哪个号都能开始。
47. 作为财务，我想查询条件进入 URL，这样我能把一笔存疑的对账直接甩给同事。
48. 作为财务，我想看到逐字段的一致 / 不一致判定和匹配、不匹配计数，这样我不用自己逐行比。
49. 作为财务，我想在京东侧不可达时看到明确的「京东侧未取到」，绝不显示成字段为空，这样我不会把「没查到」记成「对方是零」。
50. 作为财务，我想区分「京东没有这笔出库」和「京东查询失败」，这样前者是业务问题、后者是技术问题，我知道找谁。
51. 作为财务，我想看到只在内部存在 / 只在京东存在 的字段被单独标出，这样单边事实不会被当成不一致混在一起。
52. 作为财务，我想从并排视图一路下钻到订单、商品行、Shipment、运单、原始导入行和审计日志，这样每个数字我都能追到源头。
53. 作为财务，我想在查不到这个单号时看到「未找到这笔出库」并保留我输入的条件，这样我能改一个字符再试。
54. 作为财务，我想看到金额列显示为 `¥ ——` 并附一句「金额对账未纳入本期」，这样我知道这是有意的决定而不是数据丢了。
55. 作为财务，我想知道当前的对账是数量口径，这样我不会拿它去做金额结算。

### 导航与外观

56. 作为任何使用者，我想旧的书签和带 `?query=` 的深链在这次改动后照旧可用，这样我攒的链接不会全废。
57. 作为任何使用者，我想不再看到那条几乎没有内容的顶栏，这样纵向空间给内容。
58. 作为任何使用者，我想全站配色只来自一处主题定义，这样不同页面的同一种状态是同一个颜色。
59. 作为任何使用者，我想「演示订单」不出现在日常导航里，这样我不会在生产菜单里点进演示数据。

---

## Implementation Decisions

**D1 — 岗位是视图，不是身份。** 岗位下拉只影响两件事：默认落地的工作台、复核收件箱的默认 `responsible_team`。它不发任何请求、不进任何写操作的请求头、不改变任何返回数据。浏览器不得提供 `X-Operator`（受信网关覆盖身份，`RequestContextFilter` 在 `HIGHEST_PRECEDENCE` 复验），所以岗位在物理上不可能影响审计归属——这不是「我们选择不做」，是「链路上做不到」。这一条要在岗位下拉旁以一句话呈现给使用者。

**D2 — 岗位词汇沿用 `responsible_team`，不新造一套。** 权威取值来自 `queuePresentation.ts` 的 `TEAM_OPTIONS`：`CUSTOMER_OPS` / `SKU_OPS` / `ORDER_OPS` / `FULFILLMENT_OPS`。扫描方案里的「发货员 / 采购 / 财务」是人话别名，只作为工作台标题，不进任何契约字段。`internal_operators.responsible_team` 是 `VARCHAR(32)` + 大写归一（`OperatorRules.normalizeTeam`），不是枚举，所以前端遇到未知团队值必须原样显示而不是崩溃或丢弃。

**D3 — 岗位存 `localStorage`，且第一次进入不预设默认值。** 存 `localStorage`（不进 URL、不进后端）。理由：进 URL 会让分享链接携带发送方的岗位，直接违反故事 4 与 27；进后端要先有身份，而身份不存在。首次进入无值时显示未选择态，不猜。Phase 2 有真身份后，这个键作为一次性迁移来源读取后即弃——`localStorage` 里没有任何业务事实，丢了只损失一次重选。

**D4 — 财务没有对应的 `responsible_team` 值，因此对账工作台不做团队预筛。** 现有四个团队里没有财务。不为此新增团队值（那需要改 `internal_operators` 的数据，属于后端改动，越界）。对账工作台按单号查询，不按团队筛。岗位下拉里的「财务」只决定默认落地页。

**D5 — URL 一个不改。** 新增三个 URL（`/workbench/shipping`、`/workbench/procurement`、`/workbench/recon`）；既有 URL 全部保留，含 `hideInMenu` 的那些。`/fulfillment/outbound-recon` 与 `/procurement/price-compare` 继续存在并继续可直达，新工作台是它们的高频父入口而不是替代品——这正是 `navigation-admission.md` 里「上下文二级入口」的固定三件套写法。

**D6 — 一级板块 10 → 11，各板块可见子项均 ≤ 6。** 新增「我的工作台」（4 个可见入口，排在最前）。`/workbench/reviews` 与 `/workbench/alerts` 从「作业中心」移入，作业中心可见叶子 6 → 4，仍在上限内。可见入口总数 30 → 32（新增 3，移动 2 不计）。这些数字以实现时重算为准，不以本文为准——`navigation-admission.md` 已经确立「一律以实时重算为准」。

**D7 — severity 从后端取，删掉前端的派生表。** `DashboardController.attention()` 已经返回 severity。删掉 `DashboardPage.tsx` 里的 `CRITICAL_REASONS`，改为消费返回值。**但复核列表 API（`/api/v1/review-cases`）确实没有 severity**，所以复核收件箱不显示严重度——不为此改后端契约。这是两件不同的事，只做前一件。

**D8 — 「今日」有两个口径，因为后端本来就有两个，且复核计数没有口径。** 代码事实（`DashboardController` SQL）：下单口径 = `(orders.created_at AT TIME ZONE 'Asia/Shanghai')::date`；发货口径 = `(shipments.shipped_at AT TIME ZONE 'Asia/Shanghai')::date`；**复核事项的计数与聚合完全不带时间边界**（`WHERE rc.status = 'OPEN'`，全部 OPEN）。因此：发货工作台的链路分段用发货/下单口径并逐段标注用的是哪个；复核收件箱**不显示任何时间范围控件**，因为后端过滤不了——`reviewQueueUrl.ts` 已经写明「绝不伪造 `business_date`／`date` 参数，那类参数在复核列表 API 上没有过滤效果，只会让分享链接说谎」。切换岗位不改变任何时间口径：岗位与时间是两个正交维度，岗位只筛团队。

**D9 — 复核清零后不自动继续，必须人点「整批确认」。** `POST /api/v1/import-batches/{id}/confirm` 是显式端点，没有任何调度器或触发器在复核清零时调用它。保持这个形态：确认是一次人的决定，符合 `ImportBatchConfirmation` 的定义（「操作员对一个来源订单导入批次作出的一次整体确认」）。工作台的责任是让「现在可以确认了」这件事显眼，不是替人点。

**D10 — 京东出库提交的重试用既有端点，不新建。** `POST /api/v1/import-batches/{id}/jd-outbound-submit`「已提交的跳过，失败项可安全重试（幂等键稳定）」。故事 19 的重试按钮直接调它。

**D11 — 幂等重放要显式呈现。** `confirm` 在 `confirmed.replayed()` 为真时不会再触发 `submitJdOutboundsForBatch`。前端必须区分「首次确认成功」与「重放命中」两种成功响应并给不同文案（故事 20），否则人会以为自己重复提交了业务事实。

**D12 — 采购工作台是只读 + 建议，写操作一律跳走。** `ProcurementPricePolicy.enforce` 强制 `requires_human=true`；`CONTEXT.md` 边界写明一期 Agent 只做只读分析与建议。工作台展示 `candidates` 与 `excluded_candidates` + 剔除理由，每条带「需要人确认」标记，动作按钮全部是跳转到既有采购工单页，工作台自身不提交任何业务写操作。

**D13 — Agent 的错误是结果不是异常。** `compare` 对模型未配置 / 未注册 / 未启用返回结果内的稳定 `error` 码（fail-closed，不抛异常）；只有输入非法才抛 `INVALID_PARAMETERS`。前端因此有两条不同的错误路径（故事 39 与 40），不能合并成一个「请求失败」。

**D14 — 对账的七个比对态全部显式呈现，不做二值化。** `Comparison.state ∈ MATCH / MISMATCH / INTERNAL_ONLY / JD_ONLY / EMPTY / JD_UNAVAILABLE / JD_NOT_FOUND`，`JdSide.status ∈ OK / NOT_FOUND / UNAVAILABLE`。后端注释已经明确要求「调用方必须明确标注『京东侧未取到』，不得显示为空值」。前端不得把这七态压成「一致/不一致」——那正好会制造故事 49、50、51 要避免的误读。

**D15 — 金额列显示 `¥ ——` 是设计决定，写在页面上。** 全库唯一的金额字段是 `skus.purchase_price` / `retail_price` 与 `fulfillment_export_items.item_amount`（京东云仓被触发器强制为 0）；`orders` 有 `settlement_method` / `settlement_time` 但无金额；`analytics.v_channel_daily` 是数量口径。所以金额对账在数据层就不成立。页面显式说明它是数量口径对账（故事 54、55），并另立一张 Phase 3 的票，不在本 spec 内偷偷留空列。

**D16 — 自动回填开关只呈现状态，不在前端提供开关。** `app.jd.tracking-backfill.enabled` 默认 `false`，`ShipmentJdTrackingPoller` 只有 `@Scheduled` 入口、没有手动触发端点。工作台显示它当前是开还是关（故事 21、22），并给出配置项名，但不提供打开它的按钮——那需要新增后端端点。**打开它本身是一张独立的运维票（用户已拍板要开），不是前端工作。**

**D17 — 每个区块独立取数、独立降级。** 工作台是多来源聚合页，一个接口失败不得让整页白屏（故事 24）。每个区块自己的加载态、空态、错误态互不影响。

**D18 — 不新建共享组件。** 三个新页面用既有的 `PageShell` / `FilterBar` / `DataTable`（#97 已在 38 个路由上落地：PageShell 34/38、DataTable 25/38、FilterBar 17/38）+ `KpiCard` / `Chart`。颜色只从 `frontend/src/theme/saasTheme.ts` 与 `pages/shared/semanticStatus.ts` 取，不新增页面级调色板；`semanticStatus.ts` 已经禁止 antd 具名预设色，沿用该约束。

**D19 — 复核抽屉复用，不重做。** `ReviewCaseDrawer.tsx` 已存在并已被 `manualReviewQueueRoute.test.ts` 覆盖。故事 30、31、32 全部在它上面做，不新建第二种处理形态。**这一条同时取消了原计划中「复核单条处理形态需要再开 `/prototype` 验证」——形态已经落地并有测试。**

**D20 — 动作按钮由 `allowed_actions` 驱动，不由前端推断。** `ReviewCaseDto.allowed_actions` 的取值域：`RESOLVE_CUSTOMER` / `RESOLVE_SKU` / `COMPLETE_SOURCE_FOLLOWUP` / `RESOLVE_MANUALLY` / `DISMISS` / `CONFIRM_ORDER_DRAFT` / `CONFIRM_TRACKING_DRAFT` / `RESOLVE_CHAT` / `RESOLVE_JD_TRACKING_CONFLICT`，分别对应 `/api/v1/review-cases/{id}` 下的 `resolve-customer` / `resolve-sku` / `complete-source-followup` / `resolve` / `dismiss` 等端点。前端只渲染返回值里出现的动作（故事 31），不按 `reason_code` 猜测。

**D21 — 分组按 `reason_code` 分，标签取既有映射。** `queuePresentation.ts` 的 `REASON_LABELS` 已经是 24 个原因码的权威中文映射；`REVIEW_STATUS_LABELS`、`ALERT_STATUS_LABELS`、`TEAM_OPTIONS` 同理。分组折叠形态保持不变（已拍板）。未知 `reason_code` 原样显示码值并归入「其他」组，不丢弃。

**D22 — 提醒专用原因码直达提醒页。** `ALERT_ONLY_ATTENTION_CODES`（`PROCUREMENT_REQUIRED` / `JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED` / `JD_SKU_MAPPING` / `OUT_OF_STOCK`）在复核队列里按 `reason_code` 过滤会得到空列表，所以这些卡跳 `/workbench/alerts`。这个契约已经实现，新工作台的所有跳转一律走 `reviewQueueUrl.ts`，不另写第二套 URL 拼装逻辑。

**D23 — 拉取配额来自既有节流字段，不新建计数器。** 三平台共享 `connector_configs.last_pull_at`，`app.platform-pull.min-interval` 默认 `PT12H`（= 每平台每天 ≤2 次的合规红线）。剩余额度与下次可拉取时间由 `last_pull_at` + `min-interval` 推导（故事 8、9），不新增字段。`DEFAULT_CHANNELS = CAISHIXIAN, JUFUBAO, FEIXIANG`；`app.platform-pull.auto-confirm` 恒为 false，因此拉取永不自动确认——工作台不得暗示它会。

**D24 — 聚福宝降级要在界面上说出来。** 聚福宝 JSON 直连缺收货人字段，只报告拉取数量、不入库。这件事目前只写在一个后端 Javadoc 里。工作台把它做成一等状态（故事 15），否则发货员会以为订单丢了。

**D25 — 对象页只写怎么拼，不做设计。** 订单、发货批次、运单、库存这些对象页一律 `PageShell` + `FilterBar` + `DataTable` 三件套。筛选维度以既有 URL 参数为准并全部进 query string；入口来自工作台的分段跳转与复核抽屉的下钻。本 spec 不为它们规定新的视觉或交互。

**D26 — 删顶栏、岗位下拉进侧边栏顶部（品牌下方）。** 已拍板。`NAVIGATION_GROUP_SUFFIX = '~'` 这个分组 hack 一并去掉——新增板块会让它更难读。

---

## Testing Decisions

**单一接缝：`frontend/test/routeHarness.ts` 上的路由级点击动线测试。**

它已经是 #95 / #96 / #98 / #64 的验收接缝，装配好了 JSDOM + Vite SSR + MemoryRouter + `ConfigProvider(zh_CN + saasTheme)` + antd `App`，并提供 `mount(initialEntries)`、`bodyText()`、`location()`（断言 URL）、`waitFor()`、`control(text)`、`dispatchEvent()`、`jsonResponse()` / `apiErrorResponse()` / `page()` / `reviewCaseFixture()`。四个工作台的每个分支、空态、错误态都在这一层写，用 `globalThis.fetch` 打桩后端响应。

- **不新增接缝。** 上面 59 条故事没有一条需要比路由更低的接缝：状态推导（配额剩余、可确认判定、七态比对呈现）都可以在挂载后的可见文本上断言。
- **不加后端测试。** 本 spec 零后端改动，没有可测的后端行为变更。
- **错误态用 `apiErrorResponse(status, businessCode, message)` 打桩**，逐个覆盖：拉取失败（故事 12）、区块级降级（24）、版本冲突（32）、Agent 稳定错误码（39）、输入非法（40）、`JD_UNAVAILABLE` / `JD_NOT_FOUND`（49、50）、查不到单号（53）。
- **URL 契约用 `location()` 断言**：分段跳转带对的筛选参数（14）、URL 的 `responsible_team` 覆盖岗位默认值（27）、对账查询条件进 URL（47）、旧 `view=alerts` 链接重定向仍然有效（56）。
- **回归门禁沿用既有的**：`businessObjectNavigation.test.ts`、`navigation.test.ts`、`dashboardDispatchRoute.test.ts`、`importBatchReviewRoute.test.ts`、`manualReviewQueueRoute.test.ts`、`alertsQueueRoute.test.ts`、`procurementPriceCompare.test.ts`、`outboundRecon.test.ts`、`saasTheme.test.ts`、`presentationPrimitives.test.ts`、`identityBoundary.test.ts`。全量门禁：`cd frontend && npm run typecheck && npm test && npm run build` + `git diff --check`。
- **`identityBoundary.test.ts` 是 D1 的守门人**：岗位下拉落地后它必须仍然绿——即浏览器仍然不发 `X-Operator`。

---

## Out of Scope

- **登录、真实身份、权限、数据隔离。** 已拍板 Phase 1 不做。因此财务的「归集退货责任」这类动作在审计里无法归属到具体人。**判断：可接受，因为本 spec 不新增任何写操作**——四个工作台的所有写动作都是既有端点（`confirm`、`jd-outbound-submit`、复核的五个 resolve/dismiss），它们的归属现状与本次改动无关，不因为界面重组而变差。需要新归属的动作一个都不引入。
- **金额对账。** 数据层不成立（D15）。另立 Phase 3 票。
- **打开 `app.jd.tracking-backfill.enabled`。** 已拍板要开，但那是配置/运维动作，另立票（D16）。
- **给 `ReviewCase` 契约加 severity。** 后端改动，另立票（D7 只做前端那一半）。
- **采购建议持久化表 + 每日调度。** `compare` 端点已存在，持久化与调度是后端新增，另立票。
- **`/orders` 四个 URL 渲染同一组件的收敛。** 属 #61 / #62 / #63，前置但不在本 spec。
- **万齐。** 用户说是另一个平台。#91–#94 不动。
- **`/analytics` 改名与响应式外壳。** 分别是独立小票与 #65。
- **原型。** §6 的两件事都不再需要：复核单条形态已由 `ReviewCaseDrawer.tsx` 落地（D19）；岗位 × 时间口径的组合行为由 D8 定死（正交，不组合）。

---

## Further Notes

**票面对齐（本 spec 的前置，已按代码事实校正）**

| 票 | 关系 | 说明 |
|---|---|---|
| #95 导入到复核闭环 | 无关（已完成） | CLOSED COMPLETED `ea9fe90`。批次参数契约收在 `reviewQueueUrl.ts`，本 spec 直接消费 |
| #96 工作台变调度台 | **前置** | CLOSED COMPLETED `e3e6b87`。本 spec 在其上加岗位维度与主动作，不推翻 |
| #97 页面迁移共享组件 | 无关（已完成） | CLOSED COMPLETED `16b4a3e`→`b4bcfda`。三件套已在 38 路由落地，D25 因此不需要设计 |
| #98 菜单收敛与准入 | **前置，且是约束** | CLOSED COMPLETED `46d6404`。`navigation-admission.md` 的 ≤6 / 降级≠删除 约束了 D5、D6 |
| #64 拆分复核页为两路由 | 前置（已实现，票面滞后） | 分支上已完成，`manualReviewQueueRoute.test.ts` + `alertsQueueRoute.test.ts` |
| #72 复核家族补齐明细 | 前置（已实现，票面滞后） | 分支上已完成，是分组队列每一组的内容来源 |
| #89 运营人员登记 | **前置（必须先并）** | V48 `internal_operators` + `operator` 包，D2 的团队词汇底座 |
| #61 / #62 / #63 | 前置（未实现） | 对象页的 URL 层级、真实详情路由、筛选进 URL |
| #65 响应式外壳 | 无关 | 375px 限制留在该票 |
| #66–#69 | 无关 | 面包屑 / 危险动作确认 / 表单校验 / 加载态基线 |
| #73 采购比价剔除不可比候选 | 前置（已完成） | `excluded_candidates` 就是它的产物，故事 36 直接消费 |
| #74 出库内外事实并排 | **前置（已完成）** | `OutboundReconController` + 七态比对，对账工作台整个建在它上面 |
| #75–#80 agent-console | 无关 | |
| #82–#90 wecom-outbound | 无关（#89 除外） | |
| #91–#94 wanqi | 无关 | |
| #99–#102 jufubao-shipment-p0 | 无关，但 D24 与之相邻 | 聚福宝降级呈现不等于修它 |

**交接文档的三处已失效判断**，记录在此以免下一个会话重新踩：
1. 「#95–#98 合并重排、旧票关掉并注明被哪张新票取代」——四张票是**已实现并关闭**（`2026-08-21T15:25Z`，交接写完约 30 分钟后），不是被取代。
2. 「身份层完全不存在」「`ProcurementPriceAgent` 零调用方」「Agent 管理无前端页面」——三条在 `69f259f` 上均已不成立。照它们规划会重新实现已有功能。
3. 「9 个一级板块 / 34 可见入口」——实时重算是 10 / 30，作业中心恰好 6。

**一个术语观察，但不改 `CONTEXT.md`。** 岗位（前端视图概念）与 `responsible_team`（复核事项的责任归属）在本 spec 里被有意等同（D2）。这不构成术语冲突：`responsible_team` 的定义没变，只是多了一个消费者。`CONTEXT.md` 无需新增词条——「岗位」不是领域概念，是界面上的一个筛选默认值，Phase 2 有真身份后它才可能升格为领域概念。
