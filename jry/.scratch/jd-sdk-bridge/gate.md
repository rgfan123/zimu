# 京东外部 gate — 只读接口权限验证记录

## 说明

本文件记录京东开放平台（ISC LOP）只读接口的权限验证状态，是 [spec.md](./spec.md)「外部 gate」约束的执行记录：**Mock 不冒充真实权限**，真实环境只读验证的唯一入口是显式探针 `JdReadOnlyUatProbe`。

探针运行后按下表逐行更新状态。状态列取值：**已开通** / **2001 未授权** / **缺参数**（尚未验证的接口保持「未验证」）。

## 验证方式

- 运行命令（推荐，自动加载 git-ignored 的 `backend/.env.jd.uat.local` 凭据并校验完整性）：
  `scripts/jd-readonly-uat.sh`
- 等价方式——探针文件名刻意**不以 `Test` 结尾**，普通测试套件（`mvn test`）不会触发，必须显式指定类名：
  `mvn -q -f backend/pom.xml -Dtest=JdReadOnlyUatProbe test`
- 前置环境变量：`JD_LOP_PIN`、`JD_LOP_OWNER_NO`、`JD_LOP_SERVER_URL`、`JD_LOP_APP_KEY`、`JD_LOP_APP_SECRET`、`JD_LOP_ACCESS_TOKEN`；缺失时探针拒绝运行，并一次性列出全部缺失键（不逐个短路报错）。
- 每个接口输出一行分类记录（只含存在性标记，不输出密钥、凭据或 PII）：
  `JD_READONLY_PROBE operation=<操作名> success=<bool> business_code=<业务码> request_id_present=<bool> data_present=<bool>`
- 分类口径：
  - **已开通**：`success=true`（业务码在 `0/200/1000/10000/SUCCESS` 集合内），接口可正常调用。
  - **2001 未授权**：`business_code=2001` 或等价未授权码，需在京东开放平台后台为该应用申请对应接口权限。
  - **缺参数**：业务码为缺参数类错误——探针固定传空参（`Map.of()`），只验证权限与连通性、不猜测单号；缺参数结论**不代表**权限未开通。

## 接口清单

| 域 | 操作名 | SDK 接口 | 状态 |
|---|---|---|---|
| 仓库 | queryOwners | IntegratedsupplychainBasicinfoOwnerQueryV1 | 已开通 |
| 仓库 | queryWarehouses | IntegratedsupplychainBasicinfoWarehouseQueryV1 | 已开通 |
| 仓库 | queryProducts | IntegratedsupplychainBasicinfoGoodsQueryV1 | 已开通 |
| 仓库 | queryStock | IntegratedsupplychainStockQueryV1 | 缺参数 |
| 仓库 | queryOutboundOrder | IntegratedsupplychainOrderDeliveryQueryV1 | 缺参数 |
| 仓库 | queryTracking | IntegratedsupplychainOrderTraceQueryV2 | 缺参数 |
| 基础信息 | queryCustomers | IntegratedsupplychainBasicinfoCustomerQueryV1 | 已开通 |
| 基础信息 | querySellers | IntegratedsupplychainBasicinfoSellerQueryV1 | 已开通 |
| 基础信息 | queryShops | IntegratedsupplychainBasicinfoShopQueryV1 | 已开通 |
| 基础信息 | queryShopGoods | IntegratedsupplychainBasicinfoShopgoodsQueryV1 | 已开通 |
| 基础信息 | querySuppliers | IntegratedsupplychainBasicinfoSupplierQueryV1 | 已开通 |
| 基础信息 | queryGoodsCategories | IntegratedsupplychainBasicinfoGoodscategoryQueryV1 | 已开通 |
| 基础信息 | queryWarehouseCoverages | IntegratedsupplychainOrderWarehousecoveragesQueryV1 | 缺参数 |
| 基础信息 | queryGoodsInfo | IntegratedsupplychainBasicinfoGoodsQueryV1 | 已开通 |
| 库存 | queryStockSnapshot | IntegratedsupplychainStocksnapshotQueryV1 | 已开通 |
| 库存 | queryStockSummary | IntegratedsupplychainStockmergeQueryV1 | 缺参数 |
| 库存 | queryBatchChange | IntegratedsupplychainStockBatchchangeQueryV1 | 已开通 |
| 库存 | queryGoodsLevelChange | IntegratedsupplychainStockLevelchangeQueryV1 | 已开通 |
| 库存 | queryShelfLifeGoods | IntegratedsupplychainStockShelflifegoodsQueryV1 | 缺参数 |
| 库存 | queryShelfLifeInventory | IntegratedsupplychainStockShelflifeinventoryQueryV1 | 缺参数 |
| 库存 | searchShopStockFlow | IntegratedsupplychainStockFlowShopstockQueryV1 | 缺参数 |
| 订单杂项 | queryOrderNosByPage | IntegratedsupplychainOrderQueryordernosbypageV1 | 缺参数 |
| 订单杂项 | queryAdjustment | IntegratedsupplychainOrderAdjustmentQueryV1 | 已开通 |
| 订单杂项 | queryDestroy | IntegratedsupplychainOrderDestroyQueryV1 | 缺参数 |
| 订单杂项 | queryException | IntegratedsupplychainOrderExceptionQueryV1 | 已开通 |
| 订单杂项 | queryPurchase | IntegratedsupplychainOrderPurchaseQueryV1 | 缺参数 |
| 订单杂项 | queryProcessed | IntegratedsupplychainOrderProcessedQueryV1 | 缺参数 |
| 订单杂项 | queryOperateRelation | IntegratedsupplychainOrderOperateRelationQueryV1 | 缺参数 |
| 订单杂项 | queryDeliveryTime | IntegratedsupplychainOrderDeliverytimeQueryV1 | 缺参数 |
| 订单杂项 | queryCityTrack | IntegratedsupplychainOrderCitytrackQueryV1 | 缺参数 |
| 序列号 | queryJdMallSerial | IntegratedsupplychainOrderSNQueryV1 | 缺参数 |
| 序列号 | querySerialByCondition | IntegratedsupplychainOrderSerialConditionQueryV1 | 缺参数 |
| 序列号 | querySerialFlow | IntegratedsupplychainOrderSerialFlowQueryV1 | 缺参数 |
| 序列号 | querySerialInside | IntegratedsupplychainOrderSerialInsideQueryV1 | 缺参数 |
| 退货退供 | queryRtwOrderList | IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1 | 缺参数 |
| 退货退供 | queryRtwOrderDetail | IntegratedsupplychainOrderReturntowarehouseQueryV1 | 缺参数 |
| 退货退供 | queryReturnToSupplier | IntegratedsupplychainOrderReturntosupplierQueryV1 | 缺参数 |

> 全表 37 行：已开通 15 行（含仓库域 queryOwners/queryWarehouses/queryProducts），缺参数 22 行。探针固定传空参，缺参数类错误证明请求已进入京东业务参数校验层（非权限拒绝）。

## 验证记录

- 2026-08-14 权限开通后全量重跑 `scripts/jd-readonly-uat.sh`（UAT 网关）：37 个接口，15 个已开通（`business_code=1000`，均带 requestId 与 data），22 个缺参数（`2000/2002/2003` 参数必填类，非权限拒绝）。事业部发现 `queryOwners` 成功；配置 `JD_LOP_OWNER_NO` 后第二阶段 `JD_PROBE_WAREHOUSES=true` 仓库查询 `queryWarehouses` 成功（`1000`），仓库域权限闭环。探针未打印/落盘任何凭据，未创建或取消出库单，未猜测真实单号。
