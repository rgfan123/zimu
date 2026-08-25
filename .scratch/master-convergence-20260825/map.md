# master 收敛盘点 — 2026-08-25

一次针对「重复功能实现 / 功能遗忘」的全仓盘点，起因是主工作目录长期脏、
25+ 个 worktree 并行、Codex 与 Claude 多会话交叉推进。

## 仓库拓扑（容易踩的坑）

同一个 git 仓库里有**两条无共同祖先的历史**：

- `master` —— 真正的开发主干，`origin` = `rgfan123/zimu`。
- `main` / `origin/main` —— 内容是整个子牧项目被 vendored 成 `jry/` 子目录
  （1158 文件 / 57 MB），同步点 `fcf04d7`（2026-08-19）。

`jry/` 那份拷贝当时就已落后主干，盘点时 master 已领先同步点 **145 个提交**。
**它不是主干，改代码不要改到 `jry/` 里。**

## 结论一：重复实现，主干都是收敛后的版本

盘点前的直觉是「工作区有主干没有的新东西」，实测相反 ——
主干有 **213 个源文件**是 live 工作区完全没有的（整个 Agent 平台等），
反向只有 9 个。live 工作区不是领先，是落后 116 个提交。

几处典型的「同一功能两套实现」，主干一侧都是拆分收敛后的版本：

| 功能 | 工作区/旧分支 | 主干 |
|---|---|---|
| JD 出库服务 | 单体 1776 行 | Service 757 + Preparer 730 + Executor 217 + Audit 217 |
| 聚福宝拉取 | 接口内嵌 HTTP，265 行 | PullClient 28 + HttpPullClient 87 + SessionAdapter 300 + ShipmentGateway |
| 礼包管理 UI | Table 版 | Drawer/List 版（`53c8aa7`） |
| JD 发货计划抽取 | `codex/jd-shipment-submission-plan` 的 Plan + Preview | 同一重构的 Preparer/Executor 版 |

处理：这些旧实现一律**不回流**，以主干为准。

## 结论二：功能遗忘，4 项

| # | 项 | 状态 |
|---|---|---|
| 1 | 票 11 来源回填在线推送写路径（V34 是死 schema） | → 本目录 issues/01 |
| 2 | 中汇 PMS 上传前端（后端 7 端点前端零调用） | → 本目录 issues/02 |
| 3 | vitest 组件测试基建 | ✅ 已补回（`9778663`） |
| 4 | `docs/diagrams/管理层整体架构图.drawio` | ✅ 已补回（`9778663`） |

补 3 的过程中额外查出并修掉一个真 bug：主干 `MasterDataCrud` 在加载/错误态
`return stateContent ?? (...)` 整树 unmount，用户已输入的筛选条件与 allowClear
清除入口一起消失。移植回来的 `masterDataCrudFilters.test.tsx` 正是为此写的回归测试，
在主干上稳定复现失败，改为只替换表格区域后通过。

## 安全网

`snapshot/live-wip-20260825` —— live 工作区（`/Users/jerry/Documents/子牧`）
未提交 WIP 的完整快照，含 **22 个此前任何分支都没有的文件**：
中汇上传前端 4 个、JD 查询共享重构 3 个、vitest 基建、两个平台推送脚本等。
排除了 `outputs/` `exports/` 等生成物。

**01 / 02 票完成前不要删这个分支。**

## 分支清理

已删（内容 100% 在主干，patch-id 与逐文件内容双重核对，worktree 均干净）：

| 分支 | SHA | 恢复方式 |
|---|---|---|
| `codex/issue-105-dashboard-severity` | `685ea46` | `git branch <name> <sha>` |
| `codex/issue-107-shipping-workbench` | `4e62a16` | 同上 |
| `codex/issue-114-demo-auth` | `5ac965f` | 同上 |
| `codex/release-migration-compat-87c03ba` | `0058936` | 同上 |
| `codex/source-channel-52col` | `dae9bda` | 同上 |

已合入：
- `codex/agent-observability-129` 快进合并（token 计量看板 #129 + 企微业务卡片层 #87/#88）。
- `codex/jufubao-shipment-source-sync-20260824` → `299dd32`（#128 评审缺口修复，39 文件 +1367/-128，
  零冲突、不含迁移；补进收件人事实归一化、来源同步批次命令、导入客户身份等 7 个主干缺失文件）。

### 第二轮删除（2026-08-25，全部复验「master 缺失文件 = 0」后删）

| 分支 | SHA | 判定依据 |
|---|---|---|
| `codex/issue-111-recon-workbench` | `a4df74e` | 主干 `ade3d9a` 同主题 |
| `codex/issue-84-resend-snapshot` | `fefb928` | 主干 `4345182`；其 V50 是主干 **V49** 的改号旧副本，主干版还修正了 REMINDER 回填（`i.id < d.id`），严格更优 |
| `codex/issues-117-116-zhonghui` | `7521cd2` | 主干 `f883475` + `851181d` |
| `codex/issues-84-86-wecom-tracking` | `c43915c` | 主干 `788b7a6` + `4472ede` |
| `codex/issues-87-88-wecom-card` | `336ff31` | 主干 `2237ff6` |
| `codex/jd-shipment-submission-plan` | `9595f37` | **重复重构**：主干用 Preparer/Executor 做了同一件事 |
| `codex/jufubao-convergence-20260824-recovered` | `0e99be4` | 主干 `1b34370`；其 V53 与主干 V54 DDL 完全相同，只差改号说明 |
| `codex/jufubao-shipment-source-sync-20260824` | `2f1461f` | 已合入 `299dd32` |
| `codex/mixed-provider-bundle` | `f7c8382` | 主干 `53863b3` + `5de71c3` |
| `codex/product-search-emg-20260824` | `43d8581` | `findJdProviderSkuCodes` 已在 `ProviderSkuRepository.java:44`，唯一差异是一句注释措辞 |
| `codex/source-attribution-correction` | `dde64fd` | 主干 `3910c9e` |
| `codex/root-backend-wip-snapshot-20260822` | `9d5d1e9` | 被 `-complete` 覆盖 |
| `integration/agent-platform2` | `680513e` | 主干缺失文件 0 |

本地分支 29 → 13，worktree 24 → 10。恢复：`git branch <name> <sha>`。

### 明确保留、别删

- `snapshot/live-wip-20260825` —— 票 02 中汇上传前端 4 个文件的唯一副本。
- `codex/root-backend-wip-snapshot-20260822-complete` —— 票 01 `SourceReturnPushService` 的 cherry-pick 源。
- `codex/root-wip-live-20260822` —— **就是主工作目录 `/Users/jerry/Documents/子牧` 本身**，164 个未提交改动挂在上面。
- `codex/agent-observability-129` —— 有会话在 `/private/tmp/zimu-129` 上活跃推进。

## 待办 / 未处理

- 远端分支（`origin/codex/*`）一律未动，删远端需要单独确认。
- ~~`codex/issue-84-resend-snapshot` 的 V50 撞号~~ —— **此项作废**：查证后主干已把同一迁移
  收编为 `V49__wecom_export_delivery_generation_fencing.sql`，实现也齐（`FulfillmentExportWecomDeliveryFinalizer`
  的 `initial_generation` / `SUPERSEDED` / 按代际收窄关告警）。分支上的 V50 只是改号前旧副本，无需合并，已删。
- `jry/` 拷贝落后主干 145 个提交，是否继续维护这条线待定。
- live 工作区仍有 164 个脏文件与 20 MB 未跟踪生成物；`.gitignore` 已在 `048737a` 补齐
  （原先只写了 `output/` 单数，实际目录是 `outputs/`），live 分支合入主干后即可生效。
