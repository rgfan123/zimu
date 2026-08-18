# 01 — 接口演进：PlatformConnector 扩展为在线 Pull 形态

**What to build:** 在不破坏现有三个 Excel Connector 的前提下，让 `PlatformConnector` 具备在线拉单形态：新增 default 实现返回 `CONNECTOR_CAPABILITY_UNAVAILABLE` 的 `pullOrders/pullOrderChanges/pullCancellations/pushShipmentResult`，并定义 PullCursor/PullResult/SourceOrderEnvelope/SourceShipmentResult/SourceSyncResult 等 DTO（签名级，参考设计文档 §4.1）。现有彩食鲜/聚福宝/飞象 Connector 行为不变、编译通过。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] `PlatformConnector` 新增方法均有 default 实现，三平台现有实现零改动编译通过
- [x] 新 DTO 字段与三平台契约（总览文档）对齐，含游标/分页语义注释
- [x] capabilities() 仍为文件模式，testConnection 行为不变

## Comments

- 2026-08-18: 已实施（PlatformConnector default 方法 + 5 个 DTO），mvn compile 通过，三个子类零改动。待双轴 code review。
