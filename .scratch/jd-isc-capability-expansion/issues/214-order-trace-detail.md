# 214: 承运商轨迹明细接入——妥投证据与渠道回传升级

**What to build:** 对已回填运单号的发货单，系统能拉取并落库京东订单轨迹明细
（`queryTracking`，LOP `/integratedsupplychain/order/trace/query/v2`，按 waybillNo 或
warehouseOrderNo 查询），在发货单详情页展示「揽收→分拣→派送→妥投」时间线，并把关键节点
（揽收/妥投时间）纳入渠道回传与客服可查数据。当前妥投判定只有 querySoOrder 的
deliveryStatusList 快照，轨迹明细是缺失的第二证据链。

**上下文：**
- 防腐层已就绪：`JDWarehouseService.queryTracking`（commonQueryOrderTrace），
  只读作业面 `GET /api/v1/jd-warehouse/tracking` 已可手工调用，但无业务落库/展示。
- 运单回填链路（ShipmentJdTrackingBackfill + ShipmentJdTrackingPoller）已通，
  回填产物是 waybillNo——正好是轨迹查询的输入。
- 彩食鲜等渠道回传目前只回运单号；轨迹节点（尤其妥投时间）是渠道对账和客诉处理的高频问题。
- PII 注意：轨迹响应含派送员姓名/电话，HTTP 边界已有统一剔除口径
  （JdWarehouseController.personalField），业务落库时同样不得存联系人字段。

**文件锚点：**
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/JdWarehouseClient.java`（queryTracking）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdTrackingPoller.java`（轮询宿主）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShippingProgress.java`（进度建模）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/SourceFollowupProgressService.java`（渠道回传）

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 有运单号的发货单可按需/定时拉取轨迹明细并落库（幂等，重复拉取不产生重复节点）
- [ ] 发货单详情能看到轨迹时间线，含妥投节点与时间
- [ ] 轨迹落库与展示不含承运/派送人员姓名、电话等个人字段（有测试断言）
- [ ] 渠道回传数据结构可携带揽收/妥投时间（回传开关与渠道白名单沿用现有机制）
- [ ] 京东查询失败（无轨迹/单号无效）不污染发货单状态，错误可观测
