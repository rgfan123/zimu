---
label: wayfinder:research
title: 京东 ISC SDK 接口面提取
status: closed
claimed_by: research-subagent
blocked_by: []
parent: wayfinder:map
---

# 京东 ISC SDK 接口面提取

## Question

从 `backend/libs/` 的两个 jar 提取**真实 API 方法签名**，产出一份接口对照表，供「Connector 与京东 Client 构建」（B3）按真实签名封装 `JDWarehouseClient`。

需要覆盖 PRD §11 的六个能力：

| 能力 | 目标方法 |
|---|---|
| 仓库查询 | `queryWarehouses()` |
| 商品查询 | `queryProducts()` |
| 库存查询 | `queryStock()` |
| 创建销售出库 | `createOutboundOrder()` |
| 查询出库单 | `queryOutboundOrder()` |
| 取消出库单 | `cancelOutboundOrder()` |
| 运单查询 | `queryTracking()` |

## 调查内容

- 每个能力对应的：LOP 服务名（API 名）、请求类、响应类、关键字段；
- 两个 jar 各自角色：`lop-opensdk-support-1.0.30.jar`（底层 SDK：client/request/parser），`IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar`（京东物流领域类 `JdlOpenPlatform*Service`）；
- 用 `javap` 反编译确认方法签名与类结构（jar 是 class 文件，可直接反编译，无需网络）；
- 若 SDK 里没有某个能力（如运单查询可能不在 ISC 包），明确标注"未找到"并指出最近的可用替代。

## 产出

写入 `docs/research/jd-isc-api.md`：能力 × 服务名 × 请求/响应类 × 关键字段 的对照表 + 每个能力的调用链示例（伪代码级）。

## Blocked by

无（前沿票）。

## Resolution

**2026-08-10 已解决**（research 子代理，资产见 `docs/research/jd-isc-api.md`，150 行）。

7/7 能力全部在 SDK 中找到真实对应：

| 能力 | LOP 服务名 |
|---|---|
| queryWarehouses | `/integratedsupplychain/basicinfo/warehouse/query/v1` |
| queryProducts | `/integratedsupplychain/basicinfo/goods/query/v1` |
| queryStock | `/integratedsupplychain/stock/query/v1` |
| createOutboundOrder | `/integratedsupplychain/order/delivery/create/v1`（So 服务） |
| queryOutboundOrder | `/integratedsupplychain/order/delivery/query/v1` |
| cancelOutboundOrder | `/integratedsupplychain/order/cancel/v1` |
| queryTracking | `/integratedsupplychain/order/trace/query/v2`（语义=订单轨迹，替代=城配轨迹 `/order/citytrack/query/v1`） |

关键入口：`JdlClient(serverUrl, appKey, appSecret, accessToken)`（参数顺序待文档确认）。每能力有对应 `*LopRequest` / `*LopResponse` 包装类 + 领域 DTO（`SoCreateOrderRequest`、`StockQueryRequest` 等）。

封装注意（9 条，详见文档）：分页类型不统一（`JdlApiPageResponseBase` / `JdlOpenPage`）、Flag 开关、三套响应信封、trace 的 `getResult()` 不一致、`pin`/`ownerNo` 必带等；枚举取值与 JSON 注解映射标注「待文档确认」。

→ 「Connector 与京东 Client 构建」的前置已就绪。
