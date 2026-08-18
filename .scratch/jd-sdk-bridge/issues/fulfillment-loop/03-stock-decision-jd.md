# 03 — 库存决策接入京东实时库存

**What to build:** 业务确认可发/可售时，京东云仓履约的订单用京东 `queryStock` 实时库存做判断（替代本地预估）：系统判断「库存充足可履约」时直接走京东建单链路；京东库存不足时给出明确的不可履约结论。当前对第三方履约库存硬拒绝（"第三方履约库存不由本系统预判"）的逻辑对京东云仓反转：京东库存成为可履约性的判定来源。

**Blocked by:** 00 — 履约记录京东同步字段扩展（消费方字段就绪）

**Status:** wontfix

- [ ] 语义反转确认：对京东云仓履约方，库存决策不再硬拒绝，而是查询京东实时库存并给出可履约/不可履约结论；对其它第三方履约方保持原行为。
- [ ] 单位换算：京东库存单位与系统数量单位（千克等）的换算规则确定并实现，四舍五入策略明确。
- [ ] 查询与建单竞态：库存查询与建单之间的竞态处理（查完即建 vs 查完提示），明确并发下不出现超卖误判。
- [ ] 查询失败时的降级策略（不可用 → 拒绝履约 vs 放行），有明确默认并记录审计。
- [ ] Mock 模式可演示（京东库存充足 → 可履约结论；不足 → 不可履约结论）。

---

## 实现记录（agent 03-stock-decision-jd）

### 语义反转

- `FulfillmentStockDecisionService.apply()` 按 `provider_type` 分派：`JD_WAREHOUSE` → 京东实时库存判定（`applyJdRealTime`）；`THIRD_PARTY` → 原行为（`applyNormalized`，`!inventory_managed_by_us` 仍抛 `INVENTORY_NOT_MANAGED`）。
- 京东分支不检查 `inventory_managed_by_us`（即使 false 也判定），但数据库触发器 `trg_stock_snapshot_scope` / `trg_procurement_ticket_validation` 仍要求 `inventory_managed_by_us=true`，**需主 agent 协调迁移**（见下）。
- 京东分支中 `StockDecisionCommand` 的 `decision`/`items` 仅作信封：判定由京东实时库存推导，命令里的决策不被采纳；`observedAt` 用作快照同步时间。
- 查询 API：`queryStockSnapshot`，入参 `goodsNoList` + `goodsLevelList=["1"]`（正品）+ `stockTypeList=[1]`（可用），只把正品可用库存计入可履约，避免残次/锁定库存造成超卖误判。可履约量 = 各仓 `availableQuantity` 之和；`stock_num` = 可用 + 占用（不含在途）。
- 载荷解析兼容两种客户端框架：Mock 把业务载荷包在 `data.response` 下，REAL 直接放 `data`。

### 单位换算规则（1 系统单位 → N 件）

- 换算系数存放：`provider_skus.external_codes` JSONB 保留键 `jd_pieces_per_unit`（正数，如 `0.500` 表示 1 盒 = 0.5 件）；未配置 → 默认 `1.000`（1 系统单位 = 1 件）。配置存在但非法（非正数/不可解析）→ 拒绝判定 `JD_STOCK_UNIT_CONFIG_INVALID`。
- 需求件数 = ceil(系统数量 × 系数)，**一律向上取整**：京东按整件履约，非零尾数也占用 1 件库存，避免低估需求。
- 采购工单缺口数量以**件**为单位（`unit_snapshot='件'`），与京东库存口径一致。
- 实现：`JdStockUnitConverter`（纯静态助手，单测覆盖换算与系数解析）；换算系数落地表结构无需迁移（复用 external_codes JSONB）。

### 降级策略（查询失败/载荷无法解析）

- 拒绝履约：抛 `BusinessException(502, JD_STOCK_QUERY_FAILED)`，不静默放行、不判 OUT_OF_STOCK。
- 留痕：`JD_STOCK_QUERY_FAILED`（YELLOW）运营告警 + 审计日志，经 `REQUIRES_NEW` 独立提交，不受业务事务回滚影响（模式同 `OrderDraftService.recordRejectionAudit`）。
- SKU 查不到 `provider_skus` 映射 → `JD_STOCK_SKU_MAPPING_MISSING`（422）+ 拒绝审计。

### 竞态结论

- 查完即判定：京东查询与状态写入在同一事务内，先查后写，判定代表查询时刻的京东权威库存；同一履约并发的两次决策被 `FOR UPDATE OF f, ol, o` 串行化，第二次因 stage 已离开 `READY_TO_EXPORT` 得到 `STOCK_DECISION_ALREADY_APPLIED`。
- 跨履约的库存消耗不互斥：不预占库存，超卖与否由京东建单环节最终裁决（京东实时权威）。

### 需要主 agent 协调的点

1. **迁移（必须）**：`validate_stock_snapshot()` 与 `validate_procurement_ticket()` 目前按 `inventory_managed_by_us` 硬拒写；要让 `JD_WAREHOUSE` + `inventory_managed_by_us=false` 的履约方走通判定，需把两个触发器的拒绝条件放宽为「`THIRD_PARTY` 且未托管」（如 `provider_type <> 'JD_WAREHOUSE' AND NOT inventory_managed_by_us`）。种子数据中京东履约方为 `inventory_managed_by_us=true`，Demo/测试不受影响。
2. 若后续希望「单位换算系数」成为正式列而非 `external_codes` 保留键，可加 `provider_skus.pieces_per_unit NUMERIC(18,3)` 列并迁移存量 JSONB 键值（当前实现无迁移也可用）。
3. `goodsLevelList=["1"]` / `stockTypeList=[1]` 的取值语义（正品/可用）来自 Mock 默认值，REAL 联调时需按京东 ISC 文档核对编码。

## Comments

- 2026-08-13：此票及其实现记录已被 `jd-fulfillment-loop/02、03、04` 取代。已落盘代码中的默认换算、向上取整和 JD 缺货采购行为不构成已接受决策；保留文件仅用于追溯，不得继续领取或实现。
