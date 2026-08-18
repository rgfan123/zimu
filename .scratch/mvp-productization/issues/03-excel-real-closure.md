# 03 — Excel 真实闭环剩余验收

Type: development-test
Status: resolved
Blocked by: 01 — 人工复核解决闭环
Claimed by: /root/review_frontend_excel_spec

**What to build:** 在不猜测客户/SKU 映射的前提下，验证真实来源文件、明确主数据确认、履约导出、运单回传和来源回填的可操作闭环。

- [x] 三种来源文件的上传、血缘和问题行在公共 HTTP/UI 可查。
- [x] 未确认映射留在 ReviewCase，确认后可继续生成严格分履约方的文件。
- [x] 运单整批回传和三来源回填保持原容器/编码/列结构。
- [x] 多 Shipment 首批自动回填、后续批次转复核有可重复验收证据。
- [x] 部分发货可从履约详情显式创建续发 Shipment/独立第三方导出，具备剩余量、幂等和版本门禁。

## Answer

已完成文件作业纵切：三来源上传与逐行血缘、人工确认映射后恢复同批次导出、第三方 tracking 整批接收和原格式来源回填均通过公共 HTTP 暴露。复核可分步处理，但只有订单的所有阻断项均关闭后才原子推进；已导出的原始行与幂等重放不会重复生成文件。

多 Shipment 首批部分发货会创建唯一开放的 `MULTI_SHIPMENT_SOURCE_FOLLOWUP`，进行中保持 `WAITING_PROVIDER`/`PROCUREMENT_IN_PROGRESS`；累计实发加取消量到终局且每个实际 Shipment 都有 Tracking 后才进入 `NEED_REVIEW`，最后必须由人工 `complete-source-followup` 关闭。来源回填始终取最小 `shipment_sequence`，历史上出现多 Shipment 后永不伪造 `is_final=true`。

新增正式 `POST /api/v1/fulfillments/{fulfillment_id}/continuation-exports`：仅允许 BUSINESS、THIRD_PARTY、PARTIALLY_SHIPPED，校验版本、正数、未分配剩余量及幂等键，并生成新 Shipment/出库单号/独立导出。履约详情提供“创建续发批次”入口。tracking 的业务统计按当前回传批次计算；同导出相同文件重放返回首次完整响应及原来源回填 ID。

## Validation

- 后端 `mvn -q -DskipTests -Dmaven.compiler.useIncrementalCompilation=false test-compile` 通过；`ExcelClosedLoopApiTest` 现有 14 个公共 HTTP/真实文件/golden 用例。新增的复核恢复与双 Shipment 用例曾 2/2 通过；补充 exact replay 后的独占重跑进一步发现并修复了 PostgreSQL JDBC 不能直接绑定 `Instant` 的 500，已改为 UTC `OffsetDateTime`。因共享 `target` 持续被外部 Maven 并发污染，最终完整绿色矩阵交由根代理在独立临时快照复跑。
- 前端 `npm test` 17/17、`npm run typecheck`、`npm run build` 通过；按钮仅在 BUSINESS 查询页的 THIRD_PARTY + PARTIALLY_SHIPPED 任务显示，并提交当前 version、数量、备注、幂等头与操作人。
- 双 Shipment HTTP 场景覆盖：首批 PARTIAL、续发幂等重放、第二批按本批 `SHIPPED` 统计、tracking 文件内容重放返回同一完整响应、累计终局、两版首批-only 非 final 回填、人工完成后订单 CLOSED 与 `MANUAL_SOURCE_FOLLOWUP_COMPLETED`。

## Remaining gates

- 仍缺京东官方 tracking golden；JD tracking 上传继续显式返回 `JD_TRACKING_TEMPLATE_GATE`。
- 三份真实来源订单中尚未由用户确认的客户/source SKU 映射继续进入 ReviewCase；真实样表证明解析与留痕，不冒充全自动出库。
