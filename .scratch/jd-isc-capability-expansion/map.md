# 京东 ISC SDK 能力扩展 — 票据地图（2026-09-01）

来源：对 `backend/libs/IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar` 的实证枚举
（59 个 LOP 操作）× 防腐层现状（`cn.zimu.fulfillment.connector.jd` 下 59 个操作全部有包装）
× 业务用例现状（仅 5 个操作真正接入业务流）。

## 现状基线（实证）

**已接入业务流的操作（5 个）：**

| 操作 | 业务用例 |
|---|---|
| `orderSoCreate`（addSoOrder） | ShipmentJdOutboundExecutor 出库单提交 |
| `queryOutboundOrder`（querySoOrder） | 运单回填 / 出库对账（TrackingBackfill、OutboundRecon） |
| `queryStock` | 提交前实时库存复查（ShipmentJdStockCheck） |
| `queryStockSnapshot` | 库存决策快照（FulfillmentStockDecision） |
| `queryGoodsInfo` | SKU 条码凭证核验（JdGoodsReadOnlyVerifier / JdSkuMappingCheck） |

其余 54 个操作：防腐层包装 + 通用只读作业面 REST 端点已存在，但无业务用例。
写操作集中在 `JdWriteOpsService`（HTTP 层 `app.jd.write-mode` 默认 OFF 锁死）。

## 本批票（值得接入，按依赖序）

| 票 | 标题 | Blocked by |
|---|---|---|
| [213](issues/213-outbound-cancel.md) | 出库单取消接入 | 无 |
| [214](issues/214-order-trace-detail.md) | 承运商轨迹明细接入 | 无 |
| [215](issues/215-rtw-visibility.md) | 退货入库（RTW）只读可见性 | 无 |
| [216](issues/216-exception-order-watch.md) | 仓内异常单主动发现 | 无 |
| [217](issues/217-po-inbound-loop.md) | 采购入库单闭环 | 无 |
| [218](issues/218-batch-shelflife-stock.md) | 批次/效期库存明细接入 | 无 |

注：编号 213–218 为预期 GitHub issue 号（rgfan123/zimu 现有最大 #212）；
按 `docs/agents/issue-tracker.md` 约定，正式发布应经 `gh issue create` 落到 GitHub，
届时以 GitHub 实际分配号为准并在本目录补 `**GitHub:** <url>` 链接。

## 暂不接入（有包装、无票，理由如下）

- **基础信息写面**（customerCreate / goodsCreate / goodsUpdateBySellerGoodsSign /
  supplierCreate / shopCreate / shopGoodsCreate / logicalinventoryfactorCreate /
  stockShopstockfixedSet）：主数据源头在本系统 + 京东后台人工维护；写面低频高险，
  write-mode 默认锁死是有意设计，无业务诉求前不开。
- **序列号域**（queryJdMallSerial / querySerialByCondition / querySerialFlow /
  querySerialInside / serialnumberCreate / boxandserialnumberTransport）：
  生鲜食品业务无序列号/串码管理需求。
- **加工域**（orderProcessedCreate / queryProcessed / processedCreate 配方）：
  当前无仓内加工业务；若未来做礼包组套仓内组装再立票。
- **销毁单**（orderDestroyCreate / queryDestroy）与**调整单写**（orderAdjustmentCreate）：
  低频高险，京东后台人工操作足够；只读 queryAdjustment 已在作业面可查。
- **退供 RTS**（orderReturntosupplierCreate / queryReturnToSupplier）：
  子牧自有货权，无「退回供应商」链路。
- **GIS 仓库覆盖 / 配送时效 / 同城轨迹**（queryWarehouseCoverages / queryDeliveryTime /
  queryCityTrack）：单仓发全国，非同城业务，暂无履约时效 SLA 需求。
- **配送指令修改**（orderOperateCommandModify）：拦截场景由 #213 取消覆盖；改配低频人工处理。
- **订单号分页 / 作业关联 / 单据主档查询**（queryOrderNosByPage / queryOperateRelation /
  queryOwners / queryWarehouses / querySellers / queryShops / queryShopGoods /
  querySuppliers / queryCustomers / queryGoodsCategories / queryStockSummary /
  searchShopStockFlow / queryShelfLifeGoods）：诊断/对账用途，连接器只读作业面
  REST 端点已可直接使用，无需再包业务用例。
