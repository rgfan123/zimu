# scan-hardening: 05 — MEDIUM 级 backlog（汇总，不单独立票）

**Status**: draft。按顺手原则捎带修复，不阻塞任何交付。

1. **单线程 @Scheduled 调度器共用**：未配 `spring.task.scheduling.pool.size` 也无自定义 TaskScheduler，
   `ShipmentJdTrackingPoller`（开启后单轮最多 200 个同步京东查询）会顶掉 `InterpretationWorker`（500ms 轮询）
   的调度窗口。修：ThreadPoolTaskScheduler pool size ≥ 任务数。⚠️ 用户已拍板要开 tracking-backfill，
   开之前建议先修这条（FulfillmentHubApplication.java:8）。
2. **审计/告警写入失败静默吞没且零日志**：IdempotencyService.markFailed:404-423、
   FulfillmentStockDecisionService:437-495、OrderDraftService:693-715、TrackingDraftService:509-529 的
   `catch (RuntimeException ignored)` 无 log.warn——审计链断裂不可观测。修：catch 块补 log.warn。
3. **采购回执缺乐观锁版本校验**：`ProcurementService.doReceipt`（:80-129）无 `version()` 检查，
   与同服务 retry/cancel 不对称；行锁兜底了数据正确性，但用户感知不到「已被他人处理」。
   修：ProcurementReceiptInput 补 expectedVersion。
4. **京东收货地址候选接口无分页**：`receiverAddressCandidates`（ShipmentJdOutboundService.java:276-338）
   缺省 import_batch_id 时全量返回。修：默认分页或强制参数。
5. **中汇 uploadOne 宽泛 catch 无日志**：ZhonghuiPmsBatchUploadService:200-207 把 NPE 也降级为
   「上传失败请稍后重试」，全文件无 Logger。修：补日志区分业务失败与编程错误。
6. **京东出库「外部成功但本地漂移」无自动收敛**：ShipmentJdOutboundService.completeSubmit:583-598 的
   RECONCILIATION_REQUIRED 可能死循环，只能人工介入且无专用动作。修：给该态加显式人工确认 API 或至少告警 SLA。
   （与 zimu-workbench 对账台相邻，可在 #111/#112 之后一并考虑。）
7. **测试盲区**：ProcurementController `/retry`、`/cancel-remaining` 两个写端点零覆盖（状态机最复杂路径）；
   频控并发、租约接管场景无回归测试（已并入 02/03 票的验收项）。
