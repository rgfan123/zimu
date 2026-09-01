# 216: 仓内异常单主动发现——异常从「京东后台惊喜」变成系统告警

**What to build:** 系统定时拉取京东异常订单列表（`queryException`，LOP
`/integratedsupplychain/order/exception/query/v1`），把与本方 ownerNo 相关的异常单
（缺货、破损、拦截失败、超期未出库等）落库并产生待办：能自动关联到本地出库单的，
挂到对应发货单上提示运营；关联不上的进入独立异常清单。运营不再靠京东后台巡检
或客户投诉才知道仓内出了问题。

**上下文：**
- 防腐层已就绪：`JdOrderService.queryException`（`connector/jd/order/`），
  只读作业面 REST 已存在，无轮询/落库/告警。
- 现有轮询基建可复用：ShipmentJdTrackingPoller 的 @Scheduled lane 模式、
  attempt/backoff 字段建模（shipment_jd_outbounds.tracking_*）。
- 与 tracking CONFLICT 复核流互补：CONFLICT 是「我们发现京东不对」，异常单是
  「京东主动说它自己不对」——两者都应汇入人工复核入口。
- 本次 ESL00000025540305777 事故说明：出库链路的异常信号越早浮出，止损窗口越大。

**文件锚点：**
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/order/JdOrderService.java`（seam）
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/order/JdOrderClient.java`（REAL 实现）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdTrackingPoller.java`（轮询模式参考）

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 定时拉取异常单并幂等落库，可按异常类型/时间/处理状态筛选
- [ ] 能按单号自动关联到本地出库单的异常，在发货单上可见并计入待办
- [ ] 关联不上的异常进入独立清单，支持人工标记已处理（留审计）
- [ ] 拉取失败有限重试并可观测，不阻塞既有轮询 lane（pool-size 约束内新增 lane）
- [ ] MOCK 模式可演示完整闭环
