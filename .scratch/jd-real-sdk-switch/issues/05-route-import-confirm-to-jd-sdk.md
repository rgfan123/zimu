# 05 — 导入确认后按履约方路由到京东 SDK 建单

**Type:** implementation

**What to build:** 确认一个彩食鲜导入批次后，京东履约的发货批次直接通过 SDK 建出真实京东出库单，不再只产出一份要人工中转的导单 Excel。非京东履约方仍走既有文件路径。

**Blocked by:** 01 — 履约方京东标识配置面；02 — 本地客户档案维护京东客户编码；03 — SKU 京东件数换算；04 — 收货地址结构化确认（含批量）

**Status:** resolved

- [x] 导入确认按履约方类型路由：京东履约走 SDK 建单，第三方履约保持既有文件导出，两者共用同一应用层用例。
- [x] 路由由显式配置控制且可回退到文件模式；切换不需要改代码，也不静默改变历史批次的处置方式。
- [x] 未通过建单前置校验的发货批次不阻断整个批次确认：明确落到待处理，给出逐条可读原因，已就绪的照常建单。
- [x] 建单复用既有写门闩、授权操作人名单、幂等键与预览指纹复验；重放不产生第二张京东出库单。
- [x] 部分成功可恢复：失败项重试不重复提交已成功项，也不留下半截业务批次。
- [x] 成功与失败都记录业务码、请求 ID、操作人与脱敏审计；失败不伪造 Shipment、Tracking 或完成阶段。
- [x] 界面能看到每个发货批次的建单状态、外部引用与可否重试。
- [x] Mock 模式可完整演示成功、门闩关闭、京东拒绝、超时后安全重试与幂等重放。

## Answer

- 路由配置：`fulfillment_providers.config.outboundMode` ∈ {`SDK`, `FILE`}，缺省/显式 `FILE` 保持既有导单文件路径（回退不改变历史批次处置）；非法值 422 `FULFILLMENT_PROVIDER_CONFIG_OUTBOUND_MODE_INVALID`。01 的 `FulfillmentProviderJdConfig` 契约类加入该键，配置页提供下拉（SDK 直连/导单文件）。
- 确认路由：`ProviderFileService.routeForSourceBatch` 按履约方分流——JD + `outboundMode=SDK` 只创建 Shipment + ShipmentItems（不产文件、不推进订单阶段）；JD 文件模式与第三方保持原逻辑。`SourceImportService.confirm` 的「未覆盖行」检查同时认履约导出与发货批次；确认响应带 `outbound_routing.jd_sdk_shipment_ids`。
- 自动建单：确认事务提交后（controller 层触发，`submitJdOutboundsForBatch` 逐条走 `ShipmentJdOutboundService.submit`，与手动提交同一用例/写门闩/授权名单/幂等/预览指纹），失败留痕（SYNC_FAILED/告警/审计）不阻断批次确认；幂等重放确认不重复建单。
- 批量重试：`POST /api/v1/import-batches/{batch_id}/jd-outbound-submit` 对批次内京东发货批次逐个建单；已提交跳过，失败项逐条给出 `business_code` 可读原因，可安全重试（每次调用新幂等段，防重复由 SUBMITTED 前置跳过 + submit 业务校验保证）。
- 界面：ShipmentsPage 列表新增「京东建单」状态列（未提交/提交中/已提交/需对账/提交失败）；抽屉保留外部引用与单条重试；SalesOutboundPage 确认后按路由提示，已确认批次提供「重试京东建单」按钮。
- 验证：`SourceBatchJdSdkRoutingApiTest` 4/4（真实闭环：确认→地址确认→批量建单成功→已提交跳过→确认幂等不重复；前置未就绪落待处理不阻断批次；FILE 回退生成文件；配置校验拒绝非法值）。全量后端套件与前端 171/171 见 Comments。

## Comments

- 2026-08-17（验证结果）：
  - `SourceBatchJdSdkRoutingApiTest` 4/4 通过；前端 `tsc --noEmit`、`npm test` 171/171、`npm run build` 通过。
  - `docs/openapi.yaml` 同步：新增 `POST /import-batches/{batch_id}/jd-outbound-submit` 端点与 `ImportBatchJdOutboundSubmitResult`/`ImportBatchJdOutboundSubmitItem` schema；`ImportBatch` 补 `outbound_routing`；履约方 `jd_config`/config 描述补 `outboundMode` 键。
  - 全量后端套件回归：739 项，除 `OrderDraftApiTest`/`OrderDraftComplexityApiTest` 异步草稿链路的已知 flaky（全量环境下 awaitUntil 12s 超时，单独运行稳定全绿；与 05 无关）外全部通过；`FulfillmentProviderJdConfigApiTest` 键数断言已随 outboundMode 更新为 10。
  - 本票一并完成：04 收尾发现的 7 个既有红态测试类全部修复（根因：操作者 shell 泄漏 `JD_LOP_CLIENT_MODE=REAL` 导致真实 JD 客户端无凭据注册 + 02 客户档案门禁未补；修复方式：测试类显式钉 `app.jd.client-mode=MOCK` + 补客户档案配置），全量套件从 81 失败 + 4 错误降至仅剩上述 flaky。
  - 真实流程提醒：确认批次后自动建单会因 03/04 前置未配置而落待处理（设计如此，不猜测）；运营在 SKU 映射页配件数、发货单页确认地址后，用 SalesOutboundPage「重试京东建单」或 ShipmentsPage 单条重试即可完成建单；`townRequired` 等真实标识仍待 07 真实 addSoOrder 裁决。

- 2026-08-17（独立复核，另一会话）：
  - `SourceBatchJdSdkRoutingApiTest` 4/4、全量后端套件 **739 项 0 失败 0 错误 7 跳过 BUILD SUCCESS**、前端 `npm test` 171/171、`tsc --noEmit` 通过——均在无 `JD_LOP_*` 环境变量的干净 shell 中复现。
  - 上条注释所称 `OrderDraftApiTest`/`OrderDraftComplexityApiTest` 全量下 flaky 失败，本次复核**未复现**（全量 0 失败）。
  - 归因更正：上条注释将 7 个红态测试类的根因记为「操作者 shell 泄漏 `JD_LOP_CLIENT_MODE=REAL`」，但复核 shell 中 `JD_LOP_*` 为空，该归因不可复现。修复手段（测试类显式钉 `app.jd.client-mode=MOCK`，共 9 个类）本身正确且应保留——测试不应依赖环境变量——但「根因」表述保留意见。
  - openapi 同步已核：`POST /import-batches/{batch_id}/jd-outbound-submit`、`ImportBatchJdOutboundSubmitResult`/`Item`、`ImportBatch.outbound_routing`、`jd_config.outboundMode` 均在位。
  - 安全面已核：写门闩 `.env` 与容器内均 `OFF`；`shipment_jd_outbounds` / `trackings` 仍为 0，未产生任何真实京东出库单；`config.outboundMode` 未设置，`"SDK".equals()` 精确匹配使缺省安全回落到 FILE。
  - **未部署提醒**：运行中的 backend 镜像构建于 2026-08-17 09:58，而 03/04/05 的改动在 14:47 之后。票据全绿但运行环境尚不含这些能力（实测 `GET /provider-sku-mappings/jd-pieces-candidates` 落到 `{id}` 路由返回 `INVALID_IDENTIFIER`）。需重建镜像后方可做真实数据验证。
