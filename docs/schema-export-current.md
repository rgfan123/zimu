# 数据库结构导出说明（DDL 交接基线）

配套文件：[`docs/schema-export-current.sql`](schema-export-current.sql)

本文件说明 2026-08-17 从活库导出的权威 DDL 交接基线：导出方式、覆盖范围、领域分组、与既有文档的关系，以及开发人员接手后如何基于它工作。

## 1. 文件是什么

`schema-export-current.sql` 是 **当前活库 `fulfillment_hub` 的真实结构**（`app` schema 全量），用 `pg_dump` schema-only 方式生成：

```
pg_dump --schema-only --schema=app --no-owner --no-privileges --no-comments
```

它不是文档草稿，而是从正在运行的系统直接抽出的、可执行的建库脚本。开发人员拿它对齐环境，看到的每一张表都真实存在。

### 生成环境

| 项 | 值 |
|---|---|
| 生成时间 | 2026-08-17 |
| 来源 | docker compose stack `zimu-fulfillment` 的 postgres 容器 |
| PostgreSQL | 16.x |
| 数据库 | `fulfillment_hub`，schema `app` |
| 方式 | `pg_dump --schema-only --schema=app`（已剥离 owner/权限/注释与 `\restrict` 指令） |

## 2. 对象清单（app schema）

| 对象 | 数量 |
|---|---|
| 业务表 `CREATE TABLE` | 53 |
| 操作视图 `CREATE VIEW` | 1（`v_order_progress_summary`） |
| 函数 `CREATE FUNCTION` | 36 |
| 触发器 `CREATE TRIGGER` | 64 |
| 索引 `CREATE INDEX` | 93 |

注意：`analytics` 分析 schema 的 4 个视图**不在此导出内**（`--schema=app` 限定）。需要分析库结构时，对 `analytics` 另行导出。

## 3. 表按领域分组

导出的 53 张表可归入以下领域（与 `docs/schema.md` §3 的分组对齐，含其未收录的消息链路组）：

### 3.1 客户、商品与履约方主数据（10）

`customers`、`customer_source_refs`、`categories`、`products`、`fulfillment_providers`、`skus`、`sku_aliases`、`source_channel_skus`、`provider_skus`、`provider_stock_snapshots`

### 3.2 Excel 接入与 CanonicalOrder（6）

`import_batches`、`raw_import_rows`、`orders`、`order_lines`、`order_line_components`、`order_versions`

### 3.3 履约、发货与采购（10）

`fulfillments`、`shipments`、`shipment_items`、`shipment_jd_outbounds`、`trackings`、`shipment_syncs`、`procurement_tickets`、`procurement_ticket_items`、`procurement_receipts`、`procurement_receipt_items`

### 3.4 文件输出与回填（4）

`fulfillment_exports`、`fulfillment_export_items`、`source_return_exports`、`source_return_export_items`

### 3.5 运营、审计与接入（8）

`review_cases`、`operational_alerts`、`connector_configs`、`channel_messages`、`audit_logs`、`demo_runs`、`idempotency_registry`、`outbound_number_counters`

### 3.6 事件时间线（2）

`order_event_types`、`order_events`

### 3.7 渠道消息、草稿复核与后台任务（13）——本次新增

`channel_identities`、`message_submissions`、`message_interpretations`、`message_media`、`wecom_events`、`order_drafts`、`order_draft_lines`、`provider_tracking_drafts`、`async_tasks`、`agent_runs`、`agent_tool_calls`、`carrier_prefix_mapping_sets`、`carrier_prefix_mappings`

消息链路血缘：`channel_messages`（原始证据）→ `message_submissions`（提交）→ `message_interpretations`（版本化解释）→ `order_drafts` / `provider_tracking_drafts`（草稿复核）。`message_media` 只存媒体证据（受控存储，不参与解释）；`async_tasks` 承载 Worker 任务，靠 `idempotency_key` 幂等收敛。

## 4. 为什么有这份文件（背景）

系统演进过程中新增了企业微信消息链路（接收 → AI 解释 → 草稿复核 → 确认），连带产生渠道消息、草稿、媒体证据、后台任务、Agent 运行记录等表。这些表**尚未写入 `docs/schema.md` 权威文档**，文档与实际结构存在版本差。为了给开发人员一份「与线上完全一致」的交接基线，直接从活库导出本文件，并同步更新了 `docs/schema.md`：

- `docs/schema.md`：表总数由 48 → 53，新增 §3.7 收录 13 张未记录表（职责 + 关键约束）。
- `docs/schema-export-current.sql`：本次导出的完整 DDL，以它为准。

## 5. 开发人员怎么用

1. **对齐本地环境**：用本文件建一个干净库，确认结构与目标一致：

   ```bash
   psql "$YOUR_DB_URL" -f docs/schema-export-current.sql
   ```

   注意：文件首部包含 `CREATE SCHEMA app`，若目标库已存在 `app` schema 会报重复，可先建空库再执行。

2. **结构核对**：对照 §3 分组，逐领域确认本组要开发的表已存在且字段/约束符合预期。

3. **与代码的关系**：Flyway 迁移（`backend/src/main/resources/db/migration/`）是结构与实现的历史路径；本文件是**当前态**快照，不替代迁移文件。开发时若新增表，应走 Flyway 新版本迁移，而不是改本文件。

4. **后续同步**：下一次需要交接基线时，用同一命令重新导出并覆盖 `docs/schema-export-current.sql`；若表数或领域分组变化，同步更新本说明 §2/§3 与 `docs/schema.md`。

## 6. 常见问题

- **为什么只有 53 张表，schema.md 之前写 48？** 文档过期。本次已把 `docs/schema.md` 更新为 53，并补齐了消息链路等新表。
- **analytics 视图在哪？** 在 `analytics` schema，本导出未包含；需要时单独对 `analytics` 执行 `pg_dump --schema-only --schema=analytics`。
- **函数/触发器为什么这么多？** 只追加表（如订单版本、审计、媒体证据）的写保护、状态机一致性约束等由 DB 触发器承载，属既有设计，不是本次新增。
