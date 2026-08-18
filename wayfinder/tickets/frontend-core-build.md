---
label: wayfinder:task
title: 前端框架与核心页面构建
status: closed
claimed_by: codex-frontend-restoration
blocked_by: [API 契约设计]
parent: wayfinder:map
---

# 前端框架与核心页面构建

## Question

落地 React + TS + Vite + AntD + ECharts 前端：布局路由（PRD §22 导航）、工作台、订单管理、「模拟下单」页、订单详情 Timeline。

## 范围

- 工程骨架：Vite + React + TS + AntD + ECharts + 路由（§22 导航）；
- 布局与导航框架（侧边栏 + 顶栏），中文 UI；
- 工作台：数据卡（订单数/发货量/异常数/缺货数等）；
- 订单管理：全部订单 / 待处理 / 异常订单 / 订单追踪（列表 + 筛选）；
- 订单详情：基本信息 + **Timeline**（PRD §18 事件流时间线展示）——演示亮点；
- 「模拟下单」页：调用独立 `/demo/v1` DemoScenario 接口，只读写 DEMO 数据；不得复用 `POST /internal/v1/orders`。

## 验收

- 页面可访问、导航完整；
- 模拟下单 → 仅在 Demo 页面出现 → Timeline 完整展示到最终态；默认 BUSINESS 订单列表不得出现该数据。

## Blocked by

API 契约设计。

## Resolution

核心前端已按实际运行面收口：Ant Design 线性图标替代产品 emoji，侧栏默认使用原型 D 的图标窄轨；工作台、订单列表/筛选/详情、Shipment/Tracking、隔离 DemoScenario 与完整 Timeline 均接入真实页面和契约端点。原先占位的导航页面已由对应实现页替换，BUSINESS/DEMO 仍按命名空间隔离。

2026-08-12 成品化收敛：导航改为任务层级“工作台 → 作业中心 → 订单中心 → 主数据 → 经营分析 → 系统管理”，侧栏默认展开且顶栏显示父级路径；新增 `/workbench/reviews` 一级人工复核队列；采购详情接入失败重试与取消剩余量；京东仓配页接入只读 SDK 状态、仓库权限和发货事实查询，并显式区分 Mock 与真实就绪，避免把模拟成功当权限通过。

## 2026-08-12 补充验证

- `cd frontend && npm test`：3/3；`npm run build`：通过。
- Playwright 实浏览器：`/analytics?range=30d&ch=WECOM` 0 console error；侧栏为图标窄轨，页面刷新保留 URL 筛选。
- `frontend/src/pages/orders/OrderDetailPage.tsx` 已接 `GET /api/v1/orders/{order_id}/shipments`，不再显示“Shipment API 建设中”。
- `npm test`：7/7；`npm run build`：通过。Playwright 在 `http://localhost:18091` 实测 `/workbench/reviews` 与 `/fulfillment/jd-warehouse`，侧栏展开、父子层级、41 项复核队列与京东 SDK 作业面均可见；公共 HTTP 路由返回 200。

## Validation

### Assets

工程：`frontend/`（Vite + React 18 + TS + AntD 5 + ECharts 5 + react-router 6；`npm run build` = tsc 类型检查 + vite 构建）。`frontend/prototype/dashboard-prototype.html` 为已关闭原型票产物，保留未动。

关键文件：

- `frontend/src/routes.tsx` — **单一路由配置数组**（同时驱动侧边栏菜单与路由表）；后续票在此追加条目 + 覆盖 `pages/` 目录即可扩展
- `frontend/src/components/layout/AppLayout.tsx` — 侧边栏 + 顶栏框架（PRD §22 全导航 + 模拟下单 + BI 外链）
- `frontend/src/api/client.ts` / `endpoints.ts` / `types.ts` — 统一 fetch 客户端（X-Request-Id、契约错误模型）+ 类型化端点 + openapi schemas TS 映射
- `frontend/src/hooks/useAsync.ts` / `usePagedOrders.ts` — 共享数据 hooks
- `frontend/src/components/OrderTimeline.tsx` — 订单事件时间线（PRD §18，演示亮点：事件图标/色调、payload 中文化、末事件「当前」高亮）
- `frontend/src/components/Chart.tsx`（ECharts 封装，ResizeObserver）/ `KpiCard.tsx` / `StatusTag.tsx` / `PlaceholderPage.tsx`
- 页面：`pages/dashboard/`（工作台：KPI 卡 + 近 7 日趋势 + 待介入明细）、`pages/orders/`（全部/待处理/异常/追踪共用 `OrderListView` + 详情页 Steps/Timeline/发货运单）、`pages/demo/`（模拟下单）、占位页 `pages/{product,fulfillment,procurement,analytics,system}/index.tsx`

路由结构：`/dashboard`；`/orders`、`/orders/pending`、`/orders/exceptions`、`/orders/tracking`、`/orders/:orderId`（隐藏菜单）；`/product/products|categories|skus|sku-mappings`、`/fulfillment/tasks|jd-warehouse|sales-outbound|shipments`、`/procurement/tickets`、`/analytics`（决策 D 单屏）、`/system/connectors|audit-logs|config`（以上为占位）；`/demo/order`；`/bi` 外链 `/metabase`。

### 验证

- `npm install`：141 包安装成功（node v22.18.0 / npm 10.9.3）
- `npm run build`：tsc 0 错误 + vite 构建通过（5 个 chunk：app 45.7 kB / react 18.9 kB / antd 1.18 MB / echarts 1.03 MB，manualChunks 拆分 vendor）；`npx vite preview` 冒烟返回 HTTP 200
- 后端尚未就绪（Mock Demo 为独立实现票），运行时数据验证留待联调；页面取数严格按 openapi 参数/响应字段

### 工程裁决

1. **React 18.3.1**（而非 19）：AntD 5 原生支持，避开 React 19 兼容补丁与 echarts-for-react peer 依赖问题；图表用自写 `Chart.tsx` 封装（约 50 行）替代 echarts-for-react（2021 年后未维护），B5 可复用同一封装。
2. **代理**：`/api`、`/internal`、`/demo` → `http://localhost:8080`；额外 `/metabase` 代理支撑 BI 外链本地开发（Docker 模式由 Nginx 同源反代，URL 以实际 Compose 配置为准）。
3. **模拟下单**：只调 `/demo/v1/scenarios`（GET 场景 / POST 创建，带 `Idempotency-Key`）/ `/demo/v1/runs/{id}`（RUNNING 时 2s 轮询至终态）；未使用 `/internal/v1`。
4. **数据域协调点（需审查者确认）**：Demo 订单事件读取复用 `GET /api/v1/orders/{order_id}/timeline`（契约中唯一 Timeline 来源；DemoRun 不含事件）。若后端对该端点强制 BUSINESS 数据域（404），Demo 页会显示降级提示（页面已内置），届时需在 `/demo/v1` 补时间线读取入口。
5. 时间格式：dayjs `Asia/Shanghai` 本地自然日（`YYYY-MM-DD`），与契约业务日一致。
6. `buildMenuItems` 分组 key 加 `~` 后缀避免与叶子路径（如 全部订单=/orders）重名；点击只对展平后的真实路由导航，分组/外链不触发路由。
