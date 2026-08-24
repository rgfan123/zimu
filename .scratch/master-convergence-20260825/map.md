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

已合入：`codex/agent-observability-129` 快进合并（token 计量看板 #129 + 企微业务卡片层 #87/#88）。

## 待办 / 未处理

- 远端分支（`origin/codex/*`）一律未动，删远端需要单独确认。
- `codex/issue-84-resend-snapshot` 带 `V50__wecom_export_delivery_generation_fencing.sql`，
  而主干 V50 已被 `V50__zhonghui_pms_stable_upload_intent.sql` 占用 —— **合并前必须先化解撞号**
  （参考 `66e6faf` 化解 V53 撞号的做法）。
- `jry/` 拷贝落后主干 145 个提交，是否继续维护这条线待定。
- live 工作区仍有 164 个脏文件与 20 MB 未跟踪生成物；`.gitignore` 已在 `048737a` 补齐
  （原先只写了 `output/` 单数，实际目录是 `outputs/`），live 分支合入主干后即可生效。
