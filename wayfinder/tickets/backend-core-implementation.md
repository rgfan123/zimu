---
label: wayfinder:task
title: 后端骨架与订单域实现
status: closed
claimed_by: zed-main
blocked_by: [后端核心实现边界, 数据库 Schema 设计, API 契约设计]
parent: wayfinder:map
---

# 后端骨架与订单域实现

## Question

按已确认的核心边界落地可运行的后端骨架与 Order / Customer / Product / SKU 模块。

## 范围

- Java 21、Maven、Spring Boot 3.x、Spring Data JPA，单工程包分层的 Modular Monolith；
- 通过 Flyway 落地 `docs/schema.sql`，Hibernate 只验证 schema；
- `common`：PostgreSQL 权威幂等、OrderEvent、OrderVersion、AuditLog 写入服务及 `/api/v1/audit-logs` 查询端点、统一错误模型、请求/操作人上下文；
- Order：真实 `BUSINESS + WECOM` 结构化订单的创建、修订、纠正、列表、详情、Timeline 与版本接口；
- Customer / Category / Product / SKU / 来源与履约方 SKU 映射：既有 API 契约中的查询与维护接口；
- 客户/SKU 缺失时保存订单/行和 ReviewCase，不进入履约；全部映射成功时按 OrderLine 创建初始 Fulfillment；
- Redis 连接与非权威缓存骨架；缓存不可用不得破坏写事务正确性；
- 最小、确定性的客户/京东 SKU/第三方 SKU 主数据种子。

## 不包含

- 微信消息解析、LLM/Agent 编排；
- Mock DemoScenario；
- 库存判断、采购、Shipment、履约文件和运单回传；
- 外部 Adapter 的具体调用审计（后续票只调用本票提供的共享 AuditLog 服务）；
- 三平台 Excel 解析、履约导出与来源回填；
- 30 天完整演示数据。

## 验收

- `mvn compile` 与测试通过，应用可启动；
- `POST /internal/v1/orders` 只创建 BUSINESS/WECOM 订单，幂等重放不重复写入；
- 创建命令原子写入订单事实、初始 Fulfillment（仅已映射行）、OrderEvent、OrderVersion 与 AuditLog；
- 缺客户/SKU 的订单形成 ReviewCase 且不创建对应 Fulfillment；
- Order 与主数据所属接口按 OpenAPI 可调通；
- 最小商品/SKU 种子可通过商品中心 API 查询。

## Blocked by

后端核心实现边界、数据库 Schema 设计、API 契约设计。

## Validation

- `cd backend && mvn test -q`：27/27 通过（0 failures，0 errors）；
- `cd backend && mvn -DskipTests compile -q`：通过；
- `cd backend && mvn -Dtest=MasterDataApiTest test -q`：1/1 通过，覆盖七类主数据查询、幂等创建、乐观锁更新与审计；
- `cd backend && mvn -Dtest=InternalOrderApiTest test -q`：12/12 通过，覆盖 BUSINESS/WECOM 创建、映射门禁、纠正单与修订版本历史。

## Resolution

已落地 Spring Boot 3 / Java 21 单体后端、Flyway Schema、PostgreSQL 权威幂等、事件/版本/审计与统一错误模型。Order 支持 BUSINESS/WECOM 创建、修订、显式纠正、列表/详情/Timeline/版本；Customer、Category、Product、SKU、来源/履约方 SKU 映射及履约方接口已按 OpenAPI 补齐查询与幂等维护，并保留客户/SKU 映射失败不进入履约的门禁。
