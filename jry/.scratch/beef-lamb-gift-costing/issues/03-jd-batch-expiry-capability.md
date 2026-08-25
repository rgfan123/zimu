# 03 — 京东库存批次与效期接口能力（调研）

**Type:** research
**Status:** open
**Blocked by:** —

## Question

京东库存与出库接口到底能不能拿到**批次的生产日期 / 到期日 / 剩余保质期**？拿得到的话字段叫什么、精度如何、是否需要额外开关。

这决定票 08 的临期模型是「用主数据保质期天数估算」还是「用京东批次真值」。决策 6 已定两层方案、真值优先，但真值到底存不存在没人验证过。

### 已知线索

- `docs/research/jd-isc-api.md:75`：`StockResult` 含 `batchInfos`（类型 `BatchInfos`），与 `stockNum` / `usableNum` 同级；请求侧 `StockQueryRequest` 有嵌套的 `batchStock` / `batchInfos` 参数。
- 同文件 `:99`：`SoQueryResponse` 有 `deliveryBatchItemList`；`SoQueryRequest` 有 9 个 `*Flag: Integer` 开关，其中 `deliveryBatchItemFlag` 控制是否返回批次明细，**取值 0/1 待文档确认**。
- 两个京东 jar 在 `backend/libs/`，`docs/research/jdl-api-367/` 下有 HTML/JSON 接口文档。

### 要回答的

1. `BatchInfos` 的完整字段清单——有没有 `produceDate` / `expireDate` / `batchNo` / 剩余保质期天数？字段类型与格式（字符串日期？时间戳？）。
2. 库存查询要拿到批次，请求侧必须设哪些参数（`stockIndexes` 的取值、`batchStock` 嵌套对象怎么填）？不设会不会静默返回空批次而非报错。
3. `deliveryBatchItemFlag` 等 Flag 的取值语义（0/1 还是其他）。
4. 批次库存与 `stockNum`/`usableNum` 的关系——批次量之和是否等于总量，会不会有未分批次的余量。
5. 现有 `provider_stock_snapshots` 是 append-only 快照表，如果要存批次，是加子表还是把 `batchInfos` 落 JSONB？（给出建议，最终由票 08 定。）
6. **兜底结论**：如果京东确实不给效期，明确说清，票 08 就只走主数据估算这一层。

### 交付

findings 写 `.scratch/beef-lamb-gift-costing/research/03-jd-batch-expiry.md`，结论回写本票 `## Answer`。证据以 jar 反编译签名或 `docs/research/jdl-api-367/` 原文为准，**不接受推测**。
