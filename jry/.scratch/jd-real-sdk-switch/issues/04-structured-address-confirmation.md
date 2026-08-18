# 04 — 收货地址结构化确认（含批量）

**Type:** implementation

**What to build:** 运营能一次性把一批发货的收货地址拆成京东要求的省/市/区/详细地址并确认，而不是逐单手工填。系统仍然不从自由文本自动采纳，只把解析结果作为候选交给人确认。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 从来源表格既有字段与订单地址生成结构化**候选**；候选与已确认值在数据和界面上严格区分，未确认不参与建单。
- [x] 提供批量确认入口，可按导入批次或发货批次一次处理多条，逐条可改可跳过。
- [x] 保留既有单条确认入口的语义与幂等性，两条路径复用同一应用层用例。
- [x] 确认结果记录操作人与确认时刻；订单地址后续变更使已确认结果失效并要求重新确认。
- [x] 乡镇按履约方 `townRequired` 策略处理：为 `false` 时可留空且不阻塞。
- [x] 解析失败、层级缺失或候选歧义时明确落到人工，不猜测、不静默填充。
- [x] 处理完当前 3 个发货批次后，预览中 `receiverInfo.*` 阻塞全部消失（闭环测试覆盖同一链路；真实 3 批需运营在页面上批量确认）。

## Answer

后端（交接已有，本次验收并修复两处缺陷）：
- `POST /api/v1/shipments/jd-receiver-address-batch`：逐条复用单条确认用例与审计；任一版本冲突整个批量原子失败（409 `VERSION_CONFLICT`）；相同 `Idempotency-Key` 重放首次结果。
- `GET /api/v1/shipments/jd-receiver-address-candidates`：从来源表格 `省/市/区/详细地址` 单元格生成候选；`candidate` 为 `null` + `candidate_incomplete=true` 表示来源层级缺失，落到人工。
- 修复 ①：控制器 `@RequestParam` 缺显式 `name`，`import_batch_id` / `only_missing` 从未真正绑定（一直取默认值），已按仓库惯例改为 snake_case 显式名称。
- 修复 ②：候选响应硬编码 `confirmed_by=null`，已改为返回 `jd_receiver_confirmed_by`（操作人留痕要求）。
- 附带修复：03 遗留的过时测试 `previewBlocksNonIntegralConversionInsteadOfRounding`（见 03 Comments）。

前端（本次新增）：
- `ShipmentsPage` 新增「京东收货地址批量确认」面板：
  - 候选表：发货单号 / 来源渠道 / 原始地址 / 候选（缺失时标「需人工填写」）/ 已确认值（含操作人）/ 编辑。
  - 勾选多行 → 弹窗逐条可改（省/市/区/必填，乡镇可选）→ 「批量确认选中」一次性提交。
  - 缺必填层级时整批取消导入并点名提示，不静默跳过；未确认行不参与建单。
  - 幂等键由「发货单+确认值」内容派生：同内容重放返回首次结果，改值即新键，不会被旧结果吞掉。
- 纯逻辑模块 `jdReceiverAddress.ts` + `test/jdReceiverAddress.test.ts`（6 用例）：候选/已确认严格区分、编辑优先、必填缺失落 skipped、幂等键稳定性。

验证：
- `ShipmentJdReceiverAddressBatchApiTest` 4/4 通过（候选→批量确认→操作人留痕→预览解锁→幂等重放→版本冲突原子性→层级缺失落人工）。
- `ShipmentJdOutboundPreviewApiTest` 10/10、`ProviderSkuFactorImportApiTest` 13/13 通过。
- `frontend npx tsc --noEmit` 通过；`npm test` 171/171 通过。
- 文档：`docs/openapi.yaml` 补齐两个端点与 `ShipmentJdReceiverAddressBatchItem` / `ShipmentJdReceiverAddressBatchCommand` / `JdReceiverAddressCandidate` schema。

「订单地址变更使已确认结果失效」的说明：`shipments.receiver_*_snapshot` 在批次创建时落库后全库无任何 UPDATE 路径，地址变更必然产生新发货批次（新 `shipment_sequence`），其 `jd_receiver_*` 为空、必须重新人工确认；已确认值不会随旧批次漂移。候选查询按 `import_batch_id` 过滤即覆盖「重导入后重新确认」场景。

仍需运营做的：
- 在「履约中心 → 发货单」页对当前 3 个发货批次（乃至全部待确认批次）按候选逐条确认后点「批量确认选中」；确认后预览 `receiverInfo.*` 阻塞消失，建单即可继续。

## Comments

- 2026-08-17（收尾时全量回归发现）：**后端全量测试套件存在既有红态，非 04 引入**（04 相关测试全绿，含本票新增 `ShipmentJdReceiverAddressBatchApiTest` 4/4 与修复后的 `ShipmentJdOutboundPreviewApiTest` 10/10）。各失败类有明确归属：
  - `ShipmentJdStockCheckApiTest`(9) / `ShipmentJdTrackingBackfillApiTest`(多处)：02 起 customerCode 改从客户档案取值，这两个类的 `@BeforeEach` 未补 `customers.profile.jd_customer_code`，预览被 `JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING` 阻塞（库存检查 409 `JD_STOCK_PREVIEW_BLOCKED`）。
  - `ShipmentJdOutboundSubmitTest`(11) / `ShipmentJdOutboundWriteModeDisabledTest`(1) / `CaishixianJdBatchClosedLoopApiTest`(1) / `ShipmentJdTrackingBackfillApiTest`(部分)：实时库存门禁插入提交路径后，这些测试未 stub `queryStock`，提交被 `JD_STOCK_CHECK_BLOCKED` 阻断（闭环测试的 ControlledJdClient 只覆写 queryOutboundOrder）。
  - `ShipmentJdSkuMappingGateApiTest`(9)：`JD_GOODS_QUERY_FAILED（CREDENTIALS_REQUIRED）`——京东商品只读查询凭据/客户端未就绪。
  - `ConnectorApiTest`(2) / `MessageInterpretationApiTest`(1)：与 jd-real-sdk-switch 无关的既有问题（连接器 mock 状态、消息链路）。
  - 建议 05/06 接手时先按此清单补齐测试前置（客户档案、库存 stub、商品查询凭据），再动业务代码；本票已修复的 03 遗留测试见 03 Comments。

- 2026-08-17（05 阶段补记）：上条清单中的既有红态已全部修复（7 个测试类：显式钉 `app.jd.client-mode=MOCK` 抵御操作者 shell 泄漏的 `JD_LOP_CLIENT_MODE=REAL` + 补 02 客户档案配置），全量后端套件 739 项通过（仅剩 OrderDraft 异步链路的已知 flaky）。详见 05 Comments。
