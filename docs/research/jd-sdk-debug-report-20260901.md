# 京东 SDK 防腐层全接口对照调试报告（2026-09-01）

## 0. 范围与方法

| 项 | 内容 |
| --- | --- |
| 权威契约 | `docs/research/jd-warehouse-openapi-docs/`（open.jdl.com 仓配一体 unitId=367，62 个 API 快照，抓取于 2026-08-11） |
| 被测防腐层 | `backend/src/main/java/cn/zimu/fulfillment/connector/jd/`（7 个 REAL 客户端 + 7 个 Mock + 6 个 Controller，5156 行） |
| 只读实调 | 生产容器 `zimu-fulfillment-backend-1` 内 `python3` 调本机 REST 只读作业面；每接口 1–2 次，无轮询 |
| 生产态 | `JD_LOP_CLIENT_MODE=REAL`、`JD_LOP_SERVER_URL=https://api.jdl.com`、**`JD_LOP_WRITE_MODE=ON`**、`OWNER_NO=EBU4418056064528` |
| 写接口 | **一次未真实调用**。仅做「请求构造 vs 文档参数字典」静态对照 + MOCK 行为核验 |
| 取证实体 | 出库单 `202609010004`（`ESL00000025552990157` / 运单 `JDVA46880666742`）、商品 `EMG4418727173231`、仓 `118085840`、店铺 `ESP0020008943717` |

映射方法：京东 LOP 请求类名是 `apiUrl` 的确定性变换（`/integratedsupplychain/order/cancel/v1` → `IntegratedsupplychainOrderCancelV1LopRequest`），因此 62 份文档与代码中的 LOP 类可机械对齐，非人工猜测。

## 1. 结论摘要

- **覆盖度**：62 个文档 API 中 **57 个已包装**（59 个包装方法，含 2 个重复包装）；**5 个未包装**；**0 个「SDK 有而文档没有」的孤儿包装** — 防腐层没有对着不存在的契约编程。
- **只读实调**：33 条只读路由实调 **28 条拿到 `1000` 成功业务码**，2 条因缺少我方不掌握的实体（青龙业主号 / 无退供单）标记 SKIPPED，**3 条暴露真实缺陷**。
- **未发现「防腐层吞响应字段」**：`JdResult.data` 原样透传 JD 的 `data` 结构，`querySoOrder` 实调返回的字段与文档 `outParams` 一致，无裁剪。（审计投影会裁剪，但那是刻意的 PII 收敛，不影响业务读取。）
- **最高危的一类不是「字段填错」，而是「没有任何字段校验」**：读写两侧的入参在到达京东之前都不做必填/枚举/格式校验，`#213`（`orderType` 传数字被 3008 拒绝）不是孤例而是这个结构的必然产物。MOCK 模式对任意入参无条件返回成功，因此测试永远无法拦住这一类。

- **另有一类只在业务侧才看得见的缺陷**：防腐层是透传的，所以「发错值」的根在构造命令的业务代码里。第 4.1 节列出 11 条，其中 2 条 HIGH。最刺眼的是 `queryWarehouseStockSnapshot` 的响应解析读了三个**文档和 SDK 里都不存在**的字段，而 Mock 恰好伪造了这个不存在的形状——REAL 下必然失败，测试却永远绿。

防腐层缺陷：高危 4 条（D1–D4），中危 6 条（D5–D10），低危 3 条（D11–D13）。
业务侧构造缺陷：高危 2 条（B1–B2），中危 8 条（B3–B10），低危 1 条（B11）。

## 2. 对照总表（62 文档 API × 59 包装 × REST 路由）

| # | 类别 | apiCode | 方向 | 防腐层包装 (文件:行) | REST 作业面路由 |
|---|---|---|---|---|---|
| 1559 | 入库 | `addPoOrder` | 写 | `JdWriteOpsClient.orderPurchaseCreate`:242 | POST /api/v1/jd-write/order/purchase-create |
| 1571 | 入库 | `queryPoOrderDetail` | 读 | `JdOrderClient.queryPurchase`:112 | GET /api/v1/jd-order/purchase-orders |
| 1572 | 入库 | `addRtwOrder` | 写 | `JdWriteOpsClient.orderReturntowarehouseCreate`:275 | POST /api/v1/jd-write/order/returntowarehouse-create |
| 1573 | 入库 | `queryRtwOrderDetail` | 读 | `JdReturnClient.queryRtwOrderDetail`:73 | GET /api/v1/jd-return/rtw-orders/{no} |
| 1581 | 入库 | `queryOrderNosByPage` | 读 | `JdOrderClient.queryOrderNosByPage`:80 | GET /api/v1/jd-order/outbound-order-nos |
| 1607 | 入库 | `closePoOrder` | 写 | `JdWriteOpsClient.orderPurchaseClose`:253 | POST /api/v1/jd-write/order/purchase-close |
| 1883 | 入库 | `getEclpNoByOutNo` | 读 | `JdOrderClient.queryOperateRelation`:128 | GET /api/v1/jd-order/operate-relations |
| 2758 | 入库 | `queryRtwOrderList` | 读 | `JdReturnClient.queryRtwOrderList`:64 | GET /api/v1/jd-return/rtw-orders |
| 1941 | 全程跟踪 | `commonQueryOrderTrace` | 读 | `JdWarehouseClient.queryTracking`:150 | GET /api/v1/jd-warehouse/tracking |
| 2211 | 全程跟踪 | `queryCityTrack` | 读 | `JdOrderClient.queryCityTrack`:144 | GET /api/v1/jd-order/city-tracks |
| 1574 | 出库 | `addRtsOrder` | 写 | `JdWriteOpsClient.orderReturntosupplierCreate`:264 | POST /api/v1/jd-write/order/returntosupplier-create |
| 1575 | 出库 | `queryReturnToSupplier` | 读 | `JdReturnClient.queryReturnToSupplier`:82 | GET /api/v1/jd-return/return-to-suppliers/{no} |
| 1596 | 出库 | `addSoOrder` | 写 | `JdWarehouseClient.createOutboundOrder`:117 + `JdWriteOpsClient.orderSoCreate`:286 | (无路由，无调用方) + POST /api/v1/jd-write/order/so-create |
| 1632 | 出库 | `querySoOrder` | 读 | `JdWarehouseClient.queryOutboundOrder`:128 | GET /api/v1/jd-warehouse/outbound-orders/{erp_delivery_no} |
| 1813 | 出库 | `updateDeliveryCommand` | 写 | `JdWriteOpsClient.orderOperateCommandModify`:220 | POST /api/v1/jd-write/order/operate-command-modify |
| 1817 | 出库 | `queryDeliveryTime` | 读 | `JdOrderClient.queryDeliveryTime`:136 | GET /api/v1/jd-order/delivery-times |
| 1856 | 出库 | `searchShopStockFlow` | 读 | `JdStockClient.searchShopStockFlow`:125 | GET /api/v1/jd-stock/shop-stock-flow |
| 1899 | 出库 | `addUlOrder` | 写 | `JdWriteOpsClient.orderDestroyCreate`:209 | POST /api/v1/jd-write/order/destroy-create |
| 1900 | 出库 | `ulQuery` | 读 | `JdOrderClient.queryDestroy`:96 | GET /api/v1/jd-order/destroy-orders |
| 1909 | 出库 | `queryExceptionOrderList` | 读 | `JdOrderClient.queryException`:104 | GET /api/v1/jd-order/exceptions |
| 1910 | 出库 | `queryPageSerialByOwnerNoAndCondition` | 读 | `JdSerialClient.querySerialByCondition`:81 | GET /api/v1/jd-serial/condition |
| 1912 | 出库 | `querySerialBySkuAndSerial` | 读 | `JdSerialClient.querySerialFlow`:90 | GET /api/v1/jd-serial/flow |
| 2210 | 出库 | `queryWarehouseCoverages` | 读 | `JdBasicInfoClient.queryWarehouseCoverages`:127 | GET /api/v1/jd-basicinfo/warehouse-coverages |
| 2410 | 出库 | `queryPackage` | 读 | **未包装** | — |
| 1552 | 取消 | `cancelOrder` | 写 | `JdWarehouseClient.cancelOutboundOrder`:139 | (无路由，业务内调) |
| 1584 | 基础数据 | `transportGoodsSerialNumberRule` | 写 | `JdWriteOpsClient.serialnumberCreate`:154 | POST /api/v1/jd-write/basicinfo/serialnumber-create |
| 1602 | 基础数据 | `queryWarehouseInfo` | 读 | `JdWarehouseClient.queryWarehouses`:84 | GET /api/v1/jd-warehouse/warehouses |
| 1603 | 基础数据 | `queryOwnerInfo` | 读 | `JdWarehouseClient.queryOwners`:73 | GET /api/v1/jd-warehouse/owners |
| 1604 | 基础数据 | `saveShopInfo` | 写 | `JdWriteOpsClient.shopCreate`:132 | POST /api/v1/jd-write/basicinfo/shop-create |
| 1605 | 基础数据 | `queryShopInfo` | 读 | `JdBasicInfoClient.queryShops`:95 | GET /api/v1/jd-basicinfo/shops |
| 1609 | 基础数据 | `saveGoodsInfo` | 写 | `JdWriteOpsClient.goodsCreate`:99 | POST /api/v1/jd-write/basicinfo/goods-create |
| 1610 | 基础数据 | `queryGoodsInfo` | 读 | `JdWarehouseClient.queryProducts`:95 + `JdBasicInfoClient.queryGoodsInfo`:135 | (无路由) + GET /api/v1/jd-basicinfo/goods-info |
| 1611 | 基础数据 | `queryGoodsLevelCategories` | 读 | `JdBasicInfoClient.queryGoodsCategories`:119 | GET /api/v1/jd-basicinfo/goods-categories |
| 1617 | 基础数据 | `upsert` | 写 | `JdWriteOpsClient.supplierCreate`:121 | POST /api/v1/jd-write/basicinfo/supplier-create |
| 1618 | 基础数据 | `query` | 读 | `JdBasicInfoClient.querySuppliers`:111 | GET /api/v1/jd-basicinfo/suppliers |
| 1895 | 基础数据 | `addOrUpdateCustomerInfo` | 写 | `JdWriteOpsClient.customerCreate`:88 | POST /api/v1/jd-write/basicinfo/customer-create |
| 1896 | 基础数据 | `queryCustomer` | 读 | `JdBasicInfoClient.queryCustomers`:79 | GET /api/v1/jd-basicinfo/customers |
| 1897 | 基础数据 | `getSellerInfo` | 读 | `JdBasicInfoClient.querySellers`:87 | GET /api/v1/jd-basicinfo/sellers |
| 1898 | 基础数据 | `insertLogicalStockConfig` | 写 | `JdWriteOpsClient.logicalinventoryfactorCreate`:176 | POST /api/v1/jd-write/basicinfo/logicalinventoryfactor-create |
| 1901 | 基础数据 | `addGoodsFormula` | 写 | `JdWriteOpsClient.processedCreate`:165 | POST /api/v1/jd-write/basicinfo/processed-create |
| 1902 | 基础数据 | `queryShopGoodsInfo` | 读 | `JdBasicInfoClient.queryShopGoods`:103 | GET /api/v1/jd-basicinfo/shop-goods |
| 2223 | 基础数据 | `transportBoxAndSerialInfo` | 写 | `JdWriteOpsClient.boxandserialnumberTransport`:187 | POST /api/v1/jd-write/basicinfo/boxandserialnumber-transport |
| 1576 | 库内 | `queryCheckStock` | 读 | `JdStockClient.queryShelfLifeGoods`:109 ⚠️命名错位 | GET /api/v1/jd-stock/shelf-life-goods |
| 1612 | 库内 | `queryStock` | 读 | `JdWarehouseClient.queryStock`:106 | (无路由，业务内调) |
| 1628 | 库内 | `transportInsideOrder` | 写 | `JdWriteOpsClient.orderAdjustmentCreate`:198 | POST /api/v1/jd-write/order/adjustment-create |
| 1629 | 库内 | `queryInsideOrder` | 读 | `JdOrderClient.queryAdjustment`:88 | GET /api/v1/jd-order/adjustments |
| 1854 | 库内 | `queryGoodsLevelChange` | 读 | `JdStockClient.queryGoodsLevelChange`:100 | GET /api/v1/jd-stock/level-changes |
| 1855 | 库内 | `queryBatchChange` | 读 | `JdStockClient.queryBatchChange`:92 | GET /api/v1/jd-stock/batch-changes |
| 1857 | 库内 | `setShopStockFixed` | 写 | `JdWriteOpsClient.stockShopstockfixedSet`:297 | POST /api/v1/jd-write/stock/shopstockfixed-set |
| 1884 | 库内 | `queryShelfLifeGoodsList` | 读 | `JdStockClient.queryShelfLifeInventory`:117 | GET /api/v1/jd-stock/shelf-life-inventory |
| 1887 | 库内 | `addProcessOrder` | 写 | `JdWriteOpsClient.orderProcessedCreate`:231 | POST /api/v1/jd-write/order/processed-create |
| 1911 | 库内 | `queryInStockSidBySku` | 读 | `JdSerialClient.querySerialInside`:99 | GET /api/v1/jd-serial/inside |
| 2092 | 库内 | `queryProcessOrder` | 读 | `JdOrderClient.queryProcessed`:120 | GET /api/v1/jd-order/processed-orders |
| 2172 | 库内 | `queryJDMallSerialByPage` | 读 | `JdSerialClient.queryJdMallSerial`:72 | GET /api/v1/jd-serial/mall |
| 2719 | 库内 | `queryWarehouseStockSnapshot` | 读 | `JdStockClient.queryStockSnapshot`:76 | GET /api/v1/jd-stock/snapshot |
| 2775 | 库内 | `queryWarehouseStockMergeByWarehouse` | 读 | `JdStockClient.queryStockSummary`:84 | GET /api/v1/jd-stock/summary |
| 2824 | 默认分类 | `transportGoodsSns` | 写 | **未包装** | — |
| 2968 | 默认分类 | `updatePurchaseOrder` | 写 | **未包装** | — |
| 3525 | 默认分类 | `updateGoodsInfoBySellerGoodsSign` | 写 | `JdWriteOpsClient.goodsUpdateBySellerGoodsSign`:110 | POST /api/v1/jd-write/basicinfo/goods-update-by-seller-goods-sign |
| 3532 | 默认分类 | `saveShopGoodsInfo` | 写 | `JdWriteOpsClient.shopGoodsCreate`:143 | POST /api/v1/jd-write/basicinfo/shop-goods-create |
| 3608 | 默认分类 | `createRainCoverServiceOrder` | 写 | **未包装** | — |
| 3609 | 默认分类 | `cancelRainCoverServiceOrder` | 写 | **未包装** | — |

### 差集

**文档有、SDK 未包装（5）** — 均非当前业务必需，记录备查即可：

| # | apiCode | 说明 | 建议 |
| --- | --- | --- | --- |
| 2410 | `queryPackage` | 包裹查询（非 ISC 风格 URL `/jdlopenplatformsoservice/querypackage`） | 现有 `querySoOrder.deliveryPackageList` 已覆盖包裹信息，暂不需要 |
| 2824 | `transportGoodsSns` | 序列号批量回传（写） | 无序列号管理业务，暂不需要 |
| 2968 | `updatePurchaseOrder` | 采购单修改（写） | 采购单目前只建不改，需要时再补 |
| 3608 / 3609 | `createRainCoverServiceOrder` / `cancelRainCoverServiceOrder` | 揭雨布服务单（2026-07 新增） | 冷链业务不适用 |

**SDK 有、文档没有：0**。

**重复包装（2）** — 见 D3。

## 3. 只读接口实调结果

生产 REAL 通道，每接口 1–2 次。「首调」为按控制器默认最小参数构造，「复调」为按文档参数字典补齐后重试。

| 包装方法 | 首调业务码 | 复调 | 结论 |
| --- | --- | --- | --- |
| `queryOwners` | 1000 | — | ✅ 1 个货主 `EBU4418056064528` |
| `queryWarehouses` | 1000 | — | ✅ 3 个仓（118085840 在用） |
| `queryOutboundOrder` | 1000 | — | ✅ 结构与文档 `outParams` 一致，无字段丢失 |
| `queryGoodsInfo` | 1000 | — | ✅ 但缺必填 `queryType`，见 D6 |
| `queryCustomers` | 1000 | — | ✅ `totalNum=0`（无客户档案，属实） |
| `querySellers` | 1000 | — | ✅ |
| `queryShops` | 1000 | — | ✅ 店铺 `ESP0020008943717` |
| `queryShopGoods` | 1000 | — | ✅ `totalNum=67` |
| `querySuppliers` | 1000 | — | ✅ |
| `queryGoodsCategories` | 1000 | — | ✅ |
| `queryWarehouseCoverages` | 2000 缺 `town` | 1000 | ⚠️ D7 |
| `queryOrderNosByPage` | **2002 日期格式** | 1000（59 单） | ⚠️ **D5** |
| `queryAdjustment` | 2001 查不到单据 | — | SKIPPED：无在库调整单 |
| `queryDestroy` | 1000 | — | ⚠️ 不存在的单号返回全 null 对象而非错误，见 D10 |
| `queryException` | 1000 | — | ✅ `totalNum=0` |
| `queryPurchase` | 1000 | — | ✅ `data=null`（无采购单） |
| `queryProcessed` | 1000 | — | ✅ `data=[]` |
| `queryOperateRelation` | 1000 | — | ✅ `202609010004 → ESL00000025552990157` |
| `queryDeliveryTime` | **2000 缺 `shunt`** | 1000（预计 2026-09-03 15:00 送达） | ⚠️ **D7** |
| `queryCityTrack` | **2000 缺 `customerCode`** | 4000 O2O 异常 | SKIPPED：需青龙业主号（我方仅有 EBU），非同城业务 |
| `queryRtwOrderList` | 2000 四选一均空 | 1000 | ⚠️ D7 |
| `queryRtwOrderDetail` | 1000 | — | ✅ 不存在返回 `data=null` |
| `queryReturnToSupplier` | 3010 单号有误 | — | SKIPPED：无退供出库单 |
| `queryJdMallSerial` | 1000 | — | ✅ `totalNum=0` |
| `querySerialByCondition` | **4000 起始时间为空** | 1000 | ⚠️ **D5**（日期格式，非字段丢失） |
| `querySerialFlow` | 1000 | — | ✅ |
| `querySerialInside` | 1000 | — | ✅ |
| `queryStockSnapshot` | 1000 | — | ⚠️ `total=0` 且 `snapshotStatus=1`，见 D9 |
| `queryStockSummary` | 1000 | — | ✅ `EMG4418727173231` 良品 147 件 |
| `queryBatchChange` | 1000 | — | ✅ |
| `queryGoodsLevelChange` | 1000 | — | ✅ |
| `queryShelfLifeGoods` | **2000 缺 `orderType`** | 1000 | ⚠️ **D7 + D8**（实为盘盈亏查询） |
| `queryShelfLifeInventory` | 1000 | — | ✅ 效期库存 153 件，2027-05-10 到期 |
| `searchShopStockFlow` | **2000 缺 `shopNo`** | 1000（42 条流水） | ⚠️ **D7**（且日期格式含毫秒） |
| `queryTracking` | **2001 客户编码不能为空** | **2001（再次）** | ❌ **D2 死路由** |
| `queryStock` | — | — | 无 REST 路由，仅业务内调（`ShipmentJdStockCheckService:131`） |

**关于「防腐层吞字段」的澄清**：`querySerialByCondition` 首调时京东回显的入参里没有 `startDate/endDate`，初看像防腐层丢字段。经 `javap` 核对 SDK DTO `BusSerialQueryRequest` **确有** `setStartDate/setEndDate`，补齐 `yyyy-MM-dd HH:mm:ss` 格式后复调即 `1000` 成功 — 是京东侧解析失败后丢弃，根因是 D5 日期格式，**不是防腐层丢字段**。特此更正，避免误立票。

## 4. 缺陷清单

### D1 · HIGH · 写面 20 个接口零必填校验，不可逆写操作直接裸奔

- **证据**：`connector/jd/write/JdWriteOpsClient.java:87-304`，20 个方法形状完全一致，都是 `Map → sdkMapper.convertValue → SDK` 纯透传；包内不存在任何校验器。
- **文档依据**：如 `1559-addPoOrder.json` 要求 `ownerNo/erpPurchaseNo/pin/warehouseInfo/cargoInfos[].planQuantity` 必填且 `cargoInfos[].goodsLevel` 只能是 `100/200/300`；`1899-addUlOrder.json` 的 `destroyType` **只能为 1**；`1574-addRtsOrder.json` 的 `deliveryMode` 合法值 `1,2,3,4,6,7`（**5 非法**）。
- **危害**：`orderPurchaseCreate`/`orderDestroyCreate`/`orderReturntosupplierCreate`/`orderProcessedCreate` 均不可逆。必填缺失只会被京东拒绝（尚可接受）；**枚举值合法但语义错误会真的建出一张单**，只能人工去取消。`#213` 的 `orderType=数字` 正是这一结构的产物。
- **修法**：按 API 建一张「必填字段 + 枚举合法值」表（本报告第 5 节即可直接作为数据源），在 `execute(...)` 内、写门闩之前校验，失败返回 `MISSING_REQUIRED_FIELD:<path>` / `ILLEGAL_ENUM_VALUE:<path>`。文档中 `required="2"` 的条件必填（二选一）需要「至少给一个」语义。

### D2 · HIGH · `/api/v1/jd-warehouse/tracking` 是死路由，永远返回 2001

- **证据**：`JdWarehouseController.java:83-95` 只接收 `waybill_no` 与 `warehouse_order_no`，**从不设置 `customerCode`**；`JdIscGateway`/`JdWarehouseClient` 的 `withDefaults` 只注入 `pin`/`ownerNo`，且 `javap` 确认 `CommonOrderTraceRequest` 只有 `setPin/setCustomerCode/setWaybillNo/setEnterpriseOrderNo/setWarehouseOrderNo/setScope` — **没有 `setOwnerNo`**，注入兜不住。
- **文档依据**：`1941-commonQueryOrderTrace.json` — `customerCode` `required="1"`，错误码 2001 即「客户编码不能为空」。
- **实证**：分别用 `warehouse_order_no` 和 `waybill_no` 各调一次，**两次都是 `2001 客户编码不能为空`**。
- **影响面**：仅限该 REST 路由。业务侧轨迹回填走的是 `querySoOrder`（`ShipmentJdTrackingBackfillService.java:203`），**生产业务不受影响**。
- **修法**：控制器补 `customer_code` 参数（仓配场景传事业部 `EBU...`，可直接复用 `app.jd.owner-no`）并透传 `scope`。另注：该 API 文档已标注「2024-12-20 停止维护，新对接请订阅京东物流标准轨迹服务（unitId 469）」，可考虑直接下线本路由。

### D3 · HIGH · `JdWarehouseClient` 里的两个写操作完全不受写模式门闩管辖

- **证据**：`JdWarehouseClient.java:116-146` 实现了 `createOutboundOrder`（API#1596 addSoOrder）与 `cancelOutboundOrder`（API#1552 cancelOrder）。该文件 **没有 `writeMode` 字段、没有 `writeEnabled()` 判断**，与 `JdWriteOpsClient.java:63`（`@Value("${app.jd.write-mode:OFF}")`）、`JdWriteOpsController.java:52-56`（三重门闩：`write-mode` + `generic-http-write-mode` + `client-mode`）形成对照。
- **危害**：生产当前 `JD_LOP_WRITE_MODE=ON`、`CLIENT_MODE=REAL`。一旦出事故要用「关写模式」当急停开关，**这两个写操作不会被拦下**——取消出库单（有活调用方 `ShipmentJdOutboundCancelService.java:75`）照样发到京东。急停开关存在覆盖盲区。
- **附带**：`createOutboundOrder` 全仓库**没有任何调用方**（`grep` 仅命中接口/Mock/实现三处），与 `JdWriteOpsClient.orderSoCreate` 重复包装同一个 API#1596。它是一个无人调用却能绕过门闩的真实写通道。
- **修法**：(a) 删除 `createOutboundOrder`（含 `JDWarehouseService:16` 与 `MockJdWarehouseClient:64`）；(b) 把 `cancelOutboundOrder` 迁入 `JdWriteOpsService`，或在 `JdWarehouseClient` 中为写类操作补同一个 `writeEnabled()` 门闩。

### D4 · HIGH · MOCK 模式对任意入参无条件成功，测试结构性无法拦截契约缺陷

- **证据**：`MockJdWarehouseClient.java:100-106` 与 `write/MockJdWriteOpsClient.java:138-152` 的 `success(...)` 只判断写模式，**不看 request 内容**；`MockJdWriteOpsClient` 的 20 个方法全部直接返回固定 `MOCK_SUCCESS`。
- **危害**：`#213`（`orderType` 传数字）在 MOCK 下必然通过，只有打到生产才暴露。写面 20 个接口的门闩测试用同一个样例 body 驱动，而该 body 不满足其中**任何一个** API 的必填集。绿灯不代表契约正确。
- **最坏的实例**：Mock 不只是「不校验」，还会**伪造文档里不存在的响应形状**——`stock/MockJdStockClient.java:27-34` 返回的 `warehouseNo`/`availableQuantity`/`occupiedQuantity` 在文档与 SDK 里都不存在，于是业务解析照着假契约写、mock 测试常绿、REAL 恒坏。详见 B1。
- **修法**：让 Mock 复用 D1 的必填/枚举表，入参不合规时返回 `MISSING_REQUIRED_FIELD` 而非成功 — 把 Mock 从「回声器」升级为「契约桩」；响应侧的桩形状也必须以文档 `outParams` 为准。再加一个由文档 JSON 驱动的契约测试：对每个 API 逐个抽掉一个必填字段，断言被拒绝。

### D5 · MED · 读作业面不校验日期格式，京东要 `yyyy-MM-dd HH:mm:ss` 而我们原样透传

- **实证**：`queryOrderNosByPage` 传 `2026-08-25` → `2002 查询开始日期必须是yyyy-MM-dd HH:mm:ss`；补成 `2026-08-25 00:00:00` → `1000`，返回 59 单。`querySerialByCondition` 同样，且京东是**静默丢弃**该字段后报「起始时间不能为空」，更难排查。
- **文档依据**：`1581`/`1910`/`1576` 均写明 `格式：yyyy-MM-dd HH:mm:ss，长度：19字符`；**`1856-searchShopStockFlow.json` 例外，要求 `yyyy-MM-dd HH:mm:ss.S`（21 字符，带毫秒）**——同一套作业面里两种日期格式，靠人记必然出错。
- **代码位置**：`order/JdOrderController.java:59-62`、`serial/JdSerialController.java:55-56`、`stock/JdStockController.java:138-139,178-179` 等，全部是 `String` 原样 `putIfPresent`。
- **修法**：在控制器层做格式规范化——接受 `yyyy-MM-dd` 时自动补 `00:00:00`/`23:59:59`，并按目标 API 选择是否补 `.S`；格式不合法直接 400，不要浪费一次京东往返。

### D6 · MED · `queryGoodsInfo` 从不发送文档必填的 `queryType`

- **证据**：`basicinfo/JdBasicInfoController.java:152-166` 只 put 了 `goodsNo/erpGoodsNo/barCode/pageSize/currentPage`；全仓库 `grep queryType` 在 basicinfo 下**零命中**。
- **文档依据**：`1610-queryGoodsInfo.json` — `queryType` `required="1"`，枚举「1-查询全部信息；2-查询商品编号」；同时 `pageSize`、`currentPage` 也是 `required="1"` 而我方为可选。
- **现状**：实调 `1000` 成功并返回了全量信息，说明京东当前按 1 兜底。**这是依赖未文档化的宽容**，京东一旦收紧校验，商品查询即刻全线失败。
- **修法**：显式发送 `queryType=1`，并给 `pageSize`/`currentPage` 设默认值。

### D7 · MED · 京东必填字段在我方 REST 边界一律标成 `required=false`，报错要绕一圈京东才知道

- **实证 4 例**：`queryDeliveryTime` 缺 `shunt`（合法值 1/5）→ 2000；`queryShelfLifeGoods` 缺 `orderType`（合法值 1/2）→ 2000；`searchShopStockFlow` 缺 `shopNo` → 2000；`queryWarehouseCoverages` 缺 `town` → 2000。补齐后四者全部 `1000`。
- **代码位置**：6 个只读控制器的 **全部 33 条路由、每一个 `@RequestParam` 都是 `required = false`**。
- **危害**：调用方拿到的是京东的中文业务错误码而不是本地 400，排障要多一跳；且每次都真实消耗一次京东配额。
- **修法**：按文档 `required="1"` 把对应参数改成 `required=true`（或补本地校验），错误信息直接引用文档话术（如「shunt 必填，快递=1，仓配=5」）。

### D8 · MED · `queryShelfLifeGoods` 名实不符——它其实是「盘盈亏查询」

- **证据**：`stock/JdStockClient.java:107-112` 的 `queryShelfLifeGoods` 包装的是 `/integratedsupplychain/stock/shelflifegoods/query/v1`，对应文档 `1576-queryCheckStock.json`，**apiName 是「盘盈亏查询」**，`orderType` 为「盘点单类型：1:盘盈，2:盘亏」。
- **对照**：真正的效期库存查询是 `1884-queryShelfLifeGoodsList`（`/stock/Shelflifeinventory/query/v1`），包装为 `queryShelfLifeInventory` — 实调确实返回效期数据（生产日期/到期日/剩余天数）。
- **危害**：两个方法名 + REST 路径 `/shelf-life-goods` 都在误导使用者。京东的 URL 路径本身就取错了名（`shelflifegoods` 指向盘点单），我们照抄 URL 命名把这个错误固化进了自家 API。
- **修法**：重命名为 `queryCheckStock` / `GET /api/v1/jd-stock/check-stock`（旧路由保留一段时间做兼容），并在 javadoc 里写明「京东 URL 名与语义不符」这一坑。

### D9 · MED · `queryStockSnapshot` 的 `snapshotStatus` 语义未被处理

- **实证**：对 `EMG4418727173231` 调用 `snapshot` 返回 `total=0`、`snapshotStatus=1`、`warehouseStockSnapshotList=null`，但同一 SKU 的 `queryStockSummary` 同时返回良品 **147 件**。
- **危害**：快照是异步生成的，`snapshotStatus` 指示快照是否就绪。防腐层与调用方都不解析该字段，会把「快照尚未就绪」当成「库存为 0」。用于补货/库存决策时是错误结论。
- **文档依据**：`2719` 的 `outParams` 确实声明了 `total`/`snapshotStatus`/`cursor` 三个信封字段。
- **修法**：在读取快照的业务路径上显式判断 `snapshotStatus`，未就绪时返回「数据未就绪」而非 0，并考虑回退到 `queryStockSummary`。
- **关联**：该 API 的业务侧解析另有更严重的问题，见 B1 / B8。

### D10 · MED · `queryDestroy` 对不存在的单号返回「成功 + 全 null 对象」

- **实证**：`erp_destroy_no=NOTEXIST0001` → `1000` + `data` 为所有字段皆 null 的对象（对比：`queryRtwOrderDetail` 返回 `data=null`，`queryReturnToSupplier` 返回 `3010` 错误）。
- **危害**：同一套作业面里「查无此单」有三种表现，调用方若只判断 `success` 会把空壳对象当成有效单据。
- **修法**：在防腐层或调用方统一「查无此单」的判定（如 `data` 为空或关键标识字段为 null 即视为 NOT_FOUND）。

### D11 · LOW · `JdIscGateway` 是一个没有接线的死单元

- **证据**：`grep JdIscGateway` 在全仓库只命中 **它自己**和 `JdAuditProjection.java:8` 的一句 javadoc 引用 — **没有任何类注入它**。与此同时 7 个客户端（`JdWarehouseClient`/`JdBasicInfoClient`/`JdOrderClient`/`JdReturnClient`/`JdSerialClient`/`JdStockClient`/`JdWriteOpsClient`）**各自 `new JdlClient(...)`**，各自复制一份 `execute`/`normalize`/`withDefaults`/`supports`/`configured` 与 7 个凭据 `@Value` 字段。
- **矛盾点**：`JdIscGateway.java:25-32` 的 javadoc 声称「收编后各客户端只保留自己真正独有的东西」，`:38-41` 声称 `SUCCESS_CODES` 是「全库唯一一份集合」——而该集合实际存在 **8 份**副本。重构写完但从未接线。
- **危害**：网关里已修好的 `requestID`（大写 D）兜底逻辑（`:143-150`）不会惠及任何在跑的客户端；今后 D1/D5 一类的修复要改 8 处。
- **修法**：要么完成接线（7 个客户端注入 `JdIscGateway`，只保留 DTO 选择与自己的门闩），要么删掉网关别留误导性 javadoc。**接线是把 D1 的校验一次性落到所有接口的前提**，建议优先。

### D12 · LOW · 写面吃 camelCase，而作业面其余部分是 snake_case

- **证据**：`JdWriteOpsClient.java:74-75` 用 `LOWER_CAMEL_CASE` 构造 `sdkMapper`，但 Jackson 的命名策略**不重命名 Map 的 key**，而 `JdWriteOpsController` 收的是 `@RequestBody Map<String,Object>`（key 原样透传）。生产 `ObjectMapper` 未开启 `FAIL_ON_UNKNOWN_PROPERTIES`，仓库自己的测试 `JdBasicInfoClientRequestMappingTest.java:412` 就写着「默认关闭 FAIL_ON_UNKNOWN_PROPERTIES，静默丢弃」。
- **危害**：调用方按作业面其余部分的习惯发 `owner_no`/`erp_purchase_no`，**所有字段被静默丢弃**，空 DTO 发给京东，换回一句没有线索的 `2000 必填字段为空`。
- **修法**：给 `sdkMapper` 单独开启 `FAIL_ON_UNKNOWN_PROPERTIES`，未知 key 直接返回 `INVALID_COMMAND_FIELD:<key>`，让拼写/风格错误在本地就炸。

### D13 · LOW · SDK 异常细节被丢弃，「写不确定」与「被拒绝」无法区分

- **证据**：`JdWriteOpsClient.java:323-325` 与 `JdWarehouseClient.java:174-176` 都是 `catch (Exception) → SDK_CALL_FAILED`；两处的 `safeMessage(exception)`（`JdWriteOpsClient.java:401-403`、`JdWarehouseClient.java:340-342`）**完全忽略入参**，返回固定话术，且文件里没有 logger。
- **危害**：`addPoOrder` 已被京东受理后连接超时，与参数校验硬拒绝，在审计里长得一模一样。事后无法回答「这张采购单到底建没建成」。
- **修法**：记录异常类名与脱敏消息进审计 `responsePayload`，并把 `SDK_CALL_FAILED` 拆成 `SDK_TRANSPORT_UNCERTAIN`（IO/超时，必须对账）与 `SDK_REJECTED`。

## 4.1 业务侧命令构造缺陷

防腐层对入参不做判断，所以「值填错」的根都在构造 `Map` 的业务代码里。以下逐条已用文档 JSON + `javap` 核对。

### B1 · HIGH · 快照响应解析读的三个字段在文档和 SDK 里都不存在，REAL 下必然失败，而 Mock 伪造了这个形状

- **证据**：`fulfillment/FulfillmentStockDecisionService.java:377-386` 读 `warehouseNo`、`availableQuantity`、`occupiedQuantity`，并在 `warehouseNo` 或 `available` 为 null 时 `return null`。
- **文档依据**：`2719-queryWarehouseStockSnapshot.json` 的 `outParams` 叶子字段全集为 `goodsNo, ownerName, stockType, usableNum, sellerNo, isvSku, ownerNo, goodsLevel, stockNum, stockStatus, goodsName, sellerGoodsSign`（外加 `total/snapshotStatus/cursor`）——**没有 `warehouseNo`，没有 `availableQuantity`，没有 `occupiedQuantity`**。`javap` 对 `WarehouseStockSnapshotInfo` 的 getter 与文档完全一致，同样没有这三个。
- **加重情节**：`stock/MockJdStockClient.java:27-34` 返回的恰恰是这个虚构形状（`warehouseNo`/`availableQuantity`/`occupiedQuantity`，且 `goodsLevel: "1"`）。**Mock 认证了一份不存在的契约**，所以所有 mock 测试常绿，而 REAL 必然 `malformed_payload`。这是 D4 的最佳例证。
- **缓解**：该路径当前是死的——`FulfillmentStockDecisionService.java:113-117` 在 `applyJdRealTime` 之前就对 `JD_WAREHOUSE` 失败关闭，且 `applyJdRealTime` 无其他调用方。故列 HIGH 但当前不出血。
- **修法**：若复活该路径，映射 `usableNum`→可用、`stockNum`→在库，去掉仓库维度（该 API 是按事业部而非按仓的，`insertJdSnapshot` 的 `warehouse_code` 在这里没有合法来源），或改用有 `warehouseNo` 的 `1612-queryStock`；**同一次改动里必须修正 `MockJdStockClient`**。或者按 `:38-45` javadoc 所说的「已退役」直接删掉 `applyJdRealTime`+`snapshotRequest`+`parseSnapshot`。

### B2 · HIGH · 未决写的对账查询不带 `ownerNo`/`pin`，可能永远查不到自己刚建的单

- **证据**：`fulfillment/ShipmentJdOutboundExecutor.java:117-122` 的 `reconcileUncertainSubmit` 只发 `erpDeliveryNo` + 三个 flag。而 `SoQueryRequest` 有 `setOwnerNo`/`setPin`（javap 确认），于是网关注入的是**全局** `app.jd.pin`/`app.jd.owner-no`；建单时用的却是**按 provider 配置**的 `customerInfo.ownerNo` 与 `pin`（`ShipmentJdOutboundPreparer.java:126,142-143`）。
- **文档依据**：`1632-querySoOrder.json` — `ownerNo`「如填写 erpDeliveryNo 时，必填」，`pin` `required="1"`。
- **危害**：任何 provider 配置与全局值不一致的部署，对账查询都会查错事业部、找不到刚建的单，`:138-147` 随即永久返回 `RECONCILIATION_REQUIRED` —— 正是重复发货类事故最难收敛的那个状态。
- **对照**：同一个 API 的兄弟调用点 `ShipmentJdTrackingBackfillService.java:190-192` 做得是对的（传 `submittedOwnerNo` + `currentPin`）。**同一个 API 的三个调用点用了三种取值策略，只有一种站得住。**
- **修法**：把 `ownerNo`/`pin`（已在 `plan.request()` 里）带到 `JdShipmentSubmissionPlan` 上，在 `:118` 显式设置，对齐 `ShipmentJdTrackingBackfillService.java:190-192`。

### B3 · MED · `receiverInfo.detailAddress` 可能超京东 100 字符上限，既不校验也不截断

- **文档依据**：`1596-addSoOrder.json` — `receiverInfo.detailAddress` `长度：1-100字符`（`name` 1-20、`mobile` 1-30 同理）。
- **证据**：`fulfillment/ShipmentJdOutboundPreparer.java:322-325` 原样透传 `state.receiverAddress()`，来源列 `shipments.receiver_address_snapshot TEXT NOT NULL` 无长度约束；该方法自己的 javadoc（`:293-306`）举的真实生产地址就是三段拼接，正是会超 100 的形状。人工确认地址路径 `putConfirmedAddress`（`:334-356`）更宽，命令对象是 `@Size(max = 255)`（`ShipmentJdReceiverAddressCommand.java:16`），是京东上限的 2.5 倍。
- **修法**：在两处地址装配点加 **blocker（不是静默截断——截断地址会真的发错货）**，并把命令对象收紧到 `@Size(max = 100)`；`name`/`mobile` 一并纳入同一轮长度校验。

### B4 · MED · 取消接口的 `order_type` 来自调用方且不校验，`#213` 的洞只堵了默认值

- **证据**：`fulfillment/ShipmentJdOutboundCancelService.java:64` 的默认值 `"XSCK"` 正确且合法，但任何非空的调用方取值只 `trim()` 就透传（入口 `ShipmentJdOutboundCancelController.java:23,33`）。客户端 POST `{"order_type":"2"}` 即可复现原始的「数字 vs 字典」缺陷。
- **文档依据**：`1552-cancelOrder.json` — `orderType` `required="1"`，字典 `CGRK/XSCK/THRK/TGCK/ZKTZ/ZTJG/BFCK`。
- **附带**：`ShipmentJdOutboundCancelController.java:22` 的 javadoc 仍写着「销售出库默认 **2**」——**正是造成 3008 的那个错误值**，会把下一个读代码的人再带进坑里。
- **修法**：按字典集合校验并返回 422；同步订正该 javadoc。

### B5 · MED · 取消接口依赖全局 `pin`/`ownerNo`，而非建单时的 provider 配置

- **证据**：`ShipmentJdOutboundCancelService.java:71-74` 只设 `erpOrderNo`/`orderNo`/`orderType`；`OrderCancelRequest` 两个 setter 都有（javap），故注入全局值。与 B2 同类，但发生在**写**操作上。且 `orderNo` 取自可空的 `jdDeliveryNo`，为空时文档使 `ownerNo` 无条件必填，错配即硬失败（`2002 货主编码有误` / `2003 授权码有误`）。
- **修法**：按 shipment 读 provider 配置显式设置 `pin`/`ownerNo`。

### B6 · MED · `queryStock` 不做 100 个商品编码的分批，且只读第一页、丢弃 `totalNum`

- **文档依据**：`1612-queryStock.json` — `goodsNo`「最多支持100个商品编码」，`pageSize` 最大 1000，响应有 `totalNum`。
- **证据**：`fulfillment/ShipmentJdStockCheckService.java:423` 无上限拼接所有 `goodsNo`；`:428` 用去重数量当 `pageSize`；`:597-607` 只读 `resultList`，**从不读 `totalNum`**，因此静默截断与「查无此行」不可区分，最终表现为 `JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED`（`:327-330`）。方向是 fail-closed（误拦不误放），故列 MED。
- **修法**：`goodsNo` 按 ≤100 分批；比对 `totalNum` 与 `resultList.size()`，不一致时抛独立的 `JD_STOCK_RESPONSE_TRUNCATED`，别让它伪装成缺行。

### B7 · MED · `goodsLevel` 良品是 100–199 区间，代码按等于 `"100"` 处理

- **文档依据**：`1612-queryStock.json` — `goodsLevel`「100-199 良品；200-299 残品；6 待鉴定」，请求与响应同一套编码。
- **证据**：请求侧 `ShipmentJdStockCheckService.java:424` 发 `"100"`；响应侧 `parseObservation`（`:377`）拒绝一切 `goodsLevel != "100"` 的行。等级为 101–199 的商品仍是良品、仍可售，却被判为不可见 → 误报 `JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED` 拦单。
- **修法**：响应侧按数值区间接受 `100 <= level <= 199`；请求侧若确要钉死 100，写注释说明是政策而非笔误。

### B8 · MED · 快照请求发 `goodsLevelList=["1"]`，字典里没有这个值

- **文档依据**：`2719-queryWarehouseStockSnapshot.json` — `goodsLevelList`「商品等级，枚举值：100、良品。默认100」。
- **证据**：`fulfillment/FulfillmentStockDecisionService.java:350` 写 `List.of("1")`，`:349` 注释还断言「goodsLevel=1 正品」。合法值是 `100`。（同处 `stockTypeList = List.of(1)` 是对的，文档确为 `List<Integer>`。）另：`ownerNo`（`required="1"`）未显式设置、走全局注入；`goodsNoList` 的 50 个上限未强制（`:348`），无 `pageSize`/`cursor` 分页。
- **缓解**：与 B1 同一条死路径。修或删，二选一。

### B9 · MED · 响应字段吞没：`carrierInfo.tpWaybillNo`（众邮）从不读取

- **文档依据**：`1632-querySoOrder.json` — `carrierInfo` 下并列 `waybillNo`、`tpWaybillNo`（众邮运单号）、`pickupWaybillNo`。
- **证据**：`ShipmentJdTrackingBackfillService.java:543-545` 只读 `carrierNo`/`carrierName`/`waybillNo`，`:555-557` 一旦 `waybillNo` 为空即返回 `JD_TRACKING_CARRIER_INCOMPLETE`；`recon/OutboundReconService.java:337-339` 有同样的三字段盲区。京东若走众邮、把单号放在 `tpWaybillNo`，一单发得好好的却会自动回填失败。
- **修法**：判定「承运信息不全」之前先回退到 `tpWaybillNo`（逆向取件场景再考虑 `pickupWaybillNo`），并让回退值同样走 `CarrierPrefixMatcher` 前缀识别（`:564-565`）。

### B10 · MED · 文档可选的 `isSplit` 被当成必填，导致整条自动回填被硬失败

- **文档依据**：`1632-querySoOrder.json` — `response.data.isSplit` `required="0"`。
- **证据**：`ShipmentJdTrackingBackfillService.java:540` 用 `remoteRequiredToken(...)`，缺值即抛 `MalformedRemoteResponse` → `JD_TRACKING_RESPONSE_MALFORMED`（`:593-594`）。对比 `:539` 对 `splitDeliveryNos` 就用的可选读法，而 `:541` 本来就从拆单列表非空推导拆单性——这个「必填」根本不承重。
- **修法**：改 `remoteOptionalToken`，缺值当 `"0"`。

### B11 · LOW · provider 配置不校验京东要求的编码形状

- **文档依据**：`1596-addSoOrder.json` — `customerInfo.ownerNo`「格式：EBU开头」、`customerCode`「格式：010K开头」、`warehouseNo`「长度：9字符」；`1552-cancelOrder.json` 错误码 `2002 货主编码有误,必须是EBU开头`。
- **证据**：`sku/FulfillmentProviderJdConfig.java:81-100` 的 `validate` 只查「非空字符串」，仅对 `outboundMode`/`addressAnalysis` 有专门规则。打错的 `ownerNo` 能过配置校验，直到建单才被京东打回。
- **修法**：在 `validate` 里补 `ownerNo`(EBU 前缀)、`customerCode`(010K 前缀)、`sourceNo`(ISV 前缀)、`warehouseNo`(9 位) 断言，把错误提前到配置时。

### 业务侧已核对且合规的部分（可审计的覆盖面）

- **`addSoOrder` 构造（`ShipmentJdOutboundPreparer.java:111-183`）整体是对的**：发出的全部字段名与文档 `lowerCamelCase` 集合逐一吻合，**无拼写错误、无静默丢弃**；文档必填项全部在位；配置来源的标识符一律走 `putRequiredConfig`（`:438-458`）**阻断而非兜底**，是正确的 fail-closed 姿态。`addressAnalysis` 传 `Integer` 2（类型与枚举都对）、`cargoInfos[].goodsLevel` 传 `String "100"`（类型与值都对）、`planQuantity` 经 `intValueExact()` 保证精确正整数、内部字段 `skuId` 在算请求哈希**之前**（`:193` 早于 `:214`）已剔除、`pin` 显式设置而 `withDefaults` 用 `putIfAbsent` 故显式值优先——这些都逐一验证过。写路径不发任何日期字段，因此没有 D5 的格式暴露。
- **`querySoOrder` 的模范调用点**是 `ShipmentJdTrackingBackfillService.java:189-201`（带 `submitted_owner_no` + `current_pin` + 三个 `Integer` flag），响应侧读取的字段全部是文档真实声明的 `outParams` 且嵌套层级正确；对 `realQuantity` 为空的容忍（`:617-620`）恰好匹配文档「未出库则不返回 realQuantity」。
- **`queryStock`（`ShipmentJdStockCheckService.java:415-430`）是唯一没有租户错配暴露的读路径** —— `pin`/`ownerNo` 取自 plan，与建单同源。`stockIndexes="1"` 与必填 `warehouseNo`、`warehouseStock` 子对象的配对符合文档；`returnZeroStock="2"` 是刻意的正确选择（否则真实的 0 与缺行无法区分）。
- **`queryGoodsInfo`（`sku/JdGoodsReadOnlyVerifier.java:46-50`）** `queryType="1"` 正确，javadoc 还留有上次误传 `"2"` 的审计证据，是好的制度记忆。
- **网关默认注入策略本身是稳的**：`supports()` 反射探测真实 setter，不会引发未知属性转换失败；`putIfAbsent` 保证业务显式值永不被覆盖。已用 javap 对 5 个 DTO 交叉验证。

**未能判定**（需要读线上配置/DB，本次只读授权不覆盖）：B2/B5/B8 及 `JdGoodsReadOnlyVerifier` 的租户错配**当下是否已在出血**，取决于 `app.jd.pin`/`app.jd.owner-no` 是否恰好等于各 provider `fulfillment_providers.config` 里的值。但代码级缺陷成立与否与此无关——**同一个 API 的三个调用点用了三种取值策略，只有一种站得住**。

## 5. 写接口静态对照结论（未真实调用）

20 个写包装（`JdWriteOpsService` 声明 20 个方法，非 21）逐个与文档参数字典对照：

- **DTO 选择全部正确**：20 个包装所用 SDK DTO 的简单类名与文档根 `logogramType` **一一对应，零错配**。曾怀疑的 `goodsUpdateBySellerGoodsSign`（用 `updateGoodsInfoBySellerGoodsSign.GoodsInfoSaveRequest`）经 `javap` 核对为**误报**：文档 3525 根类型本就叫 `GoodsInfoSaveRequest`，且该 SDK 类独立成包、3 个 setter 与文档 3 个顶层字段完全吻合。
- **DTO setter 与文档字段逐一核对**（`javap` 于 `backend/libs/IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar`）：20 个 DTO 全部匹配，唯一多出的是 `ShopStockRequest.setShopStockFixed`（文档 1857 未声明，疑为文档遗漏，用前需与京东确认）。
- **`withDefaults` 的两处注入盲区**（对应 D1，需专门列出）：
  - `SoCreateOrderRequest` 无 `setOwnerNo`，文档 1596 要求的是**嵌套的** `customerInfo.ownerNo`（`required="1"`）。目前靠 `ShipmentJdOutboundPreparer` 显式填充才没出事。
  - `AdjustmentMainRequest` 无 `setOwnerNo`，文档 1628 要求的是**改名的** `sourceOwnerNo`（`required="1"`），且**没有**任何业务路径替它填充。调用方若相信「pin/ownerNo 会被自动注入」，必得 2000。
  - 注入是靠反射探测 setter 的（`supports()`），探测不到就**静默什么都不做** —— 三种可能里最差的一种。
- **枚举字段合法值清单**（D1 校验表的数据源，摘其要者）：

| API | 字段 | 合法值 |
| --- | --- | --- |
| 1552 cancelOrder | `orderType` | `CGRK` `XSCK` `THRK` `TGCK` `ZKTZ` `ZTJG` `BFCK` |
| 1559 addPoOrder | `cargoInfos[].goodsLevel` | `100` `200` `300`（字符串） |
| 1574 addRtsOrder | `deliveryMode` | `1` `2` `3` `4` `6` `7`（**5 非法**） |
| 1574 addRtsOrder | `cargoInfos[].goodsLevel` | `100` `200` `300` `400` |
| 1596 addSoOrder | `sourceNo` | 固定值 `ISV0020008045424` |
| 1604 saveShopInfo | `outBoundRules` | 固定值 `1000000000` |
| 1628 transportInsideOrder | `isLack` / `bizType` | `0,1` / `1`–`9` |
| 1813 updateDeliveryCommand | `deliveryBeforeCommand` / `wmsBeforeCommand` | `2,3,4,5`（非 2/3/4 报 2178）/ `1` |
| 1887 addProcessOrder | `mixMode` / `processedType` | `0,1,2` / `1`=组合 `2`=拆解 |
| 1895 addOrUpdateCustomerInfo | `customerType` / `transferType` | `1`–`10` / `0,1` |
| 1899 addUlOrder | `deliveryMode` / `destroyType` | `1,2` / **仅 `1`** |
| 1901 addGoodsFormula | `formulaDetailList[].processedUnit` | `0`=件 `1`=克 |

- **写门闩现状（正确的部分）**：`JdWriteOpsController` 三重门闩（`write-mode` + `generic-http-write-mode` + `client-mode=REAL` 时拒绝通用 HTTP 写）+ `JdWriteOpsClient`/`MockJdWriteOpsClient` 各自的 `writeEnabled()`，20 个写接口全部覆盖，默认 `OFF`，拒绝时照样写审计。**姿态是对的，问题只在 D3 的两个漏网写操作。**

## 6. 可直接贴的票文

### 票 A（HIGH）：京东写面补齐必填/枚举校验，并把 Mock 升级为契约桩

> `connector/jd/write/JdWriteOpsClient.java:87-304` 的 20 个写包装是 `Map → DTO` 纯透传，不校验任何文档必填字段与枚举合法值；`MockJdWriteOpsClient.java:138-152` 对任意入参无条件返回 `MOCK_SUCCESS`。结果是 `#213`（`orderType` 传数字被京东 3008 拒绝）这类缺陷在 MOCK 与 CI 中结构性不可见，只能打到生产才暴露；而 `addPoOrder`/`addUlOrder`/`addRtsOrder`/`addProcessOrder` 不可逆，枚举值「合法但语义错误」会真的建出单据。
> 做法：以 `docs/research/jd-warehouse-openapi-docs/json/*.json` 的 `inParams`（`required="1"` 必填、`required="2"` 二选一、`remark` 内的枚举字面量）生成一张 per-API 校验表，在 `execute(...)` 内、写门闩之前校验，失败返回 `MISSING_REQUIRED_FIELD:<path>` / `ILLEGAL_ENUM_VALUE:<path>`；Mock 复用同一张表；补一个文档驱动的契约测试（逐个抽掉必填字段断言被拒）。
> 枚举清单见 `docs/research/jd-sdk-debug-report-20260901.md` 第 5 节。

### 票 B（HIGH）：把 `JdWarehouseClient` 的两个写操作纳入写模式门闩

> `JdWarehouseClient.java:116-146` 的 `createOutboundOrder`(API#1596) 与 `cancelOutboundOrder`(API#1552) 所在文件没有任何 `write-mode` 判断，而 `JdWriteOpsClient`/`JdWriteOpsController` 有三重门闩。生产当前 `JD_LOP_WRITE_MODE=ON` + `CLIENT_MODE=REAL`，一旦需要用「关写模式」做急停，这两个写操作不会被拦下——其中 `cancelOutboundOrder` 有活调用方 `ShipmentJdOutboundCancelService.java:75`。
> 另：`createOutboundOrder` 全仓库无任何调用方（仅接口/Mock/实现三处），与 `JdWriteOpsClient.orderSoCreate` 重复包装同一个 API#1596，是一条无人使用却能绕过门闩的真实写通道。
> 做法：删除 `createOutboundOrder`（含 `JDWarehouseService:16`、`MockJdWarehouseClient:64`）；将 `cancelOutboundOrder` 迁入 `JdWriteOpsService`，或为其补同一个 `writeEnabled()` 门闩。

### 票 C（MED）：只读作业面按文档补齐必填参数与日期格式规范化

> 6 个只读控制器共 33 条路由，**每一个 `@RequestParam` 都是 `required=false`**，京东的必填字段在我方边界一律可选，报错要绕一圈京东才知道。生产实调已证 4 例：`queryDeliveryTime` 缺 `shunt`、`queryShelfLifeGoods` 缺 `orderType`、`searchShopStockFlow` 缺 `shopNo`、`queryWarehouseCoverages` 缺 `town`，补齐后全部 `1000`。
> 另有日期格式问题：`queryOrderNosByPage` 传 `2026-08-25` 被 `2002` 拒绝（要求 `yyyy-MM-dd HH:mm:ss`），`querySerialByCondition` 更是被京东静默丢字段后报「起始时间不能为空」；而 `searchShopStockFlow` 独家要求 `yyyy-MM-dd HH:mm:ss.S`（带毫秒）。
> 还有 `queryGoodsInfo` 从不发送文档必填的 `queryType`（目前靠京东按 1 兜底，属依赖未文档化的宽容）。
> 做法：按各 API 文档的 `required="1"` 收紧参数；控制器统一做日期规范化（`yyyy-MM-dd` 自动补时分秒，按目标 API 决定是否补 `.S`），格式非法直接 400。

### 票 D（MED）：修复 `/api/v1/jd-warehouse/tracking` 死路由（或直接下线）

> 该路由永远返回 `2001 客户编码不能为空`（已用 `warehouse_order_no` 与 `waybill_no` 各实调一次复现）。根因：`JdWarehouseController.java:83-95` 从不设置文档 `1941` 要求的必填 `customerCode`，而 `withDefaults` 只注入 `pin`/`ownerNo`，`CommonOrderTraceRequest` 又没有 `setOwnerNo` 兜底。业务侧轨迹回填走的是 `querySoOrder`，故生产业务不受影响，纯属死端点。
> 注：京东文档已标注该 API 于 2024-12-20 停止维护，建议评估直接下线本路由，或迁移到「京东物流标准轨迹服务」(unitId 469)。

### 票 E（MED）：`queryShelfLifeGoods` 名实不符，实为「盘盈亏查询」

> `JdStockClient.java:107-112` 的 `queryShelfLifeGoods` 与路由 `/api/v1/jd-stock/shelf-life-goods` 包装的是文档 `1576-queryCheckStock`（apiName「盘盈亏查询」，`orderType` 为 1=盘盈/2=盘亏）。真正的效期库存是 `1884-queryShelfLifeGoodsList`，已包装为 `queryShelfLifeInventory`。京东自己的 URL 路径（`shelflifegoods`）就取错了名，我们照抄 URL 命名把该错误固化进了自家 API，误导使用者。
> 做法：重命名为 `queryCheckStock` / `/api/v1/jd-stock/check-stock`，旧路由保留一段兼容期，javadoc 注明此坑。

### 票 F（LOW）：完成或删除 `JdIscGateway` 接线

> `JdIscGateway` 没有任何类注入（`grep` 仅命中自身与 `JdAuditProjection.java:8` 的一句 javadoc）；7 个客户端仍各自 `new JdlClient(...)` 并各持一份 `execute`/`normalize`/`withDefaults`/`configured` 与 7 个凭据字段。网关 javadoc 宣称的「收编完成」「`SUCCESS_CODES` 全库唯一一份」与事实相反——该集合实际有 8 份副本，网关里已修好的 `requestID` 兜底也惠及不到任何在跑的客户端。
> 做法：优先完成接线（这是票 A 的校验能一次性覆盖所有接口的前提）；若决定不接，请删掉网关，勿留误导性 javadoc。

### 票 G（HIGH）：未决写的对账查询必须用建单时的 `ownerNo`/`pin`

> `ShipmentJdOutboundExecutor.java:117-122` 的 `reconcileUncertainSubmit` 调 `querySoOrder` 时只发 `erpDeliveryNo` + 三个 flag，不带 `ownerNo`/`pin`；因 `SoQueryRequest` 有这两个 setter，网关会注入**全局** `app.jd.pin`/`app.jd.owner-no`，而建单用的是**按 provider 配置**的值（`ShipmentJdOutboundPreparer.java:126,142-143`）。文档 `1632-querySoOrder.json` 规定「填 erpDeliveryNo 时 ownerNo 必填」、`pin` 必填。
> 后果：任何 provider 配置与全局值不一致的部署，对账都会查错事业部、找不到自己刚建的单，`:138-147` 随即永久停在 `RECONCILIATION_REQUIRED` —— 重复发货类事故最难收敛的那个状态。
> 同一个 API 的三个调用点用了三种取值策略，只有 `ShipmentJdTrackingBackfillService.java:190-192` 是对的。请以它为模板统一 `ShipmentJdOutboundExecutor:118`、`OutboundReconService.java:302-306`、`ShipmentJdOutboundCancelService.java:71-74`（后者是**写**操作，见票 B）与 `JdGoodsReadOnlyVerifier.java:46-50`。

### 票 H（HIGH）：库存快照解析读的是不存在的字段，Mock 伪造了同一份假契约

> `FulfillmentStockDecisionService.java:377-386` 读 `warehouseNo`/`availableQuantity`/`occupiedQuantity` 并在前两者为 null 时返回 null。文档 `2719-queryWarehouseStockSnapshot.json` 的 `outParams` 与 SDK `WarehouseStockSnapshotInfo` 的 getter（javap 双向核对一致）**都没有这三个字段**，真实字段是 `usableNum`/`stockNum`/`goodsLevel` 等，且该 API 按事业部返回、根本没有仓库维度。故该解析对 REAL 必然 `malformed_payload`。
> 加重情节：`connector/jd/stock/MockJdStockClient.java:27-34` 返回的正是这份虚构形状（连 `goodsLevel: "1"` 这个非法等级都一致），于是 mock 测试常绿而 REAL 恒坏。同一请求侧 `:350` 还发着字典外的 `goodsLevelList=["1"]`（合法值是 `100`）。
> 该路径当前不可达（`:113-117` 提前 fail-close，`applyJdRealTime` 无其他调用方），且 `:38-45` javadoc 自称已退役。**建议直接删除 `applyJdRealTime` + `snapshotRequest` + `parseSnapshot` + `MockJdStockClient` 对应桩**；若要复活，必须在同一次改动里按真实字段重写解析并修正 Mock。

## 7. 复现方法

只读实调通道（生产，只读）：

```bash
# 脚本经 stdin 送入容器，凭据只从容器环境变量取，不落盘
ssh -o ConnectTimeout=15 zimupc \
  "docker exec -i zimu-fulfillment-backend-1 python3 -" < probe.py
```

`probe.py` 用 `APP_ADMIN_USER`/`APP_ADMIN_PASSWORD` 组 Basic 认证、`X-Operator` 同用户名，请求 `http://localhost:8080/api/v1/jd-{warehouse,basicinfo,order,return,serial,stock}/...`。

文档参数字典查询：

```bash
python3 scratchpad/doc.py 1596-addSoOrder in    # 打印 inParams 树 + errorCodes + requestDemo
```

（`doc.py` 遍历 `docs/research/jd-warehouse-openapi-docs/json/*.json` 的 `data.inParams`/`outParams` 嵌套树，输出 `字段路径 / 类型 / required / 示例值 / 备注`。）

SDK DTO 字段核对：

```bash
javap -classpath backend/libs/IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar \
  'com.lop.open.api.sdk.domain.IntegratedSupplyChain.<Service>.<method>.<Dto>'
```

## 8. 未覆盖 / 待办

| 项 | 原因 |
| --- | --- |
| `queryCityTrack` 实调 | 需青龙业主号（`010K`/`012K` 开头），我方仅有事业部号 `EBU...`；且非同城配送业务 |
| `queryReturnToSupplier` 实调 | 无退供出库单实体，不硬造数据 |
| `queryAdjustment` 实调 | 无在库调整单实体 |
| 20 个写接口实调 | 按红线一律不发；仅静态对照 + MOCK 核验 |
| `required="2"`（条件必填）的运行时行为 | 文档仅以中文散文描述条件，未在 JSON 中结构化，无法机械校验 |
| 外部字典 `salesPlatformSourceNo`(54604)、`thirdCategoryCode`(API#1611) | 文档给的是链接而非枚举，需另行抓取 |
