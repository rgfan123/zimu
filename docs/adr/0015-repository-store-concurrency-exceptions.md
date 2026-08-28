# ADR 0015：@Repository store 的预期并发异常绕开持久化异常翻译

- 日期：2026-08-28
- 状态：已接受
- 决策者：Jerry（授权存量收敛）、Claude

## 背景

仓库 9 个 JdbcTemplate store 都标注 `@Repository`，因此被 Spring 持久化异常翻译代理
（`PersistenceExceptionTranslationPostProcessor`）包裹；又因 classpath 上有 spring-data-jpa，
实际生效的翻译器是 `EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible`——它把逃出
代理的 `IllegalStateException` / `IllegalArgumentException` 一律改写成
`InvalidDataAccessApiUsageException`（字面意思：「你把持久化 API 用错了」）。

后果在 2026-08-28 修 SourceSyncAutoWorker 时暴露：

1. 多实例租约竞争这种**正常并发结果**在日志与告警里长得像持久化缺陷；
2. 调用方无法按类型区分「租约没了（正常竞争，安静让位）」与「真缺陷（必须修）」；
3. 测试只能用 `hasRootCauseInstanceOf(IllegalStateException.class)` 绕行断言，
   等于在测试里固化了这次翻译。

## 决策

新增抽象基类 `cn.zimu.fulfillment.common.persistence.ConcurrencyConflictException`
（直接继承 RuntimeException：翻译器不认识就不改写，事务回滚语义不变），各 store 以嵌套子类
携带自己的语义与上下文：

| 子类 | 抛出场景 |
|---|---|
| `SourceSyncAutoStateStore.LeaseLostException` | 自动回传调度租约被接管（首例，改挂基类） |
| `AsyncTaskStore.LeaseLostException` | `succeedOwned` / `failTerminal` / `recordFailureOwned` / `finalizeFailedOwned` 四处 owned 写落空；followup 两个应用服务（Approval / DraftApplication）的栅栏 LOST_LEASE 分支同样抛它，链路端到端同型 |
| `JdbcWecomNotificationStore.LeaseLostException` | `finishBatch` 两分支的 owned 收口落空 |
| `WecomOrderDraftCardEventStore.ClaimConflictException` | `complete` / `recordUpdateOutcome` / `requireToken`：claim 令牌被轮换、结果已被先到的投递写入 |
| `BusinessFollowUpCardEventStore.ClaimConflictException` | 同上 |

**判据**：只有「多实例并发下的预期落空」归入本家族。以下故意保留 ISE / IAE——它们一旦触发
就是真缺陷或调用错误，被翻译成 `InvalidDataAccessApiUsageException` 反而语义相称：

- 两个卡片 store `startAttempt` / `start` 的锁内 CAS（`claim()` 已 `FOR UPDATE` 锁行，
  不可能输给并发，触发即锁假设被破坏，代码处有注释）；
- 「插入后行必须存在」防御（`was not persisted`、`delivery disappeared`、
  `batch insert returned no id`）；
- 数据损坏（`AgentEvalCaseRepository` 的 JSONB 解析、`invalid notification summary`）、
  行不存在（`履约导出不存在`）与入参校验 IAE。

每个转换点都有走完整 Spring 代理 + 真实 Postgres（Testcontainers）的**精确类型断言**测试钉住
（`isInstanceOf(...)` 而非 `hasRootCauseInstanceOf(...)`），任何一处退回 ISE 立刻变红。

## 后果

- Worker 侧从此可以 `catch (AsyncTaskStore.LeaseLostException)` 等按类型分流，把正常竞争降级为
  info 日志（`SourceSyncAutoWorker.poll` 已是范例）；存量 worker 的泛
  `catch (RuntimeException)` 行为不变，可渐进迁移。
- HTTP 层不受影响：转换点全部在 worker 路径，`GlobalExceptionHandler` 没有 ISE 专项映射。
- 新写 store 时遵循同一判据：预期并发结果用本家族，防御性不变量保持 ISE。
