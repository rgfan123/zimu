# 12 — 补齐保留策略与运行可见性

**What to build:** 系统管理员能够看到企微消息链路的积压和最终失败，媒体在重启后仍可用，非业务数据按可配置期限安全清理，而订单、运单和开放复核相关证据不会被误删。

**Blocked by:** 03 — 异步解释消息并完成基础意图分流; 07 — 接收图片并形成可复核订单草稿; 10 — 处理批量与部分发货回传.

**Status:** resolved

**Claimed by:** root → zed-agent (2026-08-14 实现：/api/v1/admin/message-pipeline 摘要与筛选 + NON_BUSINESS 保留清理 + compose 媒体持久化卷；新增 9 个测试全绿)

- [x] 管理后台提供任务积压、重试中、最终失败和媒体失败的筛选与摘要，详情不泄露秘密或完整敏感载荷。
- [x] 最终失败只在后台可见和告警，不向原企微群补发任何处理消息。
- [x] 内容寻址媒体存储通过持久化 Docker 卷运行，服务重启后原图仍能从受权接口读取。
- [x] 企业微信、模型与 MCP 秘密只从秘密配置注入，不进入数据库业务字段、API 响应、审计 payload 或日志。
- [x] `NON_BUSINESS` 消息默认保留 30 天且期限可配置；清理任务可重复运行并记录审计摘要。
- [x] 存在订单、运单、开放 ReviewCase 或业务审计引用的消息/媒体不得被清理，相关证据沿用业务保留周期。
- [x] 清理和状态查询遵守数据权限，列表默认只显示最小必要摘要。
- [x] 公共验收覆盖重启持久化、积压统计、最终失败、到期清理、保留门禁、重复清理和秘密不泄露。

## Answer

### 实现要点

**新增运行可见性端点**（`MessagePipelineOperationsController` → `/api/v1/admin/message-pipeline`，网关 Basic Auth + X-Operator 强制）：

- `GET /summary`：积压（PENDING+RUNNING）、重试中（PENDING 且 attempts≥1）、最终失败（FAILED+FINALIZING）、媒体失败（message_media FAILED）计数 + 保留期限配置。
- `GET /tasks?scope=BACKLOG|RETRYING|FINAL_FAILURES|ALL&status=`：任务筛选列表，`last_error` 经 `MessagePublicProjectionSanitizer.stableFailureCode` 收敛为稳定错误码；不投影 payload_ref / lease_owner。
- `GET /media-failures?status=`：媒体失败筛选（默认 FAILED），投影只含 id / channel_media_id / media_type / download_status / attempts / created_at——不含 source_url（一次性下载凭据）、failure_reason（原始异常文本）、content_ref。
- `POST /cleanup`：手动触发保留清理，返回运行报告并写审计摘要。

**保留清理**（`MessageRetentionCleanupService` + `MessageRetentionProperties`，`app.message-retention.non-business-days` 默认 30 天可配置，`cron` 默认每日 03:30 可配置；非正数天数整体禁用）：

- 只清理「最新解释意图 = NON_BUSINESS、到达保留期限、且无任何业务引用」的提交及其消息、媒体与孤儿媒体文件。
- 保留门禁：存在订单草稿、运单草稿、任何 ReviewCase（含终态，作为审计证据）或 audit_logs JSON payload 中的 submission_id 引用（如重新解释审计）一律保留；同一条消息被其他提交引用也保留。
- 媒体文件按内容寻址语义只删除不再被任何 message_media 行引用的文件；删除只发生在受控目录内。
- 幂等可重复：重复运行无候选时只记零值审计摘要；每次运行（定时 SYSTEM / 手动 HUMAN）写 `message-pipeline.retention / retention.cleanup / RETENTION_CLEANUP_DONE` 审计。
- 数据权限：所有端点走 X-Operator + 网关 Basic Auth；列表最小必要投影；清理只作用于 BUSINESS 数据域并记录 DataScope.BUSINESS 审计。

**docker-compose.yml**（最小改动，仅 backend 服务 + volumes 段）：新增 `APP_MEDIA_DIR=/var/lib/zimu-fulfillment/media` 与 `app-media-data` 命名卷挂载；媒体受控存储随命名卷持久化，服务/容器重启后原图仍可从受权接口（MessageMediaContentService）读取。`docker compose config --quiet` 通过。

**秘密不泄露**：既有 sanitizer 模式沿用——任务列表 last_error 收敛为稳定错误码；媒体失败列表不投影下载凭据/失败原文；清理审计 payload 只含计数与期限；既有 V20 清洗后的公共投影不变量保持，新增断言覆盖。

### 新增/修改文件

- 新增：`backend/src/main/java/cn/zimu/fulfillment/message/MessagePipelineOperationsController.java`、`MessagePipelineQueryService.java`、`MessageRetentionCleanupService.java`、`MessageRetentionProperties.java`、`MessagePipelineSummaryDto.java`、`MessageMediaFailureDto.java`、`RetentionCleanupReport.java`
- 修改：`backend/src/main/resources/application.yml`（message-retention 配置段）、`docker-compose.yml`（媒体持久化卷）
- 测试：`backend/src/test/java/cn/zimu/fulfillment/message/MessagePipelineOperationsApiTest.java`（7 个验收）、`backend/src/test/java/cn/zimu/fulfillment/connector/wecom/WecomMediaFileStoreRestartPersistenceTest.java`（2 个重启持久化）

### 验证

- 新增 9 个测试全绿；`cn.zimu.fulfillment.message.*` 与 `cn.zimu.fulfillment.connector.wecom.*` 全套 17 个测试类全绿。
- `mvn -DskipTests compile` 通过；`docker compose config --quiet` 通过。
- 全量 493 测试中 12 failures + 123 errors 全部位于并行 agent 在建的 MCP 模块（McpReadTools/McpWriteTools bean 定义冲突与 McpProtocolAcceptanceTest 自身断言），与本票改动无关；MCP 收口后全量可回绿。
