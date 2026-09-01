# ADR 0019: 京东外部单号使用独占命名空间并在写前查重

- 状态：已采纳
- 日期：2026-08-31
- 关联：#209

## 背景

原实现把每日 12 位本地 `shipments.outbound_order_no` 直接作为京东 `erpDeliveryNo`。
该流水只由当前数据库维护；数据库恢复、重建或另一套实例可能重新生成京东历史中已存在的号码。
京东官方约束要求商家单号在事业部下唯一，因此本库唯一键不能单独证明外部唯一。

## 决策

1. `shipments.outbound_order_no` 继续作为不可变的本地/文件导出号，不重写历史。
2. 新京东外部号由数据库分配为
   `ZIMU-SO-yyyyMMdd-12位全局流水-8位随机熵`，先写入
   `shipment_jd_outbounds.erp_delivery_no` 的唯一键；`sync_status=NONE` 表示仅保留号码。
3. addSoOrder 写意图落盘前，使用所选履约方同一组 `pin + ownerNo` 调用同事业部 querySoOrder。
   仅官方 `2342` 或成功空响应代表可用；已有记录则在安全状态下换号并重新查询；
   超时、权限错误或异常响应均失败关闭。
4. `business_facts_hash` 保存剔除可替换 `erpDeliveryNo` 后的业务事实；明确未产生外部效果的失败可在
   事实未漂移时换号。V103 前旧记录先用原精确 `request_hash` 验证，再清除旧哈希并换号。
   一旦状态可能已产生外部效果（SUBMITTING 或不确定失败），号码冻结，只允许按原号对账。
5. 通用 HTTP `order/so-create` 与旧 `JDWarehouseService.createOutboundOrder` 保持关闭；
   `orderSoCreate(Map)` 从通用 `JdWriteOpsService` 移除。实际 addSoOrder 适配器只接受 Shipment
   编排包从已预检计划生成的 `PreparedJdSalesOutbound` capability，并再次校验 `ZIMU-SO-*`。

## 结果

本库唯一约束解决同库并发；独占前缀与随机熵降低跨实例碰撞；同租户外部查重覆盖数据库恢复和
历史号碰撞。外部号和本地导出号不再互相冒充，运单轮询与对账均使用已提交的 `erpDeliveryNo`。
