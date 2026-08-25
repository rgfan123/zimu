# 03 — 京东库存批次与效期接口能力（调研结论）

**调研日期：** 2026-08-25
**对应票：** `.scratch/beef-lamb-gift-costing/issues/03-jd-batch-expiry-capability.md`

**证据源（按可信度）：**

1. `backend/libs/IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar` —— `javap -p` 反编译字段签名（一手，最可信）
2. `docs/research/jdl-api-367/json/*.json` —— 京东官方 API 详情响应快照（2026-08-11 下载，含字段中文描述与枚举）
3. `docs/research/jdl-api-367/html/*.html` —— 官方「下载本页」HTML（与 JSON 同源，已交叉核对）

本文所有字段名、类型、中文描述均为上述来源原文。**未找到证据的项已单列在「未解决项」章节，未用行业惯例填充。**

---

## 结论摘要

**京东能拿到批次效期真值，而且比票 08 预期的更多。** 结论不是「只能用主数据估算」，兜底方案不必启用。

三层证据都成立：

1. **批次库存维度**：`queryStock`（`/integratedsupplychain/stock/query/v1`）传 `stockIndexes=2` 时，`StockResult.batchInfos` 返回 `productDate`（生产日期）、`expireDate`（到期日期）、`createTime`（入库日期）、`lot`（生产批号）等 17 个批属性。
2. **保质期专用接口（本次调研最重要的发现）**：存在一个票里没提过的接口 **`queryShelfLifeGoodsList` 保质期商品库存查询**（`/integratedsupplychain/stock/Shelflifeinventory/query/v1`），直接返回 **`remainDays`（剩余天数，Integer）** 和 **`remainDaysRate`（剩余保质期比例，Double）**，以及 `shelfLifeDays` / `productDate` / `expireDate` / `lockDate` / `status`。**剩余保质期不需要我们自己算，京东已经算好。**
3. **主数据保质期与临期阈值**：`queryGoodsInfo` 的 `GuaranteePeriodResult` 返回 `isShelfLifeMgmt`、`shelfLifeDays`、`shelfLifeMonths`、`warningDay`、`regularAdventDay`、`urgentAdventDay`、`adventDay` 等，即京东侧自己的临期阈值配置。

还有第四条，出乎意料：**下单时可以直接向京东提要求**。`addSoOrder`（销售出库单创建）请求侧有 `leftExpirationPercent`（保质期剩余百分比，0–1）+ `leftExpirationPercentOperate`（运算规则 1–6），即「这单只出剩余保质期 ≥ X% 的货」可以下推给京东执行，不必我们自己挑批次。这对票 12 的目标函数是一个实现层的捷径。

对票 08 的直接影响：票 08「待决策点 1」担心的「连入库日期都没有，决策 6 的一期方案是空的」**不成立**——入库日期（`createTime`）、生产日期、到期日期、剩余天数、剩余比例全都能从京东拿到真值。

**而且代码里已经有一半了。** `cn.zimu.fulfillment.connector.jd.stock.JDStockService` 已经封装了 `queryBatchChange` / `queryShelfLifeGoods` / `queryShelfLifeInventory` 三个方法，`JdStockController` 已暴露只读 HTTP 端点，前端 `JdStockQueryPage.tsx` 已有字段中文标签——**但这些结果只回给浏览器展示，没有任何持久化**。票 08 要做的不是从零对接，而是把已有的只读通道接进快照存储。详见「现状与落地影响」章节。

**但有四个必须先验证的实现风险**（详见 Q2 / Q4 / 未解决项）：批次分页的游标在响应结构里不存在；批次行与总量的关系官方文档没有任何说明；`expireDate` 的格式在不同接口间三种写法互相矛盾；现有 JD 库存校验代码写死了 `stockIndexes="1"` 且假设「一个商品一个仓只回一行」，切批次维度会直接触发它的歧义拦截。这四项都需要 UAT 实测或改代码，不能靠读文档定稿。

---

## 逐个回答票里的 6 个问题

### Q1. `BatchInfos` 的完整字段清单——有没有 `produceDate` / `expireDate` / `batchNo` / 剩余保质期天数？

**有生产日期和到期日期，没有「剩余保质期天数」，也没有叫 `batchNo` 的字段。** 注意票里写的 `produceDate` 拼写不对，实际字段名是 **`productDate`**。

`queryStock` 响应侧的 `BatchInfos` 共 17 个字段，**全部为 `String`**。

jar 证据：`com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryStock.BatchInfos`
文档证据：`docs/research/jdl-api-367/json/1612-queryStock.json`，路径 `response/data/resultList/result/batchInfos`

| 字段 | 类型 | 官方中文描述（原文） | 示例值 |
|---|---|---|---|
| `purchaseNo` | String | 采购单号；长度：1-100字符 | `EPL000000001` |
| **`productDate`** | String | **生产日期；（时间戳格式）长度：1-100字符** | `2020-1-1` |
| **`expireDate`** | String | **到期日期；（时间戳格式）长度：1-100字符** | `2020-1-1` |
| `spareBatch` | String | 备件条码；长度：1-100字符 | `SI0000000101` |
| `supplier` | String | 供应商；长度：1-100字符 | `供应商W` |
| `receiptDate` | String | 收货日期；（时间戳格式）长度：1-100字符 | `2020-1-1` |
| `plu` | String | plu管理批属性；长度：1-100字符 | `plu管理批属性1` |
| `logisticCompany` | String | 物流公司；长度：1-100字符 | `A物流公司` |
| `origin` | String | 原产地；长度：1-100字符 | `原产地A` |
| **`lot`** | String | **生产批号；长度：1-100字符** | `RS000000001` |
| `manufacturer` | String | 制造商；长度：1-100字符 | `制造商X` |
| `packageLot` | String | 包装批号；长度：1-100字符 | `UR000000001` |
| `boxNo` | String | 箱号属性；长度：1-100字符 | `箱号X` |
| `noSale` | String | 不可售；长度：1-100字符 | `不可售` |
| **`createTime`** | String | **入库日期；长度：1-100字符** | `2020-1-1` |
| `store` | String | 门店；长度：1-100字符 | `E000000001` |
| `erpPurchaseNo` | String | 商家采购单号；长度：1-100字符 | `123456` |

要点：

- **没有 `batchNo`**。批次身份由**批属性组合**表达，最接近「批号」的是 `lot`（生产批号）和 `packageLot`（包装批号）。这对建表有直接影响，见 Q5。
- **没有剩余保质期天数**。`BatchInfos` 里没有任何 `remainDays` / `shelfLife` 类字段——但另一个接口有，见下方「剩余保质期」小节。
- **`createTime` = 入库日期**，正是票 08「待决策点 1」缺的那个数。
- 日期格式描述自相矛盾：中文描述写「（时间戳格式）」，示例值却是 `2020-1-1`。见「未解决项 U3」。

#### 剩余保质期：在另一个接口里，是真值

`BatchInfos` 没有，但 **`queryShelfLifeGoodsList`（保质期商品库存查询）有**，且是京东算好的。

- 官方文档：`docs/research/jdl-api-367/json/1884-queryShelfLifeGoodsList.json`，apiName「保质期商品库存查询」，apiUrl `/integratedsupplychain/stock/Shelflifeinventory/query/v1`
- jar：`com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStock`

响应对象 `ShelfLifeGoodsStock` 的 19 个字段（jar `javap -p` 原文类型）：

| 字段 | 类型 |
|---|---|
| `ownerNo` / `ownerName` | String |
| `warehouseNo` / `warehouseName` | String |
| `goodsNo` / `erpGoodsNo` / `goodsName` | String |
| `goodsLevel` | String |
| `totalNum` | Integer |
| `usableNum` | Integer |
| **`shelfLifeDays`** | **Integer** |
| **`productDate`** | **String** |
| **`expireDate`** | **String** |
| `lockDate` | String |
| **`remainDays`** | **Integer** |
| **`remainDaysRate`** | **Double** |
| `status` | String |
| `createTime` | String |
| `eclpWarehouseNo` | String |

官方描述原文（`docs/research/jdl-api-367/json/1884-queryShelfLifeGoodsList.json`），`apiRemark`：**「根据货主编码和库房编码，查询仓库内保质期商品的库存情况」**。关键字段描述：

| 字段 | 官方中文描述（原文） | 示例 |
|---|---|---|
| `shelfLifeDays` | 保质期天数, 无长度限制 | `30` |
| `productDate` | 生产日期，**格式：yyyy-MM-dd**，长度：1-50字符 | `2023-11-11` |
| `expireDate` | 到期日期，**格式：yyyy-MM-dd**，长度：1-50字符 | `2023-11-11` |
| `lockDate` | 锁定日期，格式：yyyy-MM-dd，长度：1-50字符 | `2023-11-11` |
| `remainDays` | 剩余天数，长度：1-50字符 | `10` |
| `remainDaysRate` | 剩余天数占比，长度：1-50字符 | `0.33` |
| `status` | 状态(正常，2预警，3临期，4过期，5常规临期，6紧急临期)，长度：1-2字符 | `1` |
| `createTime` | 记录日期，格式：yyyy-MM-dd HH:mm:ss | `2023-11-11 10:01:01` |

请求对象 `ShelfLifeGoodsStockQueryRequest`（jar 字段签名 + 官方描述）：

- **`ownerNo` 必填**（事业部编码，EBU 开头）、**`warehouseNo` 必填**（库房编码，9字符）、**`pin` 必填**
- 可选：`goodsNo`、`erpGoodsNo`（同时填取交集）、`goodsLevel`（100良品/200残品）
- `status: Integer` —— 官方原文：**状态(1正常，2预警，3临期，4过期，5常规临期，6紧急临期)**
- `currentPage: Integer`、`pageSize: Integer` —— **「当前页数量，范围为1到50；默认：50」**

`remainDaysRate` 正是票 08「待决策点 2」要的「剩余保质期 / 保质期天数 = 剩余比例」，`status` 的 6 档分类正是「分档还是连续分值」里的分档方案，两者都不必自己造。

**三个必须注意的限制：**

1. **`warehouseNo` 必填，没有「全仓」模式** —— 要覆盖多个仓必须逐仓循环调用；且 `pageSize` 上限只有 **50**（`queryStock` 是 1000），全量拉取的调用量要按仓数 × 页数估算。
2. **`ShelfLifeGoodsStock` 不返回任何批次标识** —— 没有 `lot` / `purchaseNo` / `supplier` / `boxNo`。官方文档**也没有说明一行的粒度是什么**（是否可能同一商品同一 `expireDate` 出现多行、按什么键唯一），属未找到证据（见 U8）。**因此它无法与 `queryStock` 的批次行做可靠 join**，这直接决定了 Q5 的建表方案。
3. **`status` 的响应侧描述有笔误** —— 原文是「状态(**正常**，2预警，3临期，4过期，5常规临期，6紧急临期)」，`1` 丢了。请求侧描述则完整写了「(1正常，...)」。取值 `1 = 正常` 由请求侧描述与响应示例值 `"status":"1"` 交叉印证，但响应侧原文确实缺失。
4. **`remainDaysRate` 的计算公式、`lockDate` 的触发条件、`status` 各档对应的天数阈值，文档均未给出**（见 U6）。阈值本身在商品主数据里（`adventDay` / `regularAdventDay` / `urgentAdventDay`），但**「1884 的 status 是拿这些阈值算的」这个联系文档没有明说**，属推断。

该接口的错误码（官方原文）：`2000` 传值问题，必填字段为空 / `2002` 参数不合法 / `2014` 仓库不存在 / `3000` 业务异常 / `3009` 事业部无权限或事业部不存在 / `4000` 系统异常。

#### ⚠️ SDK 命名陷阱（会选错接口）

京东给「盘盈亏查询」分配的 URL 段名字叫 `shelflifegoods`，与保质期无关。SDK 包装类按 URL 段命名，于是：

| LOP 包装类 | URL | 实际业务 | 领域请求类 |
|---|---|---|---|
| `IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest` | `/integratedsupplychain/stock/shelflifegoods/query/v1` | **盘盈亏查询**（doc 1576 `queryCheckStock`） | `queryCheckStock.CheckStockQueryRequest` |
| `IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest` | `/integratedsupplychain/stock/Shelflifeinventory/query/v1` | **保质期商品库存查询**（doc 1884） | `queryShelfLifeGoodsList.ShelfLifeGoodsStockQueryRequest` |

证据：`javap -c` 提取的字符串常量 + `docs/research/jdl-api-367/manifest.json` 中 1576 与 1884 两条 entry 的 `apiUrl`。

**要保质期数据必须用 `Shelflifeinventory` 那个**，注意 URL 里的 **大写 `S`**（`Shelflifeinventory`），这是京东官方 `apiUrl` 原文，不是笔误，照抄即可。

#### 主数据保质期与临期阈值也能拿到

`queryGoodsInfo`（doc 1610）响应路径 `data.goodsInfo.management.guaranteePeriod`，类型 `GuaranteePeriodResult`。官方中文描述原文：

| 字段 | 类型 | 官方中文描述（原文） |
|---|---|---|
| `isShelfLifeMgmt` | Integer | 是否保质期管理（**1否，2是**），长度：1-3字符 |
| `shelfLifeDays` | Integer | 保质期天数，长度：1-11字符 |
| `shelfLifeMonths` | Integer | 保质期月数 |
| `safeDimension` | Byte | 保质期管理维度【**0-天，1-月**】 |
| `safeOffsetDay` | Integer | 效期偏移天数 |
| `adventDay` | Integer | 临期天数，长度：1-11字符 |
| `regularAdventDay` | Integer | 常规临期预警天数，长度：1-11字符 |
| `urgentAdventDay` | Integer | 紧急临期预警天数，长度：1-11字符 |
| `warningDay` | Integer | 预警天数，长度：1-11字符 |
| `instoreThreshold` | Float | 入库阈值，长度：1-20字符 |
| `outstoreThreshold` | Float | 出库阈值，长度：1-20字符 |
| `allowedDay` | Integer | （jar 有此字段，1610 文档响应侧未列出——见 U9） |

票 08「待决策点 2」问的「阈值谁定」：**可以先采用京东主数据里的既有阈值**（`adventDay` / `regularAdventDay` / `urgentAdventDay` / `warningDay`），而不是我们拍脑袋。注意 `isShelfLifeMgmt` 的枚举是 **1否 / 2是**（不是 0/1），容易踩坑。

⚠️ **写入侧是一次性的。** `1609-saveGoodsInfo` 的官方描述明确标注 `instoreThreshold`「入库保质期阈值(**不可修改**，新增时如果保质期大于0则必填，且入库阈值在0-1之间)」、`outstoreThreshold` 同理、`shelfLifeDays`「保质期天数(**默认不可修改**…)」。**这些值在商品创建时定死，事后改不了**——如果现有商品主数据里保质期没配或配错，不是调个接口就能补的，需要走京东侧流程。这一点票 01（保质期字段落位）要知道。

#### 京东支持的 12 个批属性（`lotAttr` 的取值来源）

`1610-queryGoodsInfo` 响应 `management.batchAttribute` 的官方描述原文列出了京东支持的全部批属性：

> 批次属性列表(**1采购单号, 2生产日期, 3供应商, 4收货日期, 5plu管理批属性, 6物流公司, 7原产地, 8批号, 9制造商, 10包装批号, 11箱号属性, 12不可售属性**，不可修改,新增时如果批属性管理为否则不能修改)

这份清单同时说明了**每个商品实际启用了哪些批属性**（该字段返回的是该商品配置的属性列表），这决定了它的批次行里哪些字段有值。**生产日期是 2 号属性** —— 但注意这里没有「到期日期」这一项，到期日期应是由生产日期 + 保质期推导的派生值（**此为推断，文档未明说**，见 U10）。

---

### Q2. 库存查询要拿到批次，请求侧必须设哪些参数？不设会不会静默返回空批次？

文档证据：`docs/research/jdl-api-367/json/1612-queryStock.json` 请求侧；jar：`queryStock.StockQueryRequest`。

**必设 `stockIndexes=2`。** 官方描述原文：

> `stockIndexes` String **[必填]** —— 库存索引，默认：1（1查询仓库库存，**2查询批次库存**，3查询店铺库存，4查询逻辑库存）长度：1字符

响应侧 `batchInfos` 的描述原文：

> `batchInfos` BatchInfos —— **批次信息，当查询批次库存（stockIndexes=2）时返回**

**「不设会不会静默返回空批次」——会，而且是设计如此。** `stockIndexes` 虽标必填但**默认值为 1**（查询仓库库存），此时按官方描述 `batchInfos` 根本不返回。所以传错维度不会报错，只会拿到没有批次的行。这是一个静默失败点，客户端应显式断言 `stockIndexes="2"`。

批次维度下还要设的参数：

| 参数 | 官方描述原文 | 备注 |
|---|---|---|
| `warehouseNo` | 当stockIndexes=3时非必填，**其余场景必填** | 批次维度下必填 |
| `batchStock` (对象) | **批次库存，当stockIndexes=2时，必填** | 见下 |
| `batchStock.cursor` | 游标，滚动分页查询参数；分页遍历数据时使用，首次传空，后续每次传上次查询返回值，此字段仅支持查询批次库存 | ⚠️ 见 U1 |
| `batchStock.startTime` | 当前批次商品**入库操作**开始时间；格式：**yyyy-MM-dd** | 按入库时间过滤 |
| `batchStock.endTime` | 当前批次商品**入库操作**结束时间；格式：**yyyy-MM-dd** | |
| `currentPage` | 当前页；默认第一页**查询批次库存必填**，页码；默认：1 | String 类型 |
| `pageSize` | 系统默认100条，**查询批次库存必填**，默认：10；最大：1000 | String 类型 |

**`batchInfos` 在请求侧是过滤器，与响应侧同名但语义完全不同。** 请求侧是 `List<BatchInfo>`（jar 证实），每个 `BatchInfo` 三个字段：

- `lotAttr: String` —— 批属性名（示例值 `supplier`）
- `lotValue: String` —— 批属性值（示例值 `2025040701`）
- `operator: Integer` —— 官方原文：**1：等于 2：大于 3：小于 4：大于等于 5：小于等于 6：不等于，当前仅生产日期、到期日期、收货日期支持范围查询，其他的均为精准查询**

**这一条对票 08 很关键**：京东**支持按到期日期做范围查询**（`expireDate` + `operator<=`），也就是「查所有 N 天内到期的批次」可以直接下推给京东，不必全量拉回来自己筛。这是 62 份文档里**唯一**可以按生产日期/到期日期做范围过滤库存的地方。

⚠️ **但 `lotAttr` 到底该传什么字符串，文档没有给。** 官方只给了示例值 `supplier`，没有列出可接受的 key 全集。最可能的取值来源是上面 `batchAttribute` 的 12 项属性名（或其英文字段名如 `productDate` / `expireDate`），**但这是推断，未经证实**。见 U11——这是使用范围过滤的直接阻塞项，必须 UAT 试。

#### 下单时可以直接要求剩余保质期（`addSoOrder`）

`1596-addSoOrder` 请求侧 `cargoInfos.cargoInfo` 下有两个字段（官方描述原文）：

- `leftExpirationPercent: Double` —— **保质期剩余百分比，0到1之间；**
- `leftExpirationPercentOperate: Integer` —— **保质期剩余百分比运算规则，1：等于 2：大于 3：小于 4：大于等于 5：小于等于 6：不等于，默认=1，长度：1字符**

同一层还可传 `batchInfos.productDate` / `batchInfos.expireDate`（格式 `yyyy-MM-dd`）来指定批次。

**这是 62 个接口里唯一能在下单时约束剩余保质期的地方。** 对票 12（反向搜索的目标函数）意味着：「优先出临期货」不一定要在我们这边挑批次再指定，也可以表达成对京东的一个约束（例如「这单必须出剩余保质期 ≤ 30% 的货」用 `leftExpirationPercent=0.3` + `operate=5`）。两条路线的取舍留给票 08/12，但**能力是存在的**。

#### 批次维度下 `stockType` 有另一套枚举

`stockType` 的官方描述在末尾另起一段（原文）：

> ...23:采购在途 **批次库存类型；默认：1； (枚举1正常，,2预警,3临期,4过期)** ，此字段不支持查询店铺库存，查询逻辑库存

即批次维度下 `stockType` 是 **1正常 / 2预警 / 3临期 / 4过期**，京东自己就给批次打了临期标签，可作为请求侧过滤条件。⚠️ 但**响应侧** `stockType` 的描述只列了 1-23 的仓库库存枚举，没有重复批次枚举 —— 响应里到底回哪一套，文档没说清，见 U4。

---

### Q3. `deliveryBatchItemFlag` 等 Flag 的取值语义

**已确认：全部是 0/1，`deliveryBatchItemFlag` 默认 0（不返回）。** 票里标的「待文档确认」现在可以去掉。

文档证据：`docs/research/jdl-api-367/json/1632-querySoOrder.json` 请求侧，官方描述原文逐条如下：

| Flag | 官方描述原文 | 默认 |
|---|---|---|
| `deliveryItemFlag` | 是否查询销售出库明细（0：否；1：是，**默认=是**） | **1** |
| `deliveryPackageFlag` | 是否查询销售出库包裹信息（0：否；1：是，默认=0） | 0 |
| `deliveryStatusFlag` | 是否查询销售出库单状态流水明细（0：否；1：是，默认=0） | 0 |
| **`deliveryBatchItemFlag`** | **是否查询销售出库批次明细（0：否；1：是，默认=0）** | **0** |
| `deliveryProductInfoFlag` | 是否查询销售出库产品明细（0：否；1：是，默认=0） | 0 |
| `deliveryRejectItemFlag` | 是否查询销售出库拒收明细（0：否；1：是，默认=0） | 0 |
| `deliveryRejectPictureUrlFlag` | 是否查询销售出库拒收图片明细（0：否；1：是，默认=0） | 0 |
| `deliveryBoxFlag` | 是否查询销售出库箱明细（0：否；1：是，默认=0） | 0 |
| `deliverySerialNoFlag` | 是否查询销售出库序列号信息（0：否；1：是，默认=0） | 0 |

全部 `Integer`（jar `querySoOrder.SoQueryRequest` 证实 9 个 Flag 均为 `java.lang.Integer`）。

#### 出库单里效期出现在两个位置，不要只看一个

jar 证实 `DeliveryItem` 和 `DeliveryBatchItem` **都**持有一个 `BatchInfosQueryResult batchInfos`：

- `querySoOrder.DeliveryItem`：`goodsNo, price, planQuantity, realQuantity, erpGoodsNo, orderLine, batchInfos, actualWeight`
- `querySoOrder.DeliveryBatchItem`：`batchQuantity: Integer, goodsNo, erpGoodsNo, orderLine, batchInfos, actualWeight`

含义差别：

- `deliveryItemList`（`deliveryItemFlag` **默认就是 1**）每行挂**一个** `batchInfos` 对象 —— 一个订单行只能表达一个批次。
- `deliveryBatchItemList`（`deliveryBatchItemFlag` **默认 0**）官方描述：**「批次出库商品明细列表，当商品是批次管理的商品时会返回」**，每行带 `batchQuantity`（实际出库数量）—— 这才是**一行拆多批次**时的正确来源。

**结论：要准确知道「这单出的是哪几个批次、各出多少」，必须显式设 `deliveryBatchItemFlag=1`**，不能依赖默认开着的 `deliveryItemList.batchInfos`（一个行多批次时它表达不了）。

`BatchInfosQueryResult` 共 14 个字段（jar 原文）：`productDate, expireDate, packageLot, purchaseNo, lot, supplier, receiptDate, plu, logisticCompany, origin, manufacturer, boxNo, noSale, store`。此处 `productDate` / `expireDate` 官方明确标 **`格式：yyyy-MM-dd`**（示例 `2016-10-10`），与库存接口的「时间戳格式」说法不一致，见 U3。

#### 官方文档在此处有两处缺陷（以 jar 为准）

同一个 Java 类 `BatchInfosQueryResult` 在文档里被渲染了两次，两次不一致：

- 在 `deliveryItemList/deliveryItem/batchInfos` 下：14 个字段，`receiptDate` 描述为「收货日期」——**正确**。
- 在 `deliveryBatchItemList/deliveryBatchItem/batchInfos` 下：只列了 13 个字段（**漏掉 `packageLot`**），且 `receiptDate` 被错标成「**包装批号**」——**文档错误**。

jar 证实这两处是同一个类、字段完全相同。实现时以 jar 为准：`packageLot` 存在，`receiptDate` 是收货日期。

---

### Q4. 批次库存与 `stockNum`/`usableNum` 的关系——批次量之和是否等于总量？

**未找到证据。官方文档对此没有任何说明，本题不能下结论，必须 UAT 实测。**

已查证的事实：

- `StockResult.batchInfos` 是**单个对象，不是列表**（jar：`private BatchInfos batchInfos;`；文档类型列写 `BatchInfos` 而非 `List<BatchInfos>`）。所以 `stockIndexes=2` 时，**一个 `StockResult` 行 = 一个批次**，`resultList` 是批次行的集合。
- 但 `stockNum` / `usableNum` 的官方描述**始终是**「商品总库存」/「可用库存」，在批次维度下**没有改写**，也没有任何一句说明它们此时是「该批次的量」还是「该商品的总量」。
- 文档中**不存在**「批次量之和等于总量」「可能存在未分批次余量」之类的表述。我检索了 `1612-queryStock` 的 JSON 全部参数描述与 HTML 全文（关键词：注意 / 说明 / 限制 / 批次 / 总库存 / 可用库存 / 汇总 / 之和 / 维度），HTML 与 JSON 同源，**没有独立的「注意事项」章节**。

**从字段结构可以合理推断**（注意：这是推断，不是证据）每行 `stockNum` 应是该批次的量，否则同一商品的多个批次行会重复同一个总量、`totalNum` 分页也失去意义。但**「未分批次余量」是否存在，完全没有依据**——这恰恰是食品临期场景最危险的缺口：如果有一部分库存没有批属性（例如历史遗留、未启用批次管理的商品），按批次行求和会**少算**可用量，进而高估「可组份数」。

**建议的 UAT 验证步骤（票 08 实施前必须做）：**

1. 选一个已知有多批次的 SKU，先用 `stockIndexes=1`（仓库维度）取 `stockNum` / `usableNum` 作为基准。
2. 同一 SKU 用 `stockIndexes=2` 取全部批次行，对 `stockNum`、`usableNum` 分别求和。
3. 比较两者：相等 → 批次完全覆盖；批次和 < 总量 → **存在未分批次余量，必须在模型里显式表达**；批次和 > 总量 → `stockNum` 在批次维度下是商品总量的重复值，需改用别的字段。
4. 同时对一个**未启用保质期管理**的 SKU（`GuaranteePeriodResult.isShelfLifeMgmt` 为否）重复一遍，确认它在批次维度下是否返回 0 行。

在这一步做完之前，票 08 的库存约束（木桶短板算法）**不应该切到批次口径**，否则可能高估可用库存。

---

### Q5. 存批次是加子表还是把 `batchInfos` 落 JSONB？

（本节为建议，最终由票 08 定。）

#### 仓库侧现状（已核查，含具体位置）

`app.provider_stock_snapshots` 定义在 `backend/src/main/resources/db/migration/V1__baseline.sql:187`（Flyway，无 Liquibase）：

```sql
CREATE TABLE app.provider_stock_snapshots (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    fulfillment_provider_id BIGINT NOT NULL REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT,
    sku_id              BIGINT NOT NULL REFERENCES app.skus(id) ON DELETE RESTRICT,
    warehouse_code      VARCHAR(128) NOT NULL,
    stock_num           NUMERIC(18,3) NOT NULL CHECK (stock_num >= 0),
    usable_num          NUMERIC(18,3) NOT NULL CHECK (usable_num >= 0 AND usable_num <= stock_num),
    synced_at           TIMESTAMPTZ NOT NULL,
    source_ref          VARCHAR(255),
    raw_payload         JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (btrim(warehouse_code) <> '')
);
```

`V14__classify_provider_stock_snapshot_units.sql` 追加了 `quantity_unit VARCHAR(32)` 与 `source_type VARCHAR(64)`（均 NOT NULL DEFAULT `'UNKNOWN'`，各带非空白 CHECK）。

四个对建表方案有硬约束的事实：

1. **append-only 是数据库层强制的**，不是约定：`trg_provider_stock_snapshot_append_only` 在 `BEFORE UPDATE OR DELETE` 上触发 `app.reject_mutation()`，直接 `RAISE EXCEPTION '% is append-only'`（ERRCODE `55000`）。子表必须沿用同样的触发器。
2. **写入被 `trg_stock_snapshot_scope` / `app.validate_stock_snapshot()` 把门**。`V16__allow_external_jd_stock_observations.sql` 里的条件：非自管履约方时，必须同时满足 `provider_kind='JD_WAREHOUSE'` **且** `quantity_unit='JD_PIECE'` **且** `source_type='JD_ISC_QUERY_STOCK'`，否则 `RAISE EXCEPTION 'third-party inventory is outside this system'`。**批次快照若引入新的 `source_type`（如 `JD_ISC_QUERY_STOCK_BATCH`），必须同步改这个校验函数**，否则写入直接被拒。这是最容易漏的一步。
3. **没有 JPA 实体，也没有 Repository。** 全表访问都是裸 `JdbcTemplate`（已确认 23 个 `@Table` 实体里没有它）。JSONB 在这条路径上的写法是 Jackson 序列化成 `String` + SQL 里 `?::jsonb`；而 JPA 实体路径用的是 Hibernate 6 原生 `@JdbcTypeCode(SqlTypes.JSON)`（如 `ProviderSku.java:35-38`），全仓无 `AttributeConverter`。新子表按现状应沿用 JdbcTemplate 风格。
4. **`raw_payload` 是全仓既定命名约定**（37 个 jsonb 列 / 33 张表，`trackings`、`channel_messages`、`wecom_events` 都叫这个名）。但注意 `provider_stock_snapshots.raw_payload` 是少数**没有** `CHECK (jsonb_typeof(...) = 'object')` 形状守卫的之一；新表应补上守卫。数组型 jsonb 的既有先例是 `shipment_jd_outbounds.submitted_cargo_snapshot`（`V18:13`，带 `jsonb_typeof(...) = 'array'` 守卫）。

另外：`app` 下 52 张表**没有任何一张建模批次/lot 维度**，`provider_stock_snapshots` 是唯一的库存观测表。`app.procurement_receipt_items` 看似合适但没有任何 lot/效期列。所以这是新增，不是改造。

修改前建议先读 `docs/schema.md:66`，那里写了这张表的治理规则（京东云仓行是受控的只读例外、append-only、无法证明单位的历史行保持 `UNKNOWN`）。

#### 建议

**新增独立子表 `app.provider_stock_batch_snapshots`，不要把 `batchInfos` 整块塞 JSONB。**

理由（都基于上面已证实的字段事实）：

1. **效期要参与排序、过滤和聚合，不是留档。** 票 08 要按剩余保质期分档、票 12 要把它塞进目标函数。`expireDate` / `remainDays` 必须是可索引的一等列（`date` / `integer`），JSONB 里的 `->>'expireDate'` 既要类型转换又难走索引。
2. **批次是一个真实的实体维度，不是附属属性。** 已证实 `stockIndexes=2` 时**一行 = 一个批次**，天然就是子表的行，父行（商品级快照）与子行（批次级）是干净的 1:N。
3. **批次没有自然主键。** 已证实**没有 `batchNo`**，身份靠批属性组合（`lot` / `packageLot` / `productDate` / `expireDate` / `purchaseNo` / `supplier` …）。这意味着需要一个**显式的批次指纹列**（对参与身份的批属性做规范化后哈希），JSONB 方案下这个指纹无处安放。
4. **append-only 快照语义要保持一致。** 子表应与父表同源同一个 `captured_at` / snapshot id，随父快照一起追加，不做原地更新。

建议的最小列集（字段名与类型依据本文 Q1 表，数值类型对齐父表的 `NUMERIC(18,3)`）：

- 关联与口径：`snapshot_id BIGINT NOT NULL REFERENCES app.provider_stock_snapshots(id)`、`fulfillment_provider_id`、`sku_id`、`warehouse_code`、`synced_at TIMESTAMPTZ`、`quantity_unit`、`source_type`
- 批次身份：`batch_fingerprint`（规范化哈希，NOT NULL）、`lot`、`package_lot`、`purchase_no`、`erp_purchase_no`、`supplier`
- **效期（一等列）**：`product_date date`、`expire_date date`、`receipt_date date`、`inbound_date date`（← `createTime`，票 08 待决策点 1 要的那个）
- 数量：`stock_num NUMERIC(18,3)`、`usable_num NUMERIC(18,3)`、`batch_stock_type smallint`（1正常/2预警/3临期/4过期）
- 留档：`raw_payload JSONB NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(raw_payload) = 'object')` —— 沿用全仓命名约定，补上父表缺的形状守卫；存剩余低频批属性（`plu` / `origin` / `manufacturer` / `boxNo` / `noSale` / `store` / `logisticCompany` / `spareBatch`），**只做留档，不参与计算**
- 触发器：**必须挂 `app.reject_mutation()` 的 append-only 触发器**，与父表一致
- 索引：`(fulfillment_provider_id, sku_id, warehouse_code, synced_at DESC)`（对齐父表 `idx_stock_latest` 的形状）、`(expire_date)`

**「一等列 + JSONB 尾巴」是这里的正解**，而不是二选一：17 个批属性里只有 6-8 个参与业务计算，其余留档即可。

**关于 `remainDays`：不要落库成物化列。** 它是「相对今天」的量，快照落库后立即过期。落 `expire_date` 真值，`remain_days` 由查询时计算（或视图 `expire_date - current_date`）。京东 `remainDays` 只在调用当刻有效，可用于对账校验，不宜作为存储真值。

**关于是否需要第二张表存 `queryShelfLifeGoodsList` 结果：不建议，因为它 join 不上。** `ShelfLifeGoodsStock` **不返回任何批次标识**（无 `lot` / `purchaseNo` / `supplier` / `boxNo`），而且官方文档没有定义它一行的粒度键（U8）。这意味着它与批次子表**无法做可靠关联**——同一商品同一仓如果有两个批次恰好 `expireDate` 相同，两边都无从区分。

因此定位是：**`queryStock`(`stockIndexes=2`) 作为存储主源（有批次身份 + 数量 + 可范围过滤），`queryShelfLifeGoodsList` 作为对账与兜底**（比对 `remainDaysRate` 与我们自算值、以及 `status` 分档），**不单独建表**，结果只留在 `audit_logs` 里备查。

**两个接口谁都不能单独满足需求**，这一点值得票 08 明确记下：

| | 批次身份(lot 等) | 数量 | 到期日期 | 剩余天数/占比 | 临期分档 | 按日期范围过滤 | 分页上限 |
|---|---|---|---|---|---|---|---|
| `queryStock` (`stockIndexes=2`) | ✅ | ✅ | ✅ | ❌ | 请求侧有(U4) | ✅ | 1000 |
| `queryShelfLifeGoodsList` | ❌ | ✅ | ✅ | ✅ | ✅ 6档 | ❌ | 50 |

---

### Q6. 兜底结论：如果京东确实不给效期？

**不适用——京东给效期，而且给得比预期充分。** 票 08 不需要退回「只用主数据估算」这一层。

明确记录三条可用的真值来源，按推荐优先级：

1. **`queryStock` + `stockIndexes=2`** —— 批次级 `productDate` / `expireDate` / `createTime`(入库日期)，且支持按到期日期做范围查询下推。**推荐作为主数据源**，因为它同时给出批次身份（`lot` 等）和数量，能直接支撑木桶短板算法。
2. **`queryShelfLifeGoodsList`**（`/integratedsupplychain/stock/Shelflifeinventory/query/v1`）—— 直接给 `remainDays` / `remainDaysRate` / `shelfLifeDays`。**推荐作为校验与兜底**。
3. **`queryGoodsInfo.GuaranteePeriodResult`** —— 主数据 `shelfLifeDays` 与京东侧临期阈值（`warningDay` / `regularAdventDay` / `urgentAdventDay`）。**作为第二层估算与阈值来源**，即决策 6 原本的一期方案，现在降级为兜底而非主路径。
4. **`addSoOrder.leftExpirationPercent`** —— 下单时把剩余保质期要求下推给京东执行。**不是数据源，是执行手段**，供票 12 考虑。

另外两个审计类来源（不是主数据源，但对账时有用）：`queryBatchChange`（`/integratedsupplychain/stock/batchchange/query/v1`，库房主动发起的批属性变更流水，含变更前后 `ProductDate`/`ExpireDate` 与 `changeNum`）和 `queryInsideOrder`（在库调整单，`bizType=4` 批属性调整，商家侧改效期的路径）。**这两个说明效期在京东侧是可变的**——落库时要按快照语义处理，不能当成一次采集后永久不变的静态属性。

**但「拿得到」不等于「拿得准」。** 上线前必须先解掉 U1（游标分页无处取值）、U2（批次是否全覆盖）、U3（日期格式），这三项都只能靠 UAT 实测，读文档解决不了。在此之前，票 08 可以按批次真值设计模型，但**不应把批次口径直接接入库存约束的硬判定**。

---

## 现状与落地影响（调研外的附加发现，票 08 实施前必读）

调研过程中核查了仓库现状，有三条直接影响票 08 工作量与风险评估的事实。

### 1. 批次/效期的 JD 客户端**已经写好了**，只是没落库

`backend/src/main/java/cn/zimu/fulfillment/connector/jd/stock/JDStockService.java` 已声明三个方法（注释为原文）：

```java
/** 批次异动：按仓库 + 时间段查批次库存异动流水。 */
JdResult queryBatchChange(Map<String, Object> request);
/** 效期商品：按效期盘点单查商品效期信息。 */
JdResult queryShelfLifeGoods(Map<String, Object> request);
/** 效期库存：按仓库 + 商品查批次效期库存明细。 */
JdResult queryShelfLifeInventory(Map<String, Object> request);
```

- 真实实现 `connector/jd/stock/JdStockClient.java:91-121`
- HTTP 端点 `connector/jd/stock/JdStockController.java` —— `GET /api/v1/jd-stock/{batch-changes, shelf-life-goods, shelf-life-inventory}`，代码注释写明「不做任何写操作」
- 前端 `frontend/src/pages/fulfillment/JdStockQueryPage.tsx:180-234` 已有完整字段中文标签（`prechangelot`/`productdate`/`expiredate`/`lotno` 等）
- **UAT 探针也已存在**：`backend/src/test/java/cn/zimu/fulfillment/connector/JdReadOnlyUatProbe.java:207,213` 已在调 `queryShelfLifeGoods` / `queryShelfLifeInventory`

**这些结果全部只回给浏览器展示，没有任何持久化。** 票 08 的工作是「把已有只读通道接进快照存储」，不是从零对接。**U1–U4 的 UAT 验证可以直接复用 `JdReadOnlyUatProbe`**，成本很低。

另外 `JdStockClient.java:108` 有一条注释印证了本文 Q1 记录的 SDK 命名陷阱：「官方 SDK 中「效期商品」请求体复用的是盘点（CheckStock）DTO」。

### 2. 现有库存校验写死了 `stockIndexes="1"`，且**切批次维度会直接触发它的歧义拦截**

`backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdStockCheckService.java:338-353` 的 `stockRequest()`：

```java
request.put("stockIndexes", "1");
```

且 `ShipmentJdStockCheckApiTest.java:434` 断言了 `.containsEntry("stockIndexes", "1")`。

**更关键的是它的响应假设：** `resultRows()`（同文件 483 行）按 `goodsNo` + `warehouseNo` 匹配，**要求恰好一行**——零行报 `JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED`，多行报 `JD_STOCK_RESPONSE_AMBIGUOUS`。而 `stockIndexes=2` **天然一个批次一行**，同一 `(goodsNo, warehouseNo)` 必然多行。

> **结论：不能把现有出库门闩直接切到 `stockIndexes=2`，会把正常的多批次商品判成「响应歧义」而拦单。** 批次采集必须走**新的独立调用路径**，不要动这条已在生产上把关出库的逻辑。这是本次调研发现的最高优先级实施约束。

`parseObservation()`（299 行）还硬过滤了 `goodsLevel=="100" && stockStatus=="1" && stockType=="1"`——注意 `stockType` 在批次维度下是另一套枚举（U4），这个过滤条件在批次场景下语义不同。

### 3. 冷链出库 Excel 模板已经预留了效期列，但从没填过

`backend/src/main/java/cn/zimu/fulfillment/file/ProviderFileService.java:57-58` 的京东冷链出库模板表头已包含 `"包装批号"`、`"采购单号"`、`"生产日期"`、`"到期日期"`、`"生产批号"`——**但全仓没有任何 `cells.put` 往这些列写值**。批次数据一旦落库，这里是一个现成的下游消费点。

---

## 未解决项清单

| # | 问题 | 状态与已查范围 |
|---|---|---|
| **U1** | **批次滚动分页的游标从哪取？** 请求侧 `batchStock.cursor` 官方描述「首次传空，后续每次传**上次查询返回值**」，但**响应结构里根本没有 cursor 字段**。jar 证实 `queryStock.JdlOpenPage` 只有 `totalNum: Integer` + `resultList: List<StockResult>`；`JdlApiPageResponseBase` 只有 `data` / `code` / `message` / `requestId`。文档 JSON 与 HTML 的响应参数表同样没有任何 cursor/nextCursor 字段。**这是硬矛盾，无法从现有材料解决**，必须问京东或 UAT 实测（也可能实际走 `currentPage`/`pageSize` 翻页，cursor 是废弃参数）。 |
| **U2** | **批次量之和 vs 总量；是否存在未分批次余量。** 见 Q4。官方文档零说明，必须 UAT 对账。**食品临期场景下这是最高风险的未知项。** |
| **U3** | **`expireDate` / `productDate` 的真实格式，官方文档自相矛盾三种写法。** `1884` / `1632`(deliveryBatchItem) / `1855` / `1576` / `2092` / `1596` 写 **`格式：yyyy-MM-dd`**；`1612-queryStock` 写 **「（时间戳格式）」但示例值是 `2020-1-1`**（且月日非零填充）；`1854-queryGoodsLevelChange` 写 **`格式: yyyy-MM-dd HH:mm:ss`**。jar 侧统一是 `String`，无从判断。**解析器必须容错**（同时接受 `yyyy-M-d`、`yyyy-MM-dd`、`yyyy-MM-dd HH:mm:ss` 与 epoch 数字串），并在 UAT 固化 `1612` 的真实返回。**注意 `1612` 恰好是我们要用作主数据源的那个接口，也正是唯一说不清格式的那个。** |
| **U4** | **响应侧 `stockType` 在批次维度下回哪套枚举。** 请求侧描述明确批次库存类型是「1正常/2预警/3临期/4过期」，响应侧 `stockType` 描述只列了仓库库存的 1-23 枚举。两套枚举的 1-4 含义完全不同（如 `3` = 商家预留 vs 临期），**误读会直接把正常库存当临期，或把临期货当正常货发出去**。需 UAT 确认。附带影响：`ShipmentJdStockCheckService.parseObservation()` 硬过滤 `stockType=="1"`，在批次语义下含义会变。 |
| **U5** | **未启用保质期管理的商品在批次维度下的行为。** 文档未说明 `isShelfLifeMgmt=1`（否）的商品在 `stockIndexes=2` 下是返回 0 行、还是返回 `expireDate` 为空的行。影响「拿不到效期」与「该商品本就无效期概念」两种情况的区分——这两者在业务上必须区别对待，不能都当成「未知」。 |
| **U6** | **`queryShelfLifeGoodsList` 的 `remainDaysRate` 公式、`lockDate` 语义、`status` 阈值来源。** 文档对 `remainDaysRate` 只写「剩余天数占比」，**未给分母定义**；`lockDate` 只写「锁定日期」，未说明触发条件与锁定对象；`status` 的 6 档与商品主数据 `adventDay`/`regularAdventDay`/`urgentAdventDay` 的对应关系**文档从未明说**（合理推断，但未证实）。另：`status` 在请求侧是 `Integer`、响应侧是 `String`，类型不一致。 |
| **U7** | **批次维度的调用配额与性能。** `1612` 批次维度 `pageSize` 上限 1000，但 `1884` 上限只有 **50** 且 `warehouseNo` 必填无全仓模式；批次行数远多于商品行数，全量快照的调用次数与耗时未知。需按「仓数 × 商品数 × 批次数」估算后再定采集频率。 |
| **U8** | **`ShelfLifeGoodsStock` 一行的粒度键是什么。** 文档未定义唯一键，也未说明同一 `(goodsNo, warehouseNo, expireDate)` 是否可能出现多行。由于该对象**不含任何批次标识**，这直接导致它无法与批次表可靠关联（见 Q5）。 |
| **U9** | **`GuaranteePeriodResult.allowedDay` 的语义。** jar 中存在该字段，但 `1610-queryGoodsInfo` 的响应参数表中未列出，无中文描述。 |
| **U10** | **`expireDate` 是京东存的真值还是由生产日期 + 保质期推导的派生值。** `1610` 的 `batchAttribute` 12 项批属性里有「2生产日期」但**没有「到期日期」**，暗示到期日期是派生的；但文档未明说，也未说明推导规则（是否含 `safeOffsetDay` 偏移）。**若为派生值，则主数据 `shelfLifeDays` 配错会直接污染 `expireDate` 真值**，「真值优先」的前提就不成立。这一条对决策 6 的两层方案影响较大，建议 UAT 时用同一批次交叉验算 `productDate + shelfLifeDays ?= expireDate`。 |
| **U11** | **`batchInfos[].lotAttr` 可接受的 key 字符串全集。** 官方只给示例 `supplier`，未列出枚举。最可能来自 `batchAttribute` 的 12 项属性名或其英文字段名，**但未证实**。这是使用「按到期日期范围过滤」这一能力的直接阻塞项。 |
| **U12** | **无推送/订阅机制。** 62 个接口中未找到任何效期或批次变更的推送/回调 API，全部是拉取式。这意味着临期状态的时效性取决于我们的轮询频率，票 08「用多旧的快照算才算数」这一问必须自己定，京东侧没有实时通知可用。 |

> **U1–U4 + U10 建议合并成一次 UAT 联调验证，在票 08 动工前完成**，且可直接复用已有的 `JdReadOnlyUatProbe`（见「现状与落地影响」）。U5–U9、U11–U12 可在实施中解决。

### 跨接口一致性陷阱（实现时必看）

1. **同一概念两套字段名。** 多数接口用 `expireDate` / `productDate`，但 **`1576-queryCheckStock`（盘盈亏）与 `2092-queryProcessOrder`（加工单）用 `expirationDate` / `productionDate`**。共用一个解析器会在这两个接口上静默丢掉效期数据。jar 侧同样如此（`queryCheckStock.BatchInfos.expirationDate`、`JdlEdiBatAttrVO.expirationDate`）。
2. **`produceDate` 这个拼写在 62 份文档里 0 命中**，在 jar 里也不存在。票面与既有笔记里若沿用了这个拼写，是错的。
3. **同一个 `expireDate` 字段的中文标签随接口变化**：`到期日期`（1596/1612/1632/1884）、`过期日期`（1559/1571/1572/1574/1575/1628/1629/1854/1899/1900/2758）、`结束日期`（1855）。字段名相同，不要被中文标签误导成不同概念。
4. **`2719-queryWarehouseStockSnapshot` 与 `2775-queryWarehouseStockMergeByWarehouse` 完全没有批次与效期维度**（两者甚至都没有 `warehouseNo` 字段）。**不要在这两个接口上建任何效期逻辑。** 特别注意 `FulfillmentStockDecisionService` 目前用的正是 `queryWarehouseStockSnapshot`，它拿不到效期。`2719` 里唯一出现「过期」二字的是错误码 `2006 游标过期`（游标 50 秒有效），与商品效期无关。

---

## 附录：关键类的字段签名原文

以下为 `javap -p -classpath backend/libs/IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar <FQCN>` 输出原文（省略构造器与 getter/setter）。包前缀统一为 `com.lop.open.api.sdk.domain.IntegratedSupplyChain.`。

### `JdlOpenPlatformStockService.queryStock.BatchInfos`（响应侧批属性）

```
private java.lang.String purchaseNo;
private java.lang.String productDate;
private java.lang.String expireDate;
private java.lang.String spareBatch;
private java.lang.String supplier;
private java.lang.String receiptDate;
private java.lang.String plu;
private java.lang.String logisticCompany;
private java.lang.String origin;
private java.lang.String lot;
private java.lang.String manufacturer;
private java.lang.String packageLot;
private java.lang.String boxNo;
private java.lang.String noSale;
private java.lang.String createTime;
private java.lang.String store;
private java.lang.String erpPurchaseNo;
```

### `JdlOpenPlatformStockService.queryStock.StockResult`

```
private java.lang.String ownerNo;
private java.lang.String ownerName;
private java.lang.String shopNo;
private java.lang.String sellerNo;
private java.lang.String sellerName;
private java.lang.String warehouseNo;
private java.lang.String warehouseName;
private java.lang.String goodsNo;
private java.lang.String erpGoodsNo;
private java.lang.String goodsName;
private java.lang.String erpGoodsSign;
private java.lang.String barCodes;
private java.lang.String goodsLevel;
private java.lang.String stockStatus;
private java.lang.String stockType;
private java.lang.String stockNum;
private java.lang.String usableNum;
private ...queryStock.BatchInfos batchInfos;          // 单个对象，非 List
private ...queryStock.LogicalFactors logicalFactors;
```

### `JdlOpenPlatformStockService.queryStock.StockQueryRequest`（请求侧）

```
private java.lang.String ownerNo;
private java.lang.String warehouseNo;
private java.lang.String stockIndexes;
private java.lang.String goodsNo;
private java.lang.String erpGoodsNo;
private java.lang.String goodsLevel;
private java.lang.String stockType;
private java.lang.String pin;
private java.lang.String returnZeroStock;
private ...queryStock.ShopStock shopStock;
private ...queryStock.LogicalStock logicalStock;
private ...queryStock.WarehouseStock warehouseStock;
private ...queryStock.BatchStock batchStock;
private java.util.List<...queryStock.BatchInfo> batchInfos;   // 请求侧是 List，作过滤器
private java.lang.String currentPage;                          // 注意 String
private java.lang.String pageSize;                             // 注意 String
```

### `JdlOpenPlatformStockService.queryStock.BatchStock` / `BatchInfo`（请求侧过滤器）

```
// BatchStock
private java.lang.String cursor;
private java.lang.String startTime;
private java.lang.String endTime;

// BatchInfo
private java.lang.String lotAttr;
private java.lang.String lotValue;
private java.lang.Integer operator;
```

### `JdlOpenPlatformStockService.queryStock.JdlOpenPage` / `JdlApiPageResponseBase`（U1 证据）

```
// JdlOpenPage<T>  —— 无 cursor 字段
private java.lang.Integer totalNum;
private java.util.List<...queryStock.StockResult> resultList;

// JdlApiPageResponseBase<T>
private ...queryStock.JdlOpenPage<...queryStock.StockResult> data;
private java.lang.String code;
private java.lang.String message;
private java.lang.String requestId;
```

### `JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStock`（剩余保质期真值）

```
private java.lang.String ownerNo;
private java.lang.String ownerName;
private java.lang.String warehouseNo;
private java.lang.String warehouseName;
private java.lang.String goodsNo;
private java.lang.String erpGoodsNo;
private java.lang.String goodsName;
private java.lang.String goodsLevel;
private java.lang.Integer totalNum;
private java.lang.Integer usableNum;
private java.lang.Integer shelfLifeDays;
private java.lang.String productDate;
private java.lang.String expireDate;
private java.lang.String lockDate;
private java.lang.Integer remainDays;
private java.lang.Double remainDaysRate;
private java.lang.String status;
private java.lang.String createTime;
private java.lang.String eclpWarehouseNo;
```

### `JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStockQueryRequest`

```
private java.lang.String ownerNo;
private java.lang.String warehouseNo;
private java.lang.String goodsNo;
private java.lang.String erpGoodsNo;
private java.lang.String goodsLevel;
private java.lang.Integer status;
private java.lang.Integer currentPage;
private java.lang.Integer pageSize;
private java.lang.String pin;
```

### `JdlOpenPlatformGoodsService.queryGoodsInfo.GuaranteePeriodResult`（主数据保质期与临期阈值）

```
private java.lang.Integer isShelfLifeMgmt;
private java.lang.Integer shelfLifeDays;
private java.lang.Float instoreThreshold;
private java.lang.Float outstoreThreshold;
private java.lang.Integer warningDay;
private java.lang.Integer allowedDay;
private java.lang.Integer regularAdventDay;
private java.lang.Integer urgentAdventDay;
private java.lang.Integer adventDay;
private java.lang.Byte safeDimension;
private java.lang.Integer safeOffsetDay;
private java.lang.Integer shelfLifeMonths;
```

### `JdlOpenPlatformSoService.querySoOrder.SoQueryRequest`（9 个 Flag 均为 Integer）

```
private java.lang.String deliveryNo;
private java.lang.String salesPlatformDeliveryNo;
private java.lang.String erpDeliveryNo;
private java.lang.String ownerNo;
private java.lang.Integer deliveryItemFlag;
private java.lang.Integer deliveryPackageFlag;
private java.lang.Integer deliveryStatusFlag;
private java.lang.Integer deliveryBatchItemFlag;
private java.lang.Integer deliveryProductInfoFlag;
private java.lang.Integer deliveryRejectItemFlag;
private java.lang.Integer deliveryRejectPictureUrlFlag;
private java.lang.Integer deliveryBoxFlag;
private java.lang.Integer deliverySerialNoFlag;
private java.lang.String pin;
private java.lang.Integer addressType;
```

### `JdlOpenPlatformSoService.querySoOrder.DeliveryBatchItem` / `DeliveryItem` / `BatchInfosQueryResult`

```
// DeliveryBatchItem —— 一行 = 一个(订单行 × 批次)，带出库数量
private java.lang.Integer batchQuantity;
private java.lang.String goodsNo;
private java.lang.String erpGoodsNo;
private java.lang.String orderLine;
private ...querySoOrder.BatchInfosQueryResult batchInfos;
private java.lang.String actualWeight;

// DeliveryItem —— 一行 = 一个订单行，只能挂一个批次
private java.lang.String goodsNo;
private java.lang.Double price;
private java.lang.Integer planQuantity;
private java.lang.Integer realQuantity;
private java.lang.String erpGoodsNo;
private java.lang.String orderLine;
private ...querySoOrder.BatchInfosQueryResult batchInfos;
private java.lang.String actualWeight;

// BatchInfosQueryResult —— 14 字段（官方文档在 deliveryBatchItem 节点下漏了 packageLot）
private java.lang.String productDate;
private java.lang.String expireDate;
private java.lang.String packageLot;
private java.lang.String purchaseNo;
private java.lang.String lot;
private java.lang.String supplier;
private java.lang.String receiptDate;
private java.lang.String plu;
private java.lang.String logisticCompany;
private java.lang.String origin;
private java.lang.String manufacturer;
private java.lang.String boxNo;
private java.lang.String noSale;
private java.lang.String store;
```

### 全 jar 效期字段普查

对 jar 内全部 `domain/**/*.class` 逐个 `javap -p` 并筛选 `expireDate|productDate|produceDate|shelfLife|expirationDate|validDate|remainDay`，命中如下（说明效期在京东 ISC 的入库/出库/内配/退供/退货/加工全链路都有一致表达）：

```
JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStock : shelfLifeDays, productDate, expireDate, remainDays, remainDaysRate
JdlOpenPlatformStockService.queryStock.BatchInfos                       : productDate, expireDate
JdlOpenPlatformStockService.queryGoodsLevelChange.BatchAttrLevel        : productDate, expireDate
JdlOpenPlatformStockService.queryCheckStock.BatchInfos                  : expirationDate      (注意字段名不同)
JdlOpenPlatformGoodsService.queryGoodsInfo.GuaranteePeriodResult        : shelfLifeDays, shelfLifeMonths
JdlOpenPlatformGoodsService.saveGoodsInfo.GuaranteePeriod               : shelfLifeDays, shelfLifeMonths
JdlOpenPlatformSoService.querySoOrder.BatchInfosQueryResult             : productDate, expireDate
JdlOpenPlatformSoService.addSoOrder.BatchInfos                          : productDate, expireDate
JdlOpenPlatformPoService.addPoOrder.BatchInfos                          : productDate, expireDate
JdlOpenPlatformPoService.queryPoOrderDetail.BatchInfos                  : productDate, expireDate
JdlOpenPlatformPoService.queryPoOrderDetail.PoItemDiffResult            : expirationDateErrorQuantity
JdlOpenPlatformRtwService.{addRtwOrder,queryRtwOrderDetail,queryRtwOrderList}.BatchInfos : productDate, expireDate
JdlOpenPlatformRtsService.{addRtsOrder,queryReturnToSupplier}.BatchInfos : productDate, expireDate
JdlOpenPlatformUlService.{addUlOrder,ulQuery}.BatchInfos                : productDate, expireDate
JdlOpenPlatformInsideService.{transportInsideOrder,queryInsideOrder}.BatchInfos : productDate, expireDate
JdlOpenPlatformProcessService.queryProcessOrder.JdlEdiBatAttrVO         : expirationDate      (注意字段名不同)
```

**注意 `queryCheckStock.BatchInfos` 与 `JdlEdiBatAttrVO` 用的是 `expirationDate` 而非 `expireDate`** —— 跨接口复用解析器时会踩坑。

### 相关接口的 LOP 路径（`javap -c` 提取的字符串常量，与 `manifest.json` 交叉核对一致）

| 领域方法 | LOP 路径 | doc id |
|---|---|---|
| `queryStock` 库存查询 | `/integratedsupplychain/stock/query/v1` | 1612 |
| `queryShelfLifeGoodsList` 保质期商品库存查询 | `/integratedsupplychain/stock/Shelflifeinventory/query/v1` | 1884 |
| `queryCheckStock` 盘盈亏查询 | `/integratedsupplychain/stock/shelflifegoods/query/v1` | 1576 |
| `queryBatchChange` 批属性变更信息 | `/integratedsupplychain/stock/batchchange/query/v1` | 1855 |
| `querySoOrder` 销售出库单查询 | `/integratedsupplychain/order/delivery/query/v1` | 1632 |
| `queryGoodsInfo` 商品信息查询 | `/integratedsupplychain/basicinfo/goods/query/v1` | 1610 |

`domain` 参数统一为 `IntegratedSupplyChain`。
