---
label: wayfinder:grilling
title: API 契约设计
status: closed
claimed_by: zed-main
blocked_by: []
parent: wayfinder:map
---

# API 契约设计

## Question

定出系统全部 REST API 契约，产出 OpenAPI 3.0 文档。覆盖所有前端页面所需数据 + 外部接入面。

## 决策点

- `POST /internal/v1/orders`（PRD §7 结构，模拟下单页与未来 LangBot 共用）；
- 业务管理 API：订单（列表/详情/Timeline/状态筛选）、商品中心（商品/品类/SKU/Mapping CRUD）、履约中心（履约任务/京东仓/销售出库/Shipment）、采购工单（列表/详情/结果回填）、Audit Log 查询、数据中台查询（渠道/商品/履约指标，§21）；
- 外部对接面：京东 `JDWarehouseService` 接口签名（对齐 R1 提取结果）、三平台 Connector 接口（拉单/转换/回传）、WECOM 渠道回传模拟；
- 幂等机制：`idempotency_key` 放 header 还是 body，写接口清单；
- 错误模型：错误码 + 消息结构（business_code / http_status）；
- 分页/筛选/排序约定；鉴权（demo 无登录，但预留 token header 位）。

## 产出

- `docs/api-contract.md`（业务边界、Adapter/MCP 契约与实现说明）
- `docs/openapi.yaml`（OpenAPI 3.0.3 机器可读契约）

## Grilling decisions

1. 管理后台、受信任内部接入和 Demo 分别使用 `/api/v1`、`/internal/v1`、`/demo/v1`；BUSINESS API 不提供混查 Demo 的参数。
2. 所有业务写命令使用 `Idempotency-Key`、`X-Operator`；修改既有事实使用 `expected_version`。相同 key+相同 body 重放原结果，不同 body 返回 409。
3. 来源导入必须显式 NEW/REVISION；内部订单不静默 upsert，已形成履约承诺的修订进入人工复核或纠正单。
4. FulfillmentExport 在行就绪时自动生成，人只负责下载；有效行不被同文件问题行阻塞，一份文件只属于一个 provider。
5. 下载状态由 AuditLog 与显式回传血缘推导，不提供“标记已使用”；ProviderTrackingBatch 必须指向原 FulfillmentExport。
6. 京东正式回传优先按 `isv出库单号 + 运单号`；只有姓名时仅在当前待回传导出中姓名唯一才自动匹配，否则 NEED_REVIEW。
7. `isv出库单号`/`outbound_order_no`由系统生成：上海业务日 `yyyyMMdd` + 四位数据库原子流水；同 Shipment 共用，重放/重下不变，后续分批使用新号。
8. Agent 可查询、创建 WECOM 内部订单、提交匹配建议/材料；不得确认映射、取消剩余、重试采购、关闭复核或确认多 Shipment 后续回传。
9. Connector 将 `MOCK/REAL` Client 模式与 `EXCEL/API` 传输模式分开；当前三平台为 EXCEL，真实 API 等文档/凭据后接入。
10. 京东 SDK、三平台 Connector 与 MCP 是应用层 Adapter，不作为额外公共 REST，禁止直接写业务表。

## Assets

- `docs/api-contract.md`
- `docs/openapi.yaml`
- `docs/schema.sql` / `docs/schema.md` / `docs/schema-smoke.sql`：只补 API 契约必需的回传导出血缘、每日出库号分配器、乐观版本和 Connector 双轴。

## Validation

- YAML/引用自检：61 paths、76 operations、92 schemas；无缺失 schema ref、重复 operationId、路径参数错误、重复 YAML key、写命令请求头遗漏或统一错误响应遗漏。
- Redocly CLI：OpenAPI 3.0.3 有效；只剩本地 Demo 的 `localhost` server 提示，属于预期。
- PostgreSQL 16 Alpine 空库执行 `docs/schema.sql` 成功 COMMIT；实测 38 tables / 4 views / 67 non-internal triggers。
- `docs/schema-smoke.sql` 成功执行并 ROLLBACK；新增覆盖上海日流水、回传关联原导出/provider、可扩展幂等 scope。
- `git diff --check` 通过。

## Review

首轮独立架构复审为 NEEDS_CHANGES：发现 SKU 写契约误含静态礼包/BOM 字段且缺 DDL 必填 `specification`，以及 Timeline 未返回/使用权威 `sequence_no`；两项已定点修正。Connector 能力端口的 P2 提前抽象意见不阻断：拉单/变更/取消/回传本就是 PRD 与本票要求，平台 DTO 和真实枚举仍明确延后。

定点复审：PASS，无残留 P0/P1。确认 Timeline 与 DDL 的 `sequence_no` 一致、SKU 写契约不再含静态礼包字段、Connector capability port 属于 PRD §6/票面要求且真实 API 实现仍延后。

## Blocked by

无（前沿票）。

## Resolution

REST、京东/平台 Adapter 和 MCP 权限契约已定稿。管理端 61 条路径覆盖页面、P0 Excel 闭环、人工复核、采购、审计、分析和隔离 Demo；写操作统一幂等/乐观版本，FulfillmentExport 自动生成并以下载审计+原导出回传血缘判断使用状态。系统出库单号采用上海业务日加四位数据库原子流水。OpenAPI、PostgreSQL 16 smoke 与独立架构复审均通过，后续构建票可据此实现。
