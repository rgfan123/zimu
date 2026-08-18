---
label: wayfinder:task
title: 前端剩余页面与数据中台构建
status: closed
claimed_by: codex-frontend-restoration
blocked_by: [API 契约设计, 前端框架与核心页面构建]
parent: wayfinder:map
---

# 前端剩余页面与数据中台构建

## Question

落地前端剩余导航页面与数据中台（ECharts 图表 + BI 外链）。

## 范围

- 商品中心：商品 / 品类 / Internal SKU / SKU Mapping（标准表格 + 基础操作）；
- 履约中心：履约任务 / 京东仓 / 销售出库 / Shipment；
- 部门协同：采购工单（列表/详情/结果回填展示）；
- 数据中台：履约总览 / 渠道分析 / 商品分析 / 履约分析（ECharts，指标按 PRD §21）；
- 系统：Connector / Audit Log / 系统配置（基础表格）；
- 导航「BI」外链 → Metabase（容器地址）。

## 数据呈现设计（已定，2026-08-10）

统一口径：所有数据页面以「天」为粒度，默认今日，可切近 7 天 / 近 30 天 / 自定义区间。

- 工作台：KPI 卡（今日订单数、今日实发商品量、待处理、异常、缺货、回传失败）+ 近 7 天订单/发货双序列迷你趋势图；
- 履约总览：日期选择器 + 6 张履约 KPI 卡（京东仓履约量、缺货订单、采购工单数、待出库、待取得运单、回传失败）+ 近 30 天趋势折线 + 渠道构成堆叠柱状图（按天 × 渠道）；
- 渠道分析：按渠道分组对比柱状图，**订单数 ↔ 实际发货量双口径切换**（PRD §21 硬要求）+ 渠道占比饼图 + 明细表（渠道 × 日期 × 订单数 × 商品数量）；
- 商品分析：今日「渠道 × 商品」矩阵表（行=商品、列=渠道、单元格=发货数量，可上钻 SKU/品类）+ Top N 商品条形图 + 商品 × 日期趋势折线；
- 履约分析：履约状态分布堆叠柱（待出库/已出库/待取得运单/待回传/回传失败）+ 京东仓 vs 采购对比 + 异常/缺货计数卡；
- 双口径原则：**「每个渠道发了多少货」必须同时提供订单数和实际商品数量两个口径**。实际商品数量按来源包装乘数换算后的 Canonical SKU 实发件数统计，定制礼包展开为组件数量；不统计来源包装数、礼包份数或重量。

## 验收

- 全部导航页面可访问、有数据；
- 数据中台四页图表正确呈现（渠道×订单数+实际发货量双口径、商品×日期等）；
- BI 外链可达 Metabase。

## Blocked by

API 契约设计、前端框架与核心页面构建。

## Resolution

前端剩余页面与数据中台已落地并完成 2026-08-12 原型回归：所有导航均指向真实页面；数据中台恢复原型 D 的四渠道显式多选、URL `ch` 状态和全屏联动，渠道变化同时约束订单/商品、履约积压与漏斗、人工复核队列。后端新增渠道粒度履约视图与 `source_channel` 查询契约，避免只过滤半屏。热力下钻补齐真实来源商品名、`quantity_multiplier` 和京东 SKU 映射，礼包明确回到订单组件快照查看 BOM。

## 2026-08-12 补充验证

- `cd frontend && npm test`：3/3；`npm run build`：通过。
- 后端定向 HTTP seam：渠道 Analytics + ReviewCase 2/2 通过；`JUFUBAO` 不会混入 WECOM 履约/复核数据。
- Playwright 对照 `frontend/prototype/dashboard-prototype.html`：图标窄轨、四渠道显式按钮、3×2 KPI、12 列 bento、图/文切换与下钻结构均可达；`/analytics?range=30d` 0 console error。

## Validation

### Assets

- `frontend/src/pages/analytics/` — **数据中台单屏 bento（决策 D 完整落地）**：`AnalyticsPage.tsx`（全局筛选条 + KPI 六卡 + 8 张 bento 卡 + 下钻抽屉）、`useAnalyticsData.ts`（取数/聚合/环比窗口）、`chartOptions.ts`（7 图 option 构建）、`analyticsTypes.ts`、`ClickableChart.tsx`（ECharts 点击回调，仅本目录使用，未改共享组件）
- `frontend/src/pages/product/` — 品类 / 商品 / Internal SKU / SKU Mapping（两个 Tab：来源映射 + 履约方映射），全部复用 `pages/shared/MasterDataCrud.tsx`（上会话已建）+ `masterOptions.ts`（主数据下拉选项）
- `frontend/src/pages/fulfillment/` — 履约任务（列表 + 详情抽屉：发货批次/采购工单）、京东仓（履约方目录 + 编辑）、销售出库（履约导出 + 下载 + 明细）、Shipment（列表 + 详情）
- `frontend/src/pages/procurement/` — 采购工单（列表 / 详情 / 结果回填回执 Timeline 展示）
- `frontend/src/pages/system/` — Connector（配置表格 + 编辑 + 连通性测试）、Audit Log（检索 + 请求/响应快照）、系统配置（只读总览，见裁决 1）
- `frontend/src/routes.tsx` — 占位条目全部替换为真实页面；`/analytics` 保持单路由
- 未动：`backend/`、`docs/`、`prototype/`、`wayfinder/`（除本票）、`frontend/src/components/`、`frontend/src/api/client.ts`、`pages/{dashboard,orders,demo}/`

### 验证

- `npm run build`（= tsc 严格 + vite 构建）：**0 错误通过**，产物与骨架基线一致（echarts / antd 大 chunk 警告为既有拆分配置，未新增）
- `npx vite preview` 冒烟：HTTP 200（静态服务）
- 后端仍在开发（独立票），运行时数据验证留待联调；取数严格按 openapi 参数/字段

### 决策 D 落地情况（对照原型票 Resolution）

| 决策 D 要求 | 落地 |
|---|---|
| 单屏 bento（12 栅格），/analytics 单路由 | ✅ 8 卡网格：KPI 行 span12 + trend8/funnel4 + chstack7/share5 + heat7/top5 + backlog8/issues4 |
| 顶部全局筛选条（日期粒度/渠道多选/双口径），一处改动全屏联动 | ✅ 粒度 Segmented（今日/7/30/自定义 + RangePicker）+ 渠道多选 + 订单数↔实发量开关；全部状态入 URL（range/start/end/ch/metric/txt） |
| 每卡两副面孔，切换态写 URL | ✅ 右上 icon 切文字版明细，`?txt=trend,funnel,...` 刷新/分享可复原 |
| ① 趋势：平滑面积+渐变+末点高亮 | ✅ `line+smooth+areaStyle`，末点大符号 + 数值标签 |
| ② 履约漏斗 + 环节通过率 | ✅ `funnel`，5 段单调夹住（履约创建→库存校验通过→京东已受理→已出库→已取得运单），label 内嵌通过率（见裁决 3） |
| ③ 渠道构成按天堆叠面积 | ✅ `line+stack+areaStyle`，口径随双口径开关 |
| ④ 渠道占比甜甜圈 + 占比条 | ✅ `pie radius[58%,78%]` + 自定义占比条，**点条下钻抽屉** |
| ⑤ 渠道×商品热力矩阵，**按各渠道自身归一** | ✅ `heatmap`，单元格 itemStyle 按列（渠道）max 归一着色，禁用全局归一；**点色块下钻** |
| ⑥ Top 商品横向条按品类着色 | ✅ `bar`，品类稳定调色板 |
| ⑦ 积压构成堆叠面积，**不含已出库** | ✅ 只含 待出库/待运单/待回传/回传失败；已出库量走漏斗与 KPI（卡副标题明示） |
| 只保留一处常驻列表：需人工介入 | ✅ issues 卡取 `GET /api/v1/review-cases`（OPEN），其余表格全部退到 icon 后 |
| 下钻抽屉：京东SKU/渠道商品名/包装数量 + SKU 上钻；组合品 BOM 提示 | ✅ 抽屉含映射面板（京东 SKU/内部名称/包装数量）与按商品聚合时的 SKU 上钻列表；**BOM 提示为契约缺口，见裁决 2** |
| KPI 六卡（订单/实发/待出库/异常/缺货/回传失败）卡底 sparkline + 环比 | ✅ 六卡全有 sparkline（今日粒度回看近 14 天）+ 上一等长窗口环比 Tag；积压/异常类指标涨为红色（invert） |
| 双口径硬要求（Canonical SKU 实发件数，礼包展开组件） | ✅ 实发量统一取 `actual_shipped_quantity ?? canonical_quantity ?? shipped_quantity`（契约 §4.7 字段）；页脚明示口径定义 |

### 工程裁决

1. **系统配置无独立端点**：openapi 无 `/api/v1/system/config`，系统级配置实体就是 ConnectorConfig + FulfillmentProvider，故「系统配置」页以这两个资源的只读总览呈现（编辑/测试在各自主页），不为不存在于契约的接口造数据。
2. **组合品 BOM 提示（契约缺口）**：组合品主数据/BOM 无聚合端点——礼包 BOM 只存在于当单组件快照（CONTEXT.md 定制礼包），`analytics/products` 不携带 bundle 标记。抽屉映射面板已按决策 D 搭好结构，BOM 面板待后端提供组合品主数据后接入（代码内有注释标记）。
3. **履约漏斗 5 段（前端推导）**：契约字段无「已回传/已同步」计数，故末段为「已取得运单」而非原型 6 段的「已回传」；各段由 `v_fulfillment_daily` 状态计数单调夹住（min/max 防后段大于前段）。若后端补 `synced_count` 可加回末段。
4. **按天退化约定**：analytics 三端点按 `metric_date` 返回按天行时逐日图表可用；仅返回聚合行时退化为单周期口径（`types.ts` ChannelMetric 注释同约定，未改）。
5. **今日粒度的序列回看**：对齐原型 D 行为——`today` 时趋势/构成/积压与 sparkline 回看近 14 天，KPI/漏斗/占比/热力仍为今日口径。
6. **渠道多选**：契约为单值 `source_channel` 参数，页面按渠道并发请求后合并（`endpoints.ts` 既有注释同约定）。
7. **共享设施零改动**：未动 `components/` 与 `api/client.ts`；ECharts 点击回调需求通过 `pages/analytics/ClickableChart.tsx`（本目录内薄封装）满足。
8. **销售出库映射**：PRD §22「销售出库」对应契约 `fulfillment-exports`（发货指令文件，生成即履约承诺）；「京东仓」对应 `fulfillment-providers`（JD_WAREHOUSE 行以「京东仓」标签标识，与第三方同表）。
9. **采购工单只读**：本票范围=列表/详情/结果回填展示；retry / cancel-remaining 写操作未实现（契约已备，留给后续操作票）。
