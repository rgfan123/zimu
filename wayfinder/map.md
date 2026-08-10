---
label: wayfinder:map
title: 订单履约中台 Demo：PRD V0.1 → 可运行全框架 Demo
tracker: local-markdown
---

# 订单履约中台 Demo：PRD V0.1 → 可运行全框架 Demo

## Destination

一个**本地一键启动、可演示**的《订单履约与仓储物流中台》Demo：后端全部模块框架（order / customer / product / sku / fulfillment / shipment / procurement / connector / analytics / audit）全真，前端 PRD §22 全部导航页面 + 「模拟下单」页，30 天种子数据撑起数据中台（ECharts + Metabase），**7 条验收标准全过**。

> 本 effort 产出的是**可运行的 Demo**（不是设计文档包）。执行被带进地图：构建票在地图内完成，每张票一个 session，产出必须可运行、可验收。

## Notes

**领域**：B 端生鲜食材订单履约与仓储物流；渠道：彩食鲜 / 聚福宝 / 飞象 / 企业微信。
**固定输入**：`docs/prd-v0.1.md`（PRD V0.1，所有范围与契约以此为准）；领域词汇见 `CONTEXT.md`。
**技能**：grilling（任何 HITL 票）、domain-modeling（词汇变化时更新 `CONTEXT.md`）、research（外部知识票）、prototype（前端页面形态票可选）、tdd（构建票可选）。
**验收标准（7 条）**：
1. `docker compose up` 后浏览器打开，工作台与全部导航页面可访问；
2. 「模拟下单」页创建一单 → 订单列表出现 → 详情页 Timeline 完整展示到最终态（创建即跑完全程，状态机每一步都是真代码路径）；
3. 30 天种子数据撑起工作台卡片 + 渠道/商品/履约分析图表；
4. 种子数据覆盖缺货 / 采购待处理 / 异常 / 回传失败，对应页面可展示；
5. 后端全部模块包存在且逻辑非空壳；
6. 京东 jar（`backend/libs/`）被 pom 引用，`JDWarehouseClient` 真实封装类编译通过（demo 默认走 mock，真实登录后续接）；
7. Audit Log 有记录（接口调用可追溯）。

**建图期已定决策**（grilling 结论，不再重开）：
- 终点是 Demo 而非 PRD/设计文档包；策略 C：全骨架 + 真实状态机 + mock 外部系统；
- 「推进机制」砍掉：不做自动定时/手动推进按钮；新订单**创建即跑完全程**（真实流水线一步到位），中间态故事由种子数据讲；
- Docker Compose 一键起全套（PG / Redis / 后端 / 前端 / Nginx），另保留本地开发模式；
- 前端全部页面深度分级（核心扎实、支撑基础表格）+ 「模拟下单」演示页；
- 种子数据：确定性生成器（固定随机种子、幂等、空库才播），近 30 天、四渠道、状态全覆盖 + 2~3 条演示订单；
- 京东：两个 jar 引入 `backend/libs/` 并以 pom 本地依赖引用；封装真实 `JDWarehouseClient`（按 SDK 签名），demo 默认 Mock；真实登录后续阶段接入，留口；
- LangBot：demo 不碰 LangBot/企业微信环境本身；`POST /internal/v1/orders` 保留（模拟下单页使用）；WECOM 回传记为「模拟回传成功」；
- 三平台：Connector 接口（拉单/转换/回传）+ mock 实现，真实平台细节等文档/凭据再补；
- Metabase **保留**：docker compose 服务连 PG，预置 2~3 个仪表板（履约总览/渠道分析/商品分析）指向 analytics 视图；前端数据中台仍用 ECharts，导航加「BI」外链；
- **数据呈现**（2026-08-10 已定）：所有数据页面以「天」为粒度（默认今日，可切 7/30 天/自定义）；工作台 KPI 卡+迷你趋势；渠道分析按渠道分组柱状图 + **订单数/实发量双口径切换** + 饼图 + 明细表；商品分析「渠道×商品」矩阵表（可上钻 SKU/品类）+ Top N + 趋势折线；履约分析状态堆叠柱 + 京东 vs 采购 + 计数卡；Metabase 同口径复用 analytics 视图（详见 B5 票体）；
- 技术默认：Java 21 + Maven + Spring Boot 3.x + Spring Data JPA（单 Maven 工程包分层）；PostgreSQL + Redis；中文 UI、英文代码标识符；不引入 Kafka/Flink/ClickHouse/MinIO/K8s；无登录/权限（Audit Log 记固定演示账号）。

## Decisions so far

<!-- 每张已关闭票一行：标题（链接）+ 一句话结论。 -->

- [京东 ISC SDK 接口面提取](tickets/jd-isc-api-surface.md) — 7/7 能力从两个 jar 中提取到真实 LOP 服务名（含 `SoCreateOrderRequest`/`StockQueryRequest` 等 DTO 与 9 条封装坑），见 `docs/research/jd-isc-api.md`；真实 Client 封装的前置已就绪。
- [订单状态机精化](tickets/order-state-machine.md) — 五维状态集/转移矩阵/事件清单定稿：双轨持久化（order_event + order_version）、主线最终态 SYNCED、缺货走外部回执（前端采购操作台 mock，真实回执接口）、行级独立推进 + 订单级最差聚合；见 `docs/state-machine.md`。

## Not yet specified

<!-- 雾区：方向内但还无法精确成票的问题；前沿推进后逐块毕业为票。 -->

- 前端页面级细节（组件结构、图表选型）——B4/B5 开工时定；
- Metabase 仪表板具体指标与布局——B6 时定；
- 演示走查脚本细节——B6 时定；
- 种子数据的具体商品/SKU 清单与数量分布——B1 后定；
- Flyway 迁移 vs JPA ddl-auto（默认倾向 Flyway，更接近生产形态）——B1 开工时定。

## Out of scope

<!-- 已明确排除在本 effort 之外；永不毕业。 -->

- **LangBot / 企业微信环境本身**（企业微信机器人、LangBot 部署、LLM Prompt、OCR、Agent 调度）——接口保留，环境不做；
- **京东真实登录与凭据接入**——后续阶段接入，`JDWarehouseClient` 留口；
- **三平台真实 API 对接**（无文档/凭据）——Connector 接口 + mock；
- **采购部门内部流程**（询价/比价/供应商选择/审批）；
- **权限与登录体系**；
- **MinIO / 对象存储**（`evidence_refs` 只存引用字符串）；
- **Kafka / Flink / ClickHouse / Data Lake**；
- **Kubernetes**（一期 Docker Compose 足够）；
- **订单推进机制 / 时间快进**（已砍）；
- **生产部署与运维**。
