# 213: 出库单取消接入——错单/渠道取消可在仓内截停

**What to build:** 操作员对一张已提交（SUBMITTED）但仓内尚未出库的京东出库单发起取消：
系统调用京东取消接口（`cancelOutboundOrder`，LOP `/integratedsupplychain/order/cancel/v1`），
按京东返回结果推进本地出库同步状态（取消成功→终态 CANCELLED；京东拒绝→保留原状态并给出
仓内作业阶段原因），全程留审计。渠道订单取消、错误提交（如 2026-08-30 的 erpDeliveryNo
碰撞事故 ESL00000025540305777）都能在货未出仓前止损，而不是打电话找京东。

**上下文：**
- 防腐层已就绪：`JDWarehouseService.cancelOutboundOrder`（REAL 实现在 `JdWarehouseClient`），
  但无任何业务调用方——今天取消只能走京东后台人工。
- 出库状态机在 `shipment_jd_outbounds.sync_status`（NONE/SUBMITTING/SUBMITTED/SYNC_FAILED），
  尚无取消态；需要 spec 阶段决定终态建模（新 sync_status 值 vs 独立字段）。
- 写门闩语义：取消是写操作，但目前 cancel 在 `JDWarehouseService` seam 而非
  `JdWriteOpsService`，不受 `app.jd.write-mode` 锁；接入业务用例时必须补授权口径
  （对齐 `JD_OUTBOUND_AUTHORIZED_OPERATORS` 的操作人白名单）。
- 京东侧约束：拣货下架后（状态 10016+）通常不可取消，需把京东拒绝码翻译成人话。

**文件锚点：**
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/JDWarehouseService.java`（seam）
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/JdWarehouseClient.java`（REAL 实现）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdOutboundService.java`（状态机宿主）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdOutboundAuditService.java`（审计口径）

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] SUBMITTED 出库单可由授权操作员发起取消，成功后本地状态推进为取消终态且不再被轮询/回填触碰
- [ ] 京东拒绝取消（已出库/已拣货）时本地状态不变，错误码+人话原因落审计并回显给操作员
- [ ] 未授权操作员/非 SUBMITTED 状态发起取消被拒绝（fail-closed），有负例测试
- [ ] 取消动作全程审计（请求/响应/操作人），审计记录可在现有审计查询面检索
- [ ] MOCK 模式下前端/接口全链路可演示，REAL 模式行为由集成测试或生产演练验证

## 关联既有票

- #209（单号跨写入方防碰撞）/ #210（提交后读回核验）：本票的取消能力是它们的止损配套——碰撞/不一致被发现后需要能撤下错误提交。
- #211（货品冲突停止轮询）：2026-08-30 订单38 碰撞事故（202608300002 被京东后台人工单抢占）是三票共同的现实案例。
