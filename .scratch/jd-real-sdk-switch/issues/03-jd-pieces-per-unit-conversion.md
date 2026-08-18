# 03 — SKU 京东件数换算

**Type:** implementation

**What to build:** 运营能为每个京东履约 SKU 维护「来源数量单位 → 京东件数」的显式换算，建单时据此算出 `planQuantity`。今天来源单位不是「件」，系统按设计拒绝默认为 1，所有货品行都卡住。

**Blocked by:** None — can start immediately

**Status:** resolved

- [x] 履约 SKU 映射支持显式京东件数换算，提供受审计、幂等的维护与批量导入入口。
- [x] 换算值必须为正整数件数；缺失、零、负数或非整数一律阻塞建单，系统不推断、不取整。
- [x] 可从权威商品清单与来源规格（如 `500g*2`、`150g*4`）生成**候选**供人工确认，候选本身不构成已配置。
- [x] `planQuantity` = 发货指示数量 × 换算件数，使用 BigDecimal 计算后落为整数件；无法整除时阻塞并给出可读原因。
- [x] 预览中 `cargoInfos[].planQuantity` 的来源标注能追溯到具体 SKU 的换算配置。
- [x] 换算变更留痕；已提交的京东出库单不受后续换算调整影响。
- [x] 为当前 61 个内部 SKU 配齐后，货品行阻塞全部消失（工具链已就绪，需运营在 SKU 映射页按候选逐条确认导入）。

## Answer

- 后端已有/补齐：
  - `PATCH /api/v1/provider-sku-mappings/{id}` 支持 `jd_pieces_per_unit`。
  - `POST /api/v1/provider-sku-mappings/jd-pieces-per-unit-imports` 幂等批量导入，正整数校验、不静默覆盖、审计留痕。
  - `GET /api/v1/provider-sku-mappings/jd-pieces-candidates` 从来源规格/内部规格生成候选，候选不落库。
  - `ShipmentJdSkuMappingGateService` / `JdStockUnitConverter` 已在预览/建单时计算 `planQuantity`，缺失/非法/不可整除均阻塞。
- 本次修复：
  - `JdPiecesCandidateParser` 支持 `500g*2`、`150g*4` 这类带单位的乘数文本；候选优先取来源规格。
  - 前端 `SkuMappingsPage` 新增“京东件数换算”面板：展示候选、已配置值、可勾选导入。
  - 修复 `ProviderSkuFactorImportApiTest` 的用例隔离与 jsonb 读取方式。
- 验证：
  - `ProviderSkuFactorImportApiTest` 3/3 通过。
  - `frontend npx tsc --noEmit` 通过。
  - 真实 61 SKU 的换算值仍需运营在页面上按候选人工确认导入；代码与 UI 已不阻塞该操作。

## Comments

- 2026-08-17（04 交接后补记）：03 把小数系数（如 `0.5` 件/盒）改为在配置校验阶段即阻断
  `JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID`，但 `ShipmentJdOutboundPreviewApiTest.previewBlocksNonIntegralConversionInsteadOfRounding`
  仍按旧行为断言（系数 0.5 → `NON_INTEGRAL_QUANTITY`），该测试在 03 完成后处于红态（当时只验证了
  `ProviderSkuFactorImportApiTest`，未跑全量）。04 收尾时已修复：测试改用「整数系数 × 非整数指令数量（1.500 盒）」
  继续覆盖「不四舍五入」语义，保留原测试意图；小数系数阻断由 03 自身规则覆盖。
