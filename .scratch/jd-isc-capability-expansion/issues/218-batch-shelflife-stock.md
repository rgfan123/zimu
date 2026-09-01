# 218: 批次/效期库存明细接入——生鲜临期风险提前看见

**What to build:** 库存视图从「总量快照」升级到「批次+效期明细」：系统定时拉取京东仓
批次效期库存（`queryShelfLifeInventory`，按仓库+商品查批次效期明细）与批次异动流水
（`queryBatchChange`），落库后在库存页面按商品展示各批次的生产日期/到期日/数量，
并对临期批次（阈值可配）产生提醒。冻品/生鲜的临期风险从京东后台报表变成系统内
可见、可告警的数据。

**上下文：**
- 防腐层已就绪：`JDStockService.queryShelfLifeInventory / queryBatchChange`
  （`connector/jd/stock/`，只读作业面 REST 已存在），无业务落库。
- 库存决策服务（FulfillmentStockDecisionService）已用 queryStockSnapshot 做总量判断，
  且有 freshness-threshold（PT15M）新鲜度标签机制——效期维度是其自然延伸。
- 全品类为冷冻/生鲜肉品（牛腩、西冷、肥牛卷……），效期即货值；临期未发现=报废。
- 批次异动流水同时是出库对账的辅助证据（哪一批被哪张出库单消耗）。

**文件锚点：**
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/stock/JDStockService.java`（seam）
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/stock/JdStockClient.java`（REAL 实现）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/FulfillmentStockDecisionService.java`（库存决策宿主）

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 定时拉取批次效期库存并幂等落库，商品库存页可展开批次明细（到期日/数量）
- [ ] 临期阈值可配置，命中阈值的批次产生可见提醒（清单或看板）
- [ ] 批次异动流水可按商品/时间段查询，异动类型可读
- [ ] 拉取失败不影响现有库存快照链路；错误可观测
- [ ] MOCK 模式全链路可演示

## 关联既有票

- inventory-v1 #166（临期/过期 Dashboard）与 #168（External Location 快照边界）：本票是它们的京东侧批次/效期数据源，落地时应对齐 external snapshot 投影口径。
