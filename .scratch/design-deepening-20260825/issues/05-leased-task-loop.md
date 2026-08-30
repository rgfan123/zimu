# 05 — LeasedTaskLoop：8 个异步 worker 的循环骨架归一（P2）

**What to build:** 把 8 个 worker 手写的 poll → suppress → claim → process →
succeed/fail/recover 骨架收进一个 `LeasedTaskLoop`（或模板基类），把事故性分叉
变成显式配置。

**Blocked by:** 无
**Status:** ready-for-agent

## 背景

8 个 worker 各自手写同一循环：`connector/wecom/card/WecomBusinessCardWorker`、
`order/card/OrderDraftCardWorker`、`file/FulfillmentExportWecomWorker`、
`file/WecomTrackingFileWorker`、`notification/WecomBusinessNotificationWorker`、
`message/InterpretationWorker`、`agent/AgentDefinitionWorker`、`agent/QualityEvalWorker`。

事故性分叉（无设计理由）：仅 3 个在空 claim 后重置 claimSuppressUntil；仅 3 个有
drain ExecutorService + @PreDestroy + releaseForShutdown；lease 下限 60/30/2400/无
四种；仅 OrderDraftCardWorker 处理 `FINALIZING`。`message/AsyncTaskStore` 487 行
18 个公开方法，没有调用方用超过 4 个——店面比店还大。

## 范围

1. `LeasedTaskLoop`：接口 = 任务类型 + 处理函数 + 显式配置（lease 下限、suppress
   策略、是否 drain、终态钩子）；骨架内实现 claim/renew/recover/shutdown 一份；
2. 8 个 worker 改为声明式装配；分叉逐项判性质：是 bug 的（如不重置 suppress）
   统一修掉并记录，是真需求的进配置；
3. `AsyncTaskStore` 收窄公开面：骨架用的方法降为包私有，公开面只留 enqueue/查询；
4. 测试：骨架一套接口级测试（含 lease 到期恢复、suppress、shutdown drain），
   替代各 worker 里重复的循环碎片测试。

## 验收

8 个 worker 无手写 while-claim 循环；AsyncTaskStore 公开方法 ≤6；
分叉清单（bug 修复 vs 配置化）在票评论中逐项记录。
