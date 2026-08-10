---
label: wayfinder:task
title: 前端框架与核心页面构建
status: open
claimed_by: 
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
- 「模拟下单」页：表单 → `POST /internal/v1/orders`（等价未来 LangBot 输入）。

## 验收

- 页面可访问、导航完整；
- 模拟下单 → 订单列表出现 → 详情页 Timeline 完整展示到最终态。

## Blocked by

API 契约设计。

## Resolution

（未解决）
