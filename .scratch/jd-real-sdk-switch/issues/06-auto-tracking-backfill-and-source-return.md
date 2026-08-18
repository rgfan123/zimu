# 06 — 运单自动回填与彩食鲜格式回填表产出

**Type:** implementation

**What to build:** 京东出库单建好之后，系统自动取回运单号与快递公司标识，运营直接下载一份彩食鲜原格式的回填表，单号与快递公司已经填好，不用手工誊写。

**Blocked by:** 05 — 导入确认后按履约方路由到京东 SDK 建单

**Status:** resolved

- [x] 建单成功的发货批次自动进入运单回填，无需人工逐单触发；既有手工入口保留。
- [x] 轮询开关、频次与最小间隔可配置，默认值在文档与部署配置中一致；关闭时手工入口不受影响。
- [x] 回填复用既有运单写入用例与承运商前缀识别，产出快递公司标识；无法唯一映射时进入人工复核而非猜测。
- [x] 回填完成后按来源渠道原格式生成回填文件，单号与快递公司标识已填入；文件只追加版本，不覆盖历史。
- [x] 京东拆单、本地运单冲突与异常终态按既有规则创建可人工处理的复核项，不反复外调。
- [x] 远端响应只接受有界字段与候选，畸形响应收敛为安全诊断，不落库含个人信息的原始响应。
- [x] 端到端可演示：建单 → 自动取回运单 → 下载彩食鲜格式回填表。

## Answer

大部分能力在既有代码中已具备（`ShipmentJdTrackingBackfillService` + `ShipmentJdTrackingPoller` + `TrackingFileService`，48 个既有用例覆盖调度/手工入口/承运商识别/拆单冲突异常/畸形响应收敛），本次补齐的是 **05 SDK 路由与自动回填的衔接缺口**，并新增跨票端到端闭环测试：

- 缺口修复（`TrackingFileService.finalizeReadySourceReturnsForShipment`）：原实现只通过 `fulfillment_export_items.shipment_id` 反查来源批次，SDK 直连路由（05）的 shipment 没有导出项 → 回填完成后无法生成来源回填表。改为 UNION 同时认 `shipment_items → fulfillments → raw_import_rows` 与 `fulfillment_export_items` 两条路径；文件路由行为不变。
- 端到端测试（`SourceBatchJdAutoBackfillE2EApiTest` 1 用例）：SDK 路由确认 → 自动建单（SUBMITTED）→ `ShipmentJdTrackingPoller.poll()` 自动取回运单（京东返回 deliveryNo 必须与建单时出库单号一致，waybillNo 落 `trackings`，`tracking_query_status=TRACKED`）→ 自动生成彩食鲜格式回填文件（物流公司代码/物流单号/发货数量已填，`is_final=true`，只追加版本）→ 下载验证；TRACKED 终态后轮询器不再反复外调。
- 配置一致性核对：`application.yml` / `docker-compose.yml` / `.env.example` 三处 `JD_TRACKING_BACKFILL_*`（enabled=false、poll-ms=60000、batch-size=20、min-interval=PT1M）一致；关闭时手工入口不受影响（既有测试覆盖）。

## Comments

- 验证：`SourceBatchJdAutoBackfillE2EApiTest` 通过；全量后端套件回归见最终运行结果（除 OrderDraft 异步链路已知 flaky 外全绿）。
- 真实流程提醒：回填文件按来源批次生成且仅当全部已接收行都有对应运单时才出 `is_final`（部分未回填时返回 null 不产出）；运营可在「来源导入 → 回填表」或批次详情下载。

- 2026-08-17（最终）：全量后端套件 0 失败 0 错误（739 项，含新增 E2E 用例与 TrackingFileService 改动回归）。前端无需改动：回填表下载入口（SalesOutboundPage 批次详情/列表）与 API 均已存在。
