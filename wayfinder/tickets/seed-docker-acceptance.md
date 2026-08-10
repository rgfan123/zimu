---
label: wayfinder:task
title: 种子数据与一键启动验收
status: open
claimed_by: 
blocked_by: [后端骨架与订单域构建, 履约发货与采购模块构建, Connector 与京东 Client 构建, 前端框架与核心页面构建, 前端剩余页面与数据中台构建]
parent: wayfinder:map
---

# 种子数据与一键启动验收

## Question

落地确定性种子数据生成器、Docker Compose 全套编排、Metabase 预置仪表板，并按地图 7 条验收标准走查收尾。

## 范围

- 确定性 seeder（固定随机种子、幂等、空库才播）：近 30 天、四渠道（彩食鲜/聚福宝/飞象/企业微信）、状态全覆盖（已发货/缺货/采购待处理/异常/回传失败等）+ 2~3 条「演示专用」新鲜订单（一条刚进入、一条缺货、一条异常）；
- Docker Compose 全套：PostgreSQL / Redis / 后端 / 前端（Nginx 静态服务）/ Metabase（连接 PG，预置 2~3 个仪表板：履约总览/渠道分析/商品分析，数据源指向 analytics 视图）；
- 本地开发模式说明（后端 `mvn spring-boot:run` + 前端 `npm run dev` + Vite 代理）；
- 根 README：一键启动说明 + 演示路径说明；
- 按 7 条验收标准逐条走查，产出验收走查记录（写入 `docs/acceptance.md`）。

## 验收

地图 7 条验收标准全过。

## Blocked by

后端骨架与订单域构建、履约发货与采购模块构建、Connector 与京东 Client 构建、前端框架与核心页面构建、前端剩余页面与数据中台构建。

## Resolution

（未解决）
