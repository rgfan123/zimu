# ADR 0020: 京东建单成功必须立即读回并匹配冻结事实

- 状态：已采纳
- 日期：2026-08-31
- 关联：#210

## 背景

`addSoOrder` 返回成功只能证明京东接受了请求，不能证明返回的出库单确实属于本次 Shipment。
如果外部商家单号发生历史碰撞、租户配置漂移或京东返回旧单，单看 `deliveryNo` 就落库会把另一张
订单的运单和货品归到当前客户名下。后续普通轮询无法修正这个归属错误，反而会继续传播到来源回传。

## 决策

1. 在任何 `addSoOrder` 前，`shipment_jd_outbounds` 的 `SUBMITTING` 意图同时冻结本次
   `pin`、`ownerNo`、`warehouseNo` 与按 `orderLine + goodsNo + planQuantity` 表示的非 PII
   货品快照；外部调用成功后才补 `submitted_at`。
2. `addSoOrder` 成功且返回稳定的 `erpDeliveryNo + deliveryNo` 后，立即使用同一冻结
   `pin + ownerNo` 调用 `querySoOrder`，请求货品与状态事实。
3. 只有远端 `erpDeliveryNo`、`deliveryNo`、`customerInfo.ownerNo`、
   `warehouseNo` 和完整货品集合全部精确匹配，才原子完成 `SUBMITTED`、业务阶段、事件与版本。
   货品比较不依赖返回顺序，但拒绝缺行、多行、重复行、非整数数量和任一字段漂移。
   `pinAccount` 的官方语义是可选的“下单人（操作人）”，不是请求 `pin` 的回显，因此不拿它
   判断租户；同租户边界由冻结 pin 发起查询并由返回 `customerInfo.ownerNo` 复核。
4. 查询失败、官方未找到、响应格式不完整、审计失败或任一事实不一致，都保持外部效果未决并写入
   `RECONCILIATION_REQUIRED`。该状态不进入普通运单轮询，也不允许第二次 `addSoOrder`；后续重试
   只能按原 `erpDeliveryNo` 走同一个严格事实核验器。
5. 建单命令的幂等摘要只包含稳定的 Shipment 命令身份，不包含可漂移的 Provider 配置、
   Shipment 版本或派生请求哈希。派生事实的防漂移职责属于已落库的冻结写意图；因此同一幂等键
   在配置漂移后仍可进入 query-only 对账，但任何冻结事实缺失或不一致仍会在查询前失败关闭。
6. 运单轮询与人工出库对账也使用冻结的提交时 pin/owner，而不是进程当前配置，避免配置轮换后跨租户查询。
   审计只记录核验状态和不匹配字段名，不保存 querySoOrder 原始响应、pin/owner 值或收件人 PII。

## 结果

`SUBMITTED` 从“京东返回成功”提升为“京东事实已按本次冻结意图读回确认”。成功响应返回旧货品时，
系统会隔离该结果而不是强绑运单；查询暂时不可见也不会自动再建一单。V104 之前没有冻结 pin 的历史
记录保持 `NULL` 并失败关闭，不能用当前配置猜测历史租户。
