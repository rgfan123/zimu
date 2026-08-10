---
label: wayfinder:task
title: Connector 与京东 Client 构建
status: open
claimed_by: 
blocked_by: [京东 ISC SDK 接口面提取, API 契约设计]
parent: wayfinder:map
---

# Connector 与京东 Client 构建

## Question

落地 connector 包与外部对接：京东真/伪双 Client、三平台 Connector 接口 + mock、回传模拟、Audit 完整记录。

## 范围

- `connector/jd/`：`JDWarehouseService` 接口 + `MockJdWarehouseClient`（demo 默认）+ 真实 `JdWarehouseClient`（按京东 ISC SDK 接口面提取票的真实签名封装，引用 `backend/libs/` 两个 jar）；
- 真实登录/凭据**留口**：配置开关（mock/real 可切换），真实登录后续阶段接入；
- `connector/caishixian|jufubao|feixiang/`：Connector 接口（拉单/转换/回传）+ mock 实现（演示数据源）；
- WECOM 渠道：回传记为「模拟回传成功」；
- audit 切面与 Audit Log 查询 API（request_id / trace_id / operator / request / response）。

## 验收

- 两个 jar 被 pom 以本地依赖引用，真实 `JdWarehouseClient` 编译通过；
- demo 默认 mock 路径可跑（出库/发货在流水线中正常推进）；
- Audit Log 有接口调用记录可查。

## Blocked by

京东 ISC SDK 接口面提取、API 契约设计。

## Resolution

（未解决）
