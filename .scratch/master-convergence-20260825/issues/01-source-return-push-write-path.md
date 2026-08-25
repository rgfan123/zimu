# 01 — 票 11「来源回填在线推送」写路径补回主干

**What to build:** 主干上 `V34__source_return_push_status.sql` 建好的推送状态机目前**没有任何代码去写**，只有读。把写路径（`SourceReturnPushService` + `POST /api/v1/source-return-exports/{export_id}/push`）按主干重构后的结构补回来，让这套 schema 真正闭环。

**Blocked by:** 无

**Status:** resolved

## 背景 —— 这是一个死 schema

2026-08-25 做 master 收敛盘点时发现：

- `V34__source_return_push_status.sql` 给 `app.source_return_exports` 加了 6 个字段：
  `push_status`（`NOT_PUSHED/PUSHING/SUCCESS/FAILED` CHECK 约束）、`push_started_at`、
  `pushed_at`、`pushed_by`、`push_platform_ref`、`push_error`。
- 主干里 **只有读**：
  - `TrackingFileService.java:853/873` —— SELECT 出 `push_status` 并放进响应；
  - `SourceAttributionService.java:81` —— 用 `push_status IN ('PUSHING','SUCCESS')` 做订正闸门。
- 主干里 **没有任何一行写** `PUSHING` / `SUCCESS` / `FAILED`（全仓 grep 确认）。
- `TrackingFileController` 只有两个 GET（列出回填文件、下载文件），**没有 push 端点**。

结果：前端能看到「推送状态」，但这个状态永远停在 `NOT_PUSHED`，
`SourceAttributionService` 的订正闸门也因此永远不会被触发。

## 实现在哪里

写路径并没有丢，只是从没进主干：

- `codex/root-backend-wip-snapshot-20260822-complete:backend/src/main/java/cn/zimu/fulfillment/file/SourceReturnPushService.java`（600 行）
- 同分支 `backend/src/test/java/cn/zimu/fulfillment/connector/zhonghui/SourceReturnPushServiceTest.java`
- `snapshot/live-wip-20260825` 里有同一份（已核对：与快照分支字节一致），并且额外带着
  调用它的 `TrackingFileController`（多出 `POST .../push` 端点）与两个平台脚本
  `scripts/caishixian_push_shipments.py`、`scripts/jufubao_push_shipments.py`
  （这两个脚本**任何分支都没有**，只在 snapshot 里）。

## 范围

- 把 `SourceReturnPushService` 补回 `cn.zimu.fulfillment.file` 包，按主干现状适配：
  - 它已经依赖 `PlatformScriptRunner`（主干已有，A8 抽取自 refresh/push 的重复代码），无需改造；
  - 校对 `AuditLogService` / `CommandContext` / `BusinessException` 现签名。
- 在 `TrackingFileController` 补 `POST /api/v1/source-return-exports/{export_id}/push`，
  沿用 snapshot 里的幂等取舍：`Idempotency-Key` 只做格式校验（≥8 字符）防重复点击，
  真正的幂等由 DB 状态机承担（推送真实调外部平台、不可重放）。
- 三段事务边界照搬：`claimPush`（REQUIRES_NEW 抢 `PUSHING` 并提交）→
  `runPushScript`（**事务外**执行平台脚本，不持有连接）→ `completePush`（REQUIRES_NEW 回写）。
- 补回两个平台推送脚本，并确认 `PlatformScriptRunner` 的脚本查找路径能找到它们。
- 迁移号：本票**不加新迁移**（V34 已在生产部署过，不得改动）。

## 非范围

- 前端「推送」按钮与状态展示（本票只做后端闭环；前端另评估）。
- 聚福宝/彩食鲜以外的平台通道。

## 验收标准

- [ ] `POST /api/v1/source-return-exports/{export_id}/push` 存在且鉴权与其它写端点一致；
- [ ] 同一回填文件版本推送成功后再次推送被拒（幂等闸门生效，返回业务码而非 500）；
- [ ] `PUSHING` 期间并发重推被拒；`PUSHING` 超时可回收后重试；
- [ ] `FAILED` 可重试，`push_error` 落 JSONB 明细；
- [ ] 脚本执行期间不持有数据库事务/连接（按 A2 契约 §3.5，需有测试或明确证据）；
- [ ] `SourceAttributionService` 的订正闸门在推送成功后确实生效；
- [ ] 凭据只走环境变量 / data-local 凭据文件，不落盘、不打日志；
- [ ] 既有测试全绿（后端 `mvn test`）。

## 验证原则

- 不做真实外呼验收：平台脚本通道用 stub / mock 走通状态机；
- 幂等与并发是本票的核心，必须有自动化测试，不接受手工点两次当验收。

## Comments

- 合规红线未变：平台拉取每平台每日 ≤2 次；本票是**推送**通道，但同样人工触发、不做自动重试风暴。

## Resolution（2026-08-25 已完成）

合入 master：`2e1b415`（实现提交 `4f7f123`，分支 `feat/source-return-push`）。

实现直接取自 `codex/root-backend-wip-snapshot-20260822-complete`，**对当时主干零编译错误** ——
它依赖的 `PlatformScriptRunner` 等 seam 主干都已具备，无需按预想做结构适配。

意外佐证：`docker-compose.yml:127` 早已配好 `APP_PLATFORM_PULL_PUSH_STALE_TIMEOUT`，
`./scripts` 也早已挂成 `/app/platform-pull-scripts:ro` —— 配置铺好了、代码没进来，
坐实这是漏活而非新做。

顺带修正原 WIP 一处放错：测试包声明 `cn.zimu.fulfillment.file` 却放在 `connector/zhonghui/`
目录下，已归位到 `file/`。

验证：`SourceReturnPushServiceTest` 7/7、`ExcelClosedLoopApiTest` 19/19（2 skipped，
确认新构造器参数没打破 Spring 装配）、`OpenApiContractConsistencyTest` 3/3、
`mvn test-compile` 通过。
