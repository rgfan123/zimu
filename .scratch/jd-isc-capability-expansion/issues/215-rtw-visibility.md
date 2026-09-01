# 215: 退货入库（RTW）只读可见性——渠道退货回仓看得见、对得上

**What to build:** 运营能在系统里看到京东仓的退货入库单（RTW）列表与明细
（`queryRtwOrderList` / `queryRtwOrderDetail`），按时间段/单号筛选，看到实收商品
（goodsNo/数量/批次），并能把一张 RTW 单关联回本地发货单或渠道订单（人工关联即可，
第一期不做自动匹配）。退货回仓从此有账可查——今天这条链路完全在京东后台，库存
「凭空回补」在本地无解释。

**上下文：**
- 防腐层已就绪：`JDReturnService.queryRtwOrderList` / `queryRtwOrderDetail`
  （`connector/jd/returns/`，只读作业面 REST 已存在），无业务落库/展示。
- 库存快照（queryStockSnapshot）已接入库存决策：退货回补是快照数量变化的主要
  未解释来源之一；RTW 可见性直接改善库存决策的可解释性。
- 彩食鲜渠道有退货场景；手工单渠道的客户拒收也走 RTW 回仓。
- 写侧 `orderReturntowarehouseCreate`（主动建退货入库单）本期不做——建单目前
  由京东/渠道侧发起，见 map.md 暂不接入清单。

**文件锚点：**
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/returns/JDReturnService.java`（seam）
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/returns/JdReturnClient.java`（REAL 实现）
- `backend/src/main/java/cn/zimu/fulfillment/recon/OutboundReconService.java`（对账模式参考）

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 可按时间段拉取 RTW 单列表并落库（幂等 upsert），明细含实收商品与数量
- [ ] 运营界面可浏览/筛选 RTW 单，明细页展示商品行与批次信息
- [ ] RTW 单可人工关联到本地发货单/渠道订单，关联动作留审计
- [ ] PII 口径与其他 JD 边界一致：寄件人/联系人字段不落库不展示
- [ ] MOCK 模式全链路可演示；REAL 拉取失败可观测且不阻塞其他任务
