# 08 — 临期与库存约束模型

**Type:** grilling
**Status:** open
**Blocked by:** 01、03

## Question

用户对这件事的原话是：「得让他多学点儿东西，然后根据他的库存的时间，比如说产品的保质期，对吧？库存的多少，去综合考量怎么去组这个东西。」

即：组礼包不只看成本，还要看**这批货还能放多久、还剩多少**——临期的优先出、库存不够的不能组。本票定这个模型。

### 现状

- `app.provider_stock_snapshots`：`stock_num` / `usable_num`，append-only，**只覆盖 `inventory_managed_by_us` 的履约方（京东仓）**，第三方库存不在系统内。
- **本系统内无批次、无生产日期、无效期**。Excel 成本表也没有保质期列。
- **但京东侧有真值**（票 03 已 resolved，见 `../research/03-jd-batch-expiry.md`）：
  - `queryStock` 传 `stockIndexes=2` → `StockResult.batchInfos` 含 `productDate`(生产日期) / `expireDate`(到期日) / `createTime`(**入库日期**) / `lot`，共 17 个批属性。**注意字段是 `productDate` 不是 `produceDate`，且没有 `batchNo`** —— 批次身份靠批属性组合，建表要显式指纹列。
  - `queryShelfLifeGoodsList`（`/integratedsupplychain/stock/Shelflifeinventory/query/v1`）**直接返回 `remainDays` / `remainDaysRate` / `shelfLifeDays` 和 6 档临期 `status`** —— 剩余保质期不用自己算。
  - `queryGoodsInfo.GuaranteePeriodResult` 给京东侧临期阈值（`adventDay` / `regularAdventDay` / `urgentAdventDay`）—— 可作为分档阈值的现成参考，不必自己拍。
  - `addSoOrder.leftExpirationPercent` 可把「只出剩余保质期 ≥ X%」**下推给京东执行**，不必自己挑批次。
  - `JDStockService` 的 `queryBatchChange` / `queryShelfLifeGoods` / `queryShelfLifeInventory` **客户端代码已写好**，只是结果没落库。

### 待决策点

1. ~~**剩余保质期怎么算**~~ **原「没有入库日期，一期方案是空的」阻塞已被票 03 证伪**，改为：**批次数据怎么落库**。
   - 取哪条路：`queryStock(stockIndexes=2)` 自己算，还是直接用 `queryShelfLifeGoodsList` 的 `remainDays`？（倾向后者做主、前者做批次明细补充。）
   - 落哪张表：`provider_stock_snapshots` 是 append-only 总量快照，批次是它的下一级。加子表 `provider_stock_batches`，还是把 `batchInfos` 落 JSONB？（票 03 倾向子表 + 显式批次指纹列，因为没有 `batchNo` 可做主键。）
   - 采集频率与时效：批次快照多久拉一次，多旧的快照还算数。
   - ⚠️ **不要改 `ShipmentJdStockCheckService`**：它写死 `stockIndexes="1"` 且假设「一商品一仓恰好一行」，切批次维度会触发 `JD_STOCK_RESPONSE_AMBIGUOUS` 拦单。**批次采集必须走新路径**，这是票 03 挖出来的生产风险。
   - **前置**：票 03 列了 4 项必须 UAT 实测才能定的事（批次分页无 cursor、批次量之和与总量的关系、`expireDate` 三种矛盾格式、门闩冲突）。其中「批次量之和是否等于总量」**直接影响可用量会不会被高估**，本票开工前建议先跑那次 UAT。
2. **临期评分**：剩余保质期 / 保质期天数 = 剩余比例（京东已给 `remainDaysRate`，不必自己算）。分档还是连续分值？阈值自己定还是**直接采用京东的 `adventDay` / `regularAdventDay` / `urgentAdventDay` 与 6 档 `status`**？（倾向采用京东档位——与仓库实际管控口径一致，避免两套标准打架。）
3. **动销速度**（map 雾区）：临期风险 = 剩余保质期 vs 卖完所需时间。动销速度用现有 `analytics.v_product_daily`（发货量口径）够不够？口径是 Canonical SKU 件数，与礼包组件展开后的口径是否一致？
4. **库存约束**：礼包可用份数 = 各组件 `usable_num / 需求数量` 的**木桶短板**（这个算法在 `wayfinder/tickets/product-bundle-and-pack-mapping.md` 已定，本票沿用不重造）。但库存快照有时效——用多旧的快照算才算数？
5. **第三方履约方的库存**：不在系统内，京东那套效期真值也覆盖不到。含第三方组件的礼包，库存与临期约束怎么表达？（标为不可判定 vs 排除该组件）**决策 6 的「主数据保质期天数」这一层在这里才是真正必需的兜底**，不是给京东商品用的。
6. **约束还是偏好**：临期是硬约束（临期的必须用）还是软偏好（优先用，但成本更优时可让步）？——这直接变成票 12 目标函数里的一项。

### 与其他票的关系

- 依赖票 01（保质期字段落位）、票 03（京东批次效期是否可得）。
- 票 12（反向搜索）把本票的评分与约束吃进目标函数。
