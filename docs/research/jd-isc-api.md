# 京东 ISC SDK 接口面提取（JDWarehouseClient 封装依据）

> 来源：`backend/libs/` 下两个 jar 的 class 反编译（`unzip -l` + `javap`），非官方文档。
> 日期：2026-08-10。标注「待文档确认」的项需京东开放平台文档核对。
> 结论速览：7 个能力全部在 SDK 中找到真实对应 API；运单查询用「订单轨迹查询 v2」承担，语义为物流轨迹而非单点运单状态。

---

## 1. 两个 jar 的角色

| Jar | 角色 | 内容 |
|---|---|---|
| `lop-opensdk-support-1.0.30.jar` | 底层 LOP SDK（基础设施） | client（`JdlClient` / `DefaultLopClient` / `DefaultDomainApiClient` / `TokenClient`）、request 基类（`DomainAbstractRequest` / `LopRequest` / `DomainRequest`）、response 基类（`AbstractResponse`）、插件体系（`OAuth2Plugin` 等）、内置 fastjson、websocket、签名工具 |
| `IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar` | 京东物流 ISC 领域层（业务类） | ① 具体请求包装类 `com.lop.open.api.sdk.request.IntegratedSupplyChain.Integratedsupplychain*V1LopRequest`（继承 `DomainAbstractRequest`）；② 具体响应类 `com.lop.open.api.sdk.response.IntegratedSupplyChain.Integratedsupplychain*V1LopResponse`（继承 `AbstractResponse`）；③ 领域 DTO `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatform*Service/<method>/...` |

### 关键 client / 入口类

- **`com.lop.open.api.sdk.JdlClient`**（主要入口）
  - 构造：`JdlClient(String, String, String, String)`，另有 `(…, int connectTimeout, int readTimeout)` 重载
  - 字节码证实：第 1 个参数传给 `DefaultDomainApiClient(serverUrl, connectTimeout, readTimeout)`；参数 2–4 传给 `OAuth2PluginFactory.produceLopPlugin(...)`。按京东 LOP 惯例推断为 `(serverUrl, appKey, appSecret, accessToken)` —— **顺序待文档确认**
  - 调用：`<T extends AbstractResponse> T execute(DomainAbstractRequest<T>) throws LopException`
- **`com.lop.open.api.sdk.DefaultDomainApiClient`**：JdlClient 内部持有的 HTTP 执行器（`execute` / `uploadFile` / `downloadFile`）
- **`com.lop.open.api.sdk.LopClient` / `DefaultLopClient`**：通用 LOP 客户端（`execute(LopRequest<T>)`），非 ISC 专用
- **请求基类** `DomainAbstractRequest<T>`：`getApiMethod()`、`getDomain()`、`getDomainApiTypeCode()`（ISC 请求固定 = 1，对应 `EmDomainApiType`）、`buildDomainHttpParam(DefaultDomainApiClient)`、`getResponseClass()`
- **响应基类** `AbstractResponse`：`code` / `msg` / `zhDesc` / `enDesc` / `url`（LOP 层信封）

### 通用调用链（7 个能力一致）

```
JdlClient.execute(Integratedsupplychain*V1LopRequest)
  → DefaultDomainApiClient.execute(DomainAbstractRequest)
  → HTTP → Integratedsupplychain*V1LopResponse (getResponse()/getResult())
  → JdlApi*ResponseBase<T> 信封 { code, message, requestId, data }
  → 领域响应 DTO
```

请求构造模式：`new IntegratedsupplychainXxxV1LopRequest()` → `setRequest(领域Request)` → `client.execute(req)`。API 路径 = `getApiMethod()` 返回的 `"/integratedsupplychain/..."` 字符串，`getDomain()` = `"IntegratedSupplyChain"`。

---

## 2. 七个能力对照表

### ① 仓库查询 queryWarehouses —— ✅ 找到

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/basicinfo/warehouse/query/v1`（domain=IntegratedSupplyChain） |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService.queryWarehouseInfo.WarehouseQueryRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoWarehouseQueryV1LopResponse` |
| 响应类（领域） | `JdlApiListResponseBase<WarehouseEntity>`（`data: List<WarehouseEntity>`）；`WarehouseEntity`：warehouseNo, warehouseName, erpWarehouseNo, status, warehouseAddress(`Address`) |
| 关键字段（请求） | `ownerNo`（货主编码）、`warehouseNo`、`status`、`pin` |
| 调用链 | `JdlClient.execute(IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest)` → `DefaultDomainApiClient` |

### ② 商品查询 queryProducts —— ✅ 找到

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/basicinfo/goods/query/v1` |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryGoodsInfo.GoodsInfoQueryRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopResponse` |
| 响应类（领域） | `JdlApiListResponseBase<GoodsInfoResult>`（`data: List<GoodsInfoResult>`）；`GoodsInfoResult`：ownerNo + 9 个子对象（basicInfo(`GoodsBasicInfoResult`), goodsCategory, goodsSpecificationsInfo, management, attribute, storage, packing, industryAttributes, immediateFulfillment） |
| 关键字段（请求） | `ownerNo`、`erpGoodsNo`、`goodsNo`、`barCode`、`queryType`（取值待文档确认）、`pin`、`pageSize: Integer`、`currentPage: Integer`、`requestId` |
| 调用链 | 同 ①，换对应 Request/Response 类 |

### ③ 库存查询 queryStock —— ✅ 找到

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/stock/query/v1` |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockQueryV1LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryStock.StockQueryRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainStockQueryV1LopResponse` |
| 响应类（领域） | `JdlApiPageResponseBase<StockResult>`（分页信封，`data: JdlOpenPage<StockResult>`）；`JdlOpenPage<T>`：`totalNum: Integer` + `resultList: List<T>`；`StockResult`：ownerNo/ownerName, shopNo, sellerNo/sellerName, warehouseNo/warehouseName, goodsNo, erpGoodsNo, goodsName, erpGoodsSign, barCodes, goodsLevel, stockStatus, stockType, **stockNum, usableNum**, batchInfos(`BatchInfos`), logicalFactors(`LogicalFactors`) |
| 关键字段（请求） | `ownerNo`、`warehouseNo`、`stockIndexes`（库存维度索引，取值待文档确认）、`goodsNo`、`erpGoodsNo`、`goodsLevel`、`stockType`、`pin`、`returnZeroStock`、嵌套 `shopStock`/`logicalStock`/`warehouseStock`/`batchStock`/`batchInfos`、**`currentPage: String`、`pageSize: String`（⚠️ 字符串类型）** |
| 调用链 | 同 ① |

### ④ 创建销售出库 createOutboundOrder —— ✅ 找到（核心：So = Sales Order 销售出库单）

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/order/delivery/create/v1` |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.addSoOrder.SoCreateOrderRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopResponse` |
| 响应类（领域） | `JdlApiResponseBase<SoCreateResponse>`（`data: SoCreateResponse`）；`SoCreateResponse`：**`deliveryNo`（京东出库单号）、`erpDeliveryNo`** |
| 关键字段（请求） | 顶层：`sourceNo`, **`erpDeliveryNo`（客户出库单号，必填语义待文档确认）**, `orderType`, `warehouseNo`, `erpWarehouseNo`, `intoWarehouseNo`, `orderMark`, `logicalInventoryFactor`, `customerRemark`, `erpRemark`, `erpOrderType`, `erpOrderTypeName`, `pin`, `erpRelatedOrderNo`, `orderPrice: Double`, `deliveryPickupSync`, `extendProps: Map`；子对象：`channelInfo`(`ChannelInfo`), `customerInfo`(`CustomerInfo`: customerCode/ownerNo/shopNo), `receiverInfo`(`ReceiverInfo`: name/mobile/phone/email/postCode/province/city/county/town/detailAddress/addressAnalysis/customerNo/receiveCompany), `carrierInfo`, `addServices`, `industryAttributes`, `warehouseOperationRule`, `invoiceInfo`, `relatedOrders`, `pickupOrder`；明细：**`cargoInfos: List<CargoInfo>`**（goodsNo/erpGoodsNo/planQuantity: Integer/goodsLevel/orderLine/goodsName/unit/remark/price: Double/amount/payAmount/taxRate/type/batchInfos/serialNos/extendProps） |
| 调用链 | 同 ① |

### ⑤ 查询出库单 queryOutboundOrder —— ✅ 找到

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/order/delivery/query/v1` |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryQueryV1LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.querySoOrder.SoQueryRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryQueryV1LopResponse` |
| 响应类（领域） | `JdlApiResponseBase<SoQueryResponse>`（`data: SoQueryResponse`）；`SoQueryResponse` 核心：**`deliveryNo`, `erpDeliveryNo`, `status`, `isSplit`, `splitDeliveryNos`, `transType`, `expectDeliveryDate`, `orderWeight`, `warehouseNo/erpWarehouseNo`, `pinAccount`** + 各类子列表：`deliveryItemList`, `deliveryPackageList`, `deliveryStatusList`, `deliveryBatchItemList`, `deliveryProductInfoList`, `deliveryRejectItemList`, `deliveryBoxList`, `serialNoList` 及 receiver/customer/carrier/channel/addServices/afterSales 查询结果对象 |
| 关键字段（请求） | 三选一主键：**`deliveryNo` / `salesPlatformDeliveryNo` / `erpDeliveryNo`**；`ownerNo`、`pin`、`addressType: Integer`；**返回子集开关（Flag，Integer，1=返回？待文档确认）**：deliveryItemFlag / deliveryPackageFlag / deliveryStatusFlag / deliveryBatchItemFlag / deliveryProductInfoFlag / deliveryRejectItemFlag / deliveryRejectPictureUrlFlag / deliveryBoxFlag / deliverySerialNoFlag |
| 调用链 | 同 ① |

### ⑥ 取消出库单 cancelOutboundOrder —— ✅ 找到

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/order/cancel/v1` |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderCancelV1LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.cancelOrder.OrderCancelRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderCancelV1LopResponse` |
| 响应类（领域） | `JdlApiResponseBase<OrderCancelResponse>`（`data: OrderCancelResponse`）；`OrderCancelResponse`：`orderNo`, `erpOrderNo`, `resultType` |
| 关键字段（请求） | `ownerNo`、`erpOrderNo`（客户单号）、`orderNo`（京东单号）、`orderType`（必填语义待文档确认）、`pin` |
| 调用链 | 同 ① |

### ⑦ 运单查询 queryTracking —— ✅ 找到（语义为「订单/物流轨迹」，非单点运单状态）

| 项 | 值 |
|---|---|
| 服务名（LOP API） | `/integratedsupplychain/order/trace/query/v2` |
| 请求类（LOP 包装） | `com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderTraceQueryV2LopRequest` |
| 请求类（领域） | `com.lop.open.api.sdk.domain.IntegratedSupplyChain.OpenOrderTraceService.commonQueryOrderTrace.CommonOrderTraceRequest` |
| 响应类（LOP 包装） | `com.lop.open.api.sdk.response.IntegratedSupplyChain.IntegratedsupplychainOrderTraceQueryV2LopResponse` |
| 响应类（领域） | **`OpenOrderTraceService.commonQueryOrderTrace.Response<CommonOrderTraceResponse>`**（⚠️ 独立信封类，字段同 code/message/requestId/data）；`CommonOrderTraceResponse`：`orderTraceList: List<CommonOrderTrace>` + `relationWaybillInfo`(`RelationWaybillInfo`: readdressWaybillNos/reverseWaybillNos)；`CommonOrderTrace`：operateCode, operateRemark, operateTitle, **operateTime**, waybillNo, operateName, operateAddress |
| 关键字段（请求） | `pin`、`customerCode`、**`waybillNo`（运单号）**、`enterpriseOrderNo`、`warehouseOrderNo`、`scope: Integer`（查询范围，取值待文档确认） |
| 调用链 | 同 ①；**注意该响应的 LOP 包装 getter 是 `getResult()` 而非 `getResponse()`** |

---

## 3. 缺失 / 替代 / 注意点

### 能力覆盖
- 7 个能力**全部找到真实 API**，无缺失。运单查询（⑦）由「订单轨迹查询 v2」承担——返回的是轨迹列表（`orderTraceList`），不是单一运单状态对象；若 Demo 需要「运单状态单点查询」，该 API 是最接近的替代，或考虑城配轨迹：

| 替代 API | 服务名 | 领域请求类 |
|---|---|---|
| 城配轨迹（同城配送） | `/integratedsupplychain/order/citytrack/query/v1` | `JdlOpenPlatformTrajectoryService.queryCityTrack.CityTrackRequest` |
| 仓库覆盖范围查询 | `/integratedsupplychain/order/warehousecoverages/query/v1` | `JdlOpenPlatformGISService.queryWarehouseCoverages.WarehouseQueryRequest` |
| 可配送时效查询 | `/integratedsupplychain/order/deliverytime/query/v1` | `WaybillDeliveryTimeQueryService.queryDeliveryTime.WaybillDeliveryTimeRequest` |

### 封装 JDWarehouseService 时的坑（均由反编译证实）

1. **JdlClient 构造参数顺序**：字节码证实第 1 参 = serverUrl、2–4 参进 OAuth2 插件；`(serverUrl, appKey, appSecret, accessToken)` 是按惯例推断 —— **待文档确认**。
2. **分页字段类型不统一**：商品查询 `pageSize/currentPage` 是 `Integer`；库存查询 `pageSize/currentPage` 是 **`String`**。封装时需分别处理，不能共用一套分页参数类型。
3. **库存查询响应是分页信封** `JdlApiPageResponseBase<JdlOpenPage>`（totalNum/resultList）；仓库、商品查询是 `JdlApiListResponseBase`（List 直接装在 data）；其余是 `JdlApiResponseBase<T>`（单对象 data）。三套信封类是**各服务包各自拷贝的独立类**（全限定名不同），不能混用。
4. **出库单查询的 Flag 开关**：`SoQueryRequest` 有 9 个 `*Flag: Integer` 字段控制返回哪些子列表（明细/包裹/状态/批次/拒收/箱号/序列号等），不设置可能拿不到对应明细 —— 具体取值（0/1）**待文档确认**。
5. **字段取值语义未确认**（javap 无注解信息）：`queryType`、`stockIndexes`、`goodsLevel`、`stockType`、`orderType`、`status`、`scope`、`resultType` 等枚举/字典值 —— 全部 **待文档确认**；字段 JSON 序列化名也可能受 `@JSONField` 注解影响（javap 默认不显示注解，**待文档确认**）。
6. **几乎所有请求都带 `pin`**（京东用户 pin）和 `ownerNo`（货主编码），封装接口建议默认注入或作为必填参数。
7. **响应成功判定**：LOP 层 `AbstractResponse.code` 与领域层信封 `JdlApi*ResponseBase.code/message` 两层都要判；`requestId` 可用于链路追踪。
8. **trace 查询的 getter 不一致**：`IntegratedsupplychainOrderTraceQueryV2LopResponse` 用 `getResult()`，其他 6 个响应用 `getResponse()` —— 泛型封装取 data 时需特判。
9. 出库单创建主键是 `erpDeliveryNo`（客户单号），成功响应回传京东 `deliveryNo`；取消出库单按 `orderNo`（京东单号）或 `erpOrderNo`（客户单号）+ `orderType` 定位 —— 封装时建议保存两者的映射关系。
