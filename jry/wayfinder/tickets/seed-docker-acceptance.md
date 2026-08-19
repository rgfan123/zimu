---
label: wayfinder:task
title: 种子数据与一键启动验收
status: closed
claimed_by: codex-seed-docker
blocked_by: [后端骨架与订单域实现, 履约发货与采购模块构建, Connector 与京东 Client 构建, 前端框架与核心页面构建, 前端剩余页面与数据中台构建, P0 Excel 接入与履约回填闭环构建]
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

后端骨架与订单域实现、履约发货与采购模块构建、Connector 与京东 Client 构建、前端框架与核心页面构建、前端剩余页面与数据中台构建、P0 Excel 接入与履约回填闭环构建。

## Validation

- `mvn -f backend/pom.xml test` → `BUILD SUCCESS`，31 tests，0 failures，0 errors，2 skipped（需真实样表的可选用例）。
- `npm --prefix frontend test` → 6 tests passed；`npm --prefix frontend run build` → production build passed（仅有已知大 chunk 警告）。
- `docker compose config --quiet`、`sh -n scripts/acceptance.sh`、`sh -n docker/metabase-init/provision.sh` 均通过。
- 独立全新项目 `zimu-fulfillment-acceptance-v3c` 将业务库从空 schema 按 Flyway `V1 -> V2 -> V3` 迁移成功，生成 123 条 `BUSINESS` 订单。
- `ACCEPTANCE_PROJECT=zimu-fulfillment-acceptance-v3c ACCEPTANCE_PORT=18091 ACCEPTANCE_REFERENCE_DATE=2026-08-12 ACCEPTANCE_SKIP_BUILD=true sh scripts/acceptance.sh` → `PASS: public HTTP seams, 30-day seed, Demo isolation, Metabase dashboards, restart idempotency`。验收栈保留，未删除容器或卷。
- 逐条证据见 `docs/acceptance.md`。

## Resolution

已完成固定随机种子和 Asia/Shanghai 参考日的 30 天演示数据，仅在无 `BUSINESS` 订单时播种，并以 PostgreSQL advisory lock 保护并发启动。Compose 已编排 PostgreSQL、Redis、backend、frontend、Nginx 和 Metabase；Metabase provisioner 可幂等创建、绑定并实际查询「履约总览」「渠道分析」「商品分析」。根 README、公共 HTTP 验收脚本与七条走查记录已补齐。

真实京东凭据/登录、三平台真实 API 和脱敏样表映射仍是后续外部 gate；本票没有从《京东商品编号.xlsx》猜测或新增来源映射。共享工作树中并发新增的 `order-assistant` 也不属于本票 MVP 验收契约，本票未回退它。

2026-08-12 复核收口：独立 fresh 项目从 V1→V2→V3 启动，123 条 BUSINESS 种子、三个 Metabase 卡片/仪表板实际查询、Demo 隔离、公共 Nginx 路由和 backend 重启幂等均通过；票面验收已全部满足，转为 closed。
