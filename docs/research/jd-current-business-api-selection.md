# 当前业务所需京东 ISC API 清单

> 日期：2026-08-11  
> 一手来源：京东物流开放平台「仓配一体」业务单元 `367`的62份官方 API 文档，以及本地 SDK jar 字节码。  
> 业务依据：`docs/api-contract.md`、`docs/excel-closed-loop-spec.md`、`wayfinder/tickets/p0-excel-closed-loop.md`。

## 1. 结论

当前真实京东 Connector 不需要接入62个 API。只需收敛为：

- **4 个运行时核心 API**：库存查询、创建销售出库单、查询销售出库单、取消单据。
- **2 个上线/主数据校验 API**：仓库信息查询、商品信息查询。
- **1 个后续物流轨迹 API**：全程跟踪查询。P0 只闭环到已发货+运单号，不等待签收，所以它不是 P0 必需。

当前最紧急的 Excel 闭环仍可不依赖 SDK 运行；本文清单用于紧接着的真实京东 Client。

## 2. 接口选型

| 优先级 | 文档 ID | API code | 路径 | 当前用途 |
|---|---:|---|---|---|
| 上线校验 | 1602 | `queryWarehouseInfo` | `/integratedsupplychain/basicinfo/warehouse/query/v1` | 确认 `ownerNo/pin/warehouseNo` 归属及仓库启用状态 |
| 上线校验 | 1610 | `queryGoodsInfo` | `/integratedsupplychain/basicinfo/goods/query/v1` | 校验 EMG/商家 SKU 映射、名称、单位和启用状态 |
| **核心** | 1612 | `queryStock` | `/integratedsupplychain/stock/query/v1` | 按仓库和 EMG 查可销良品库存，写入库存快照 |
| **核心** | 1596 | `addSoOrder` | `/integratedsupplychain/order/delivery/create/v1` | 幂等创建销售出库单 |
| **核心** | 1632 | `querySoOrder` | `/integratedsupplychain/order/delivery/query/v1` | 轮询状态、实际出库量、承运商、运单号、拆单子单 |
| **核心** | 1552 | `cancelOrder` | `/integratedsupplychain/order/cancel/v1` | 取消未完成的销售出库单 |
| 后续 | 1941 | `commonQueryOrderTrace` | `/integratedsupplychain/order/trace/query/v2` | 取得已有运单的全程物流轨迹 |

对应官方文档均已下载到 [`docs/research/jdl-api-367`](jdl-api-367/README.md)，结构化字段见其 `json/<id>-<apiCode>.json`。

## 3. 各 API 的当前最小用法

### 3.1 `queryWarehouseInfo` — 配置校验，不按订单调用

必填：

- `ownerNo`：事业部/货主编码（`EBU...`）；
- `pin`：京东 PIN。

建议用已维护的 `warehouseNo` 缩小范围，校验响应中：

- `warehouseNo`、`erpWarehouseNo`、`warehouseName`；
- `status=2` 才是启用仓库。

证据：[`1602-queryWarehouseInfo.json`](jdl-api-367/json/1602-queryWarehouseInfo.json)。

### 3.2 `queryGoodsInfo` — SKU 映射校验

必填：`ownerNo`、`queryType`、`pin`、`pageSize: Integer`、`currentPage: Integer`。

当前业务应以 **`queryType=1`** 查指定商品，用 `goodsNo` (EMG) 或 `erpGoodsNo` 校验已有显式映射。

`queryType` 取值枚举（官方 remark：「查询类型，枚举：1-查询全部信息；2-查询商品编号，长度：1-4字符」，官方请求示例亦为 `"queryType":"1"`）：

| 取值 | 含义 | 用法 |
| --- | --- | --- |
| `"1"` | 查询全部信息 | **本业务必须用这个**：只有它会回填完整 `basicInfo` |
| `"2"` | 只查商品编号 | 京东按字面执行，`basicInfo` 除 `goodsNo`/`barcode` 外全为 null |

> 曾把 `queryType=2` 误读为「按商品编号查」而写死在 `JdGoodsReadOnlyVerifier`，导致 `enableFlag` 恒为 null、
> 出库单被判 `GOODS_STATUS_MISSING` 拦停。实测（审计日志 `app.audit_logs` id=165 传 `queryType="2"` vs
> id=173~178 不传）已确认差异。

主要使用响应：

- `goodsNo`、`erpGoodsNo`、`goodsName`、`goodsUnit`；
- `enableFlag`：**`1` = 未启用，`2` = 启用**（官方 remark：「启用标志，1：未启用，2：启用，长度：1-4字符」，
  见 [`1610-queryGoodsInfo.json`](jdl-api-367/json/1610-queryGoodsInfo.json) outParams 中 `enableFlag` 节点）。
  这是京东 ISC 的 `1 否 / 2 是` 通用惯例，同文档的 `storeSaleFlag`（「是否可门店销售，1 否 2是」）与
  `afterSaleFlag`（「门店是否支持售后，1 否 2是」）可交叉印证。**官方未定义 `0`**；代码对未文档化取值
  只告警不阻断（fail-open），真正的硬关卡是 `queryStock` 库存校验。
- 需要时使用 `minPackageQuantity/minSaleQuantity`做人工校验，但不能用它们覆盖系统已确认的来源数量乘数。

证据：[`1610-queryGoodsInfo.json`](jdl-api-367/json/1610-queryGoodsInfo.json)。

### 3.3 `queryStock` — 履约前库存快照

当前业务查京东仓库的可销良品库存，建议请求语义：

- `ownerNo`、`pin`；
- `warehouseNo`；
- `stockIndexes="1"`（仓库库存）；
- `goodsNo`：最多100个 EMG，英文逗号分隔；
- `goodsLevel="100"`（良品）；
- `stockType="1"`（可销）；
- `warehouseStock.stockStatus="1"`；
- `currentPage/pageSize` 在该 API 中是 **String**，不是 `queryGoodsInfo` 的 Integer。

响应核心为 `resultList[].goodsNo/warehouseNo/stockNum/stockStatus/stockType`。`stockNum` 是 String，进入领域层时必须严格解析为 `BigDecimal`，不用浮点数。

官方文档对“返回零库存”同时出现根字段 `0/1` 和 `warehouseStock.returnZeroStock=1/2` 两套口径，不得在代码里猜；需用预发环境的有库存/零库存 SKU 做实测。

证据：[`1612-queryStock.json`](jdl-api-367/json/1612-queryStock.json)。

### 3.4 `addSoOrder` — 创建销售出库单

当前必需请求字段：

- `sourceNo`；
- `erpDeliveryNo`：用系统持久化的 `outbound_order_no`，事业部下唯一，同时作为幂等键；
- `orderType=1`（B2C）；
- `warehouseNo`；
- `orderMark`：非 COD 不得把首位设为1；
- `pin`；
- `channelInfo.salesPlatformSource=6`（其他），可同时传 `salesPlatformDeliveryNo/createTime`；
- `customerInfo.ownerNo/shopNo`，B2C 时 `shopNo` 必填；
- `receiverInfo.name`、`mobile/phone` 至少一个、`detailAddress`；
- `carrierInfo.carrierNo`：当前京东快递；
- `cargoInfos[]`：`goodsNo` (EMG)、`planQuantity: Integer > 0`、`orderLine`，建议明确 `goodsLevel=100`；
- 非 COD 金额不虚构，与当前 Excel 契约一致时可明确传0，不得传伪造金额。

成功响应必须保存 `deliveryNo` 与 `erpDeliveryNo` 的映射。

#### 当前阻断性配置冲突

- 该 API 官方页面把 `sourceNo` 写为固定 `ISV0020008045424`；
- 当前京东 Excel 模板的 `*ISV来源编号` 是 `ISV0020000000079`。

两者不能默认是同一配置，也不能直接选一个硬编码。预发创建单前必须让京东对接人确认本应用的 API `sourceNo`。

证据：[`1596-addSoOrder.json`](jdl-api-367/json/1596-addSoOrder.json)。

### 3.5 `querySoOrder` — 当前发货事实的权威来源

建议按 `erpDeliveryNo + ownerNo + pin` 查询，不依赖进程内存里的京东单号。显式设置：

- `deliveryItemFlag=1`：取得每行 `realQuantity`；
- `deliveryPackageFlag=1`：取得包裹与包裹商品明细；
- `deliveryStatusFlag=1`：取得状态流水。

当前需要消费：

- `status`、`deliveryStatusList[].statusCode/statusName/operateTime`；
- `carrierInfo.carrierNo/carrierName/waybillNo`；
- `deliveryItemList[].goodsNo/orderLine/planQuantity/realQuantity`；
- `isSplit`、`splitDeliveryNos`；
- `deliveryNo`、`erpDeliveryNo`；
- `deliveryPackageList[]`。

`querySoOrder` 已能提供当前所需的承运商、运单号、实际出库量和拆单信息，因此 **不需要为 P0 再引入 `queryPackage`**。

京东拆单时，必须把 `splitDeliveryNos` 拆成子单逐一查询，每个子单建立真实 Shipment/Tracking；不得拼接运单或覆盖首批运单。

证据：[`1632-querySoOrder.json`](jdl-api-367/json/1632-querySoOrder.json)。

### 3.6 `cancelOrder` — 取消不是同步布尔值

销售出库的固定语义：

- `orderType="XSCK"`；
- 优先用 `orderNo=deliveryNo`；
- 没有京东单号时，使用 `ownerNo + erpOrderNo`；
- `pin`。

响应 `resultType`：`0=取消成功`、`1=取消中`、`2=取消失败`。`1` 必须后续查询，不能当成已取消。

证据：[`1552-cancelOrder.json`](jdl-api-367/json/1552-cancelOrder.json)。

### 3.7 `commonQueryOrderTrace` — 后续轨迹，不是运单号获取接口

这个 API 的输入是已有 `waybillNo`、商家单号或京东仓单号；输出是 `orderTraceList[]`的操作时间、节点、地址和说明。它不用于首次取得运单号，不应堵住 P0 的 `COMPLETED`。

证据：[`1941-commonQueryOrderTrace.json`](jdl-api-367/json/1941-commonQueryOrderTrace.json)。

## 4. 当前正确调用流程

```text
上线/配置校验
  queryWarehouseInfo
  queryGoodsInfo

每批京东履约
  queryStock
    ├─ 可发数量 > 0 → addSoOrder
    │                   → 持久化 erpDeliveryNo ↔ deliveryNo
    │                   → querySoOrder 轮询
    │                       ├─ 拆单 → 逐个查 splitDeliveryNos
    │                       └─ 真实出库+实发量+运单 → Shipment/Tracking
    └─ 不足 → 按现有规则部分履约/采购工单

取消需求
  cancelOrder
    ├─ resultType=0 → 取消成功
    ├─ resultType=1 → 继续 querySoOrder
    └─ resultType=2 → ReviewCase / 人工介入

未来签收轨迹
  commonQueryOrderTrace
```

## 5. 状态映射的安全下限

官方状态表已归档为 [`54597-order-status.md`](jdl-api-367/access-guides/54597-order-status.md)。

- `100130=预分拣-获取运单`：可能已有运单号，但还不是真实已发货；不得仅因为 `waybillNo` 存在就创建已发 Shipment。
- `10020=包裹出库`：按状态名称与当前业务语义推断，它是进入 SHIPPED 判定的第一个强候选节点；仍要求 `realQuantity` 与有效运单，并在预发状态流水中确认。
- `10027/10028/10029`：取消中/取消成功/取消失败。
- `10034=妥投`：属于未来 DELIVERED，不是 P0 完成前置条件。
- `10035=拒收`、`10031=订单拉回`：进异常/人工复核，不自动猜测终局。
- `10054=分拣中心发货` 与其它特殊链路状态需预发实测后再纳入状态映射。

## 6. 通用 Client 约束

- SDK 构造参数已由 jar 局部变量表确认为 `JdlClient(serverUrl, appKey, appSecret, accessToken)`。
- 每次调用同时校验 LOP 外层响应和领域响应；领域成功码为 `1000`。
- 必须记录 `requestId`，审计中的请求/响应要脱敏，不记录 App Secret、Access Token 或 Refresh Token。
- `addSoOrder` 不得因超时就生成新 `erpDeliveryNo` 重试；应先按原 `erpDeliveryNo` 查单，避免重复出库。
- `querySoOrder` 的响应包含敏感收件信息，日志不能直接全量落原始对象。
- `commonQueryOrderTrace` 的 SDK 包装响应使用 `getResult()`，其余当前接口使用 `getResponse()`。

## 7. 暂不接入的 API

- `queryPackage`：当前 `querySoOrder` 已返回包裹、承运商、运单和实发量，引入它是重复集成。
- 入库、退货入库、退供出库、报废、库内调整、组套加工：不在当前业务边界内。
- 商品/店铺/客户/供应商新增修改：当前只读校验已有京东主数据，不由本系统写京东主数据。
- 店铺库存、逻辑库存、批次库存：P0 只按已指定仓库查实际可销良品库存。
- 同城轨迹 `queryCityTrack`：当前使用通用全程跟踪，只在证明业务走同城专用产品后再增加。

## 8. 预发环境最小验证顺序

1. 只读 `queryWarehouseInfo`：确认鉴权、PIN、事业部和仓库归属。
2. 只读 `queryGoodsInfo`：选一个已知 EMG 校验映射。
3. 只读 `queryStock`：用一个有库存和一个零库存 SKU 锁定 `returnZeroStock` 口径。
4. 由京东对接人确认 API `sourceNo`，再创建一张明确的预发测试出库单。
5. 重放同一 `erpDeliveryNo`，验证本系统幂等处理，不要期待京东代替本系统做幂等。
6. 轮询 `querySoOrder`，验证状态、实发量、运单、拆单和包裹的真实返回形态。
7. 对一张可取消测试单调用 `cancelOrder`，覆盖 `resultType=0/1/2` 的处理。

在第4步之前，所有调用都是无副作用的查询；不应直接用线上环境创建单。

## 9. 伴随官方文档

- [完整仓配一体 API 快照](jdl-api-367/README.md)
- [销售出库单状态枚举](jdl-api-367/access-guides/54597-order-status.md)
- [销售平台枚举](jdl-api-367/access-guides/54604-sales-platform.md)
- [既有 SDK 接口面字节码调查](jd-isc-api.md)

## 10. 确定性复核

2026-08-11 对本文所选7个 API 做了一次独立的机械复核：

- 每个文档 ID + API code + path 在官方 `catalog.json`、本地 `manifest.json` 和对应详情 JSON 中都恰好命中1次。
- 7个 API 在 `IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar` 中都有对应 `*LopRequest` 类，其字节码 `getApiMethod()` 与官方 path 7/7 一致。
- `querySoOrder` 详情中已确认存在 `status`、`carrierNo/name`、`waybillNo`、`realQuantity`、`isSplit`、`splitDeliveryNos` 及三个明细 flag。
- `queryPackage` 的官方请求/响应字段树中运单字段数为0，不能替代 `querySoOrder` 获取 Tracking。
- PRD 的 `queryStock/createOutboundOrder/queryOutboundOrder/cancelOutboundOrder/queryTracking` 5项能力均已被选定 API 覆盖；仓库/商品两项是为上线前主数据校验增加，未混入每单核心链路。
- 官方状态快照确认同时存在 `100130=预分拣-获取运单` 和 `10020=包裹出库`，所以“有运单号≠已发货”的结论有官方状态证据。

复核结论：接口选型与当前业务范围一致；剩余不确定性不在“选哪个 API”，而在预发返回行为：API `sourceNo`、零库存参数口径、以及特殊出库状态的映射。
