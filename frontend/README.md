# 子牧订单履约中台 — 前端

React 18 + TypeScript + Vite + AntD 5 + ECharts 5 + react-router 6。中文 UI，英文代码标识符。

## 运行

```bash
npm install
npm run dev      # http://localhost:5173，/api /internal /demo 代理到 http://localhost:8080
npm run build    # tsc 类型检查 + vite 构建（验收命令）
```

## 目录结构

```
src/
├── routes.tsx            ★ 单一路由配置（同时驱动侧边栏菜单与路由表）
├── App.tsx               路由装配（布局路由 + routeConfig 展平）
├── main.tsx              入口（ConfigProvider 中文 locale / 主题）
├── api/
│   ├── client.ts         统一 fetch 客户端（错误模型 / X-Request-Id / 查询参数）
│   ├── endpoints.ts      类型化端点（dashboard / orders / demo）
│   └── types.ts          openapi schemas 的 TS 映射（snake_case）
├── hooks/
│   ├── useAsync.ts       通用异步数据 hook（loading/error/reload）
│   └── usePagedOrders.ts 订单分页列表 hook（筛选 + 分页）
├── components/
│   ├── Chart.tsx         ECharts 封装（ResizeObserver 自适应）
│   ├── KpiCard.tsx       KPI 卡（迷你 sparkline）
│   ├── StatusTag.tsx     枚举 → 中文 Tag
│   ├── OrderTimeline.tsx 订单事件时间线（PRD §18，演示亮点）
│   └── layout/AppLayout.tsx 侧边栏 + 顶栏框架
├── constants/labels.ts   枚举/事件 → 中文标签与颜色
└── pages/
    ├── dashboard/        工作台
    ├── orders/           全部订单 / 待处理 / 异常 / 追踪 / 详情
    ├── demo/             模拟下单（仅 /demo/v1）
    ├── product/          商品 / 品类 / Internal SKU / SKU Mapping
    ├── fulfillment/      履约任务 / 京东仓 / 销售出库 / Shipment
    ├── procurement/      采购工单列表与详情
    ├── analytics/        决策 D 单屏 bento 数据中台
    └── system/           Connector / Audit Log / 系统配置
```

## 扩展方式

1. 页面组件放 `src/pages/<模块>/`；
2. 在 `src/routes.tsx` 的 `routeConfig` 追加条目（含 `path/label/icon/element`），菜单与路由自动生效；
3. 新增端点：`api/endpoints.ts` 加函数（类型来自 `api/types.ts`）。

## API 边界约定（contract §2）

- `/api/v1`：业务查询（BUSINESS 数据域）——订单列表 / 详情 / Timeline / 发货 / 工作台；
- `/demo/v1`：仅「模拟下单」页使用（DEMO 数据域，经管理网关认证，写请求含 Idempotency-Key）；
- `/internal/v1`：前端**不调用**（受信任内部接入，LangBot / Agent / 部门系统）。
- 写操作头：浏览器只生成 `Idempotency-Key`；受信管理网关注入并复验 `X-Operator` 与 Basic 身份。
