# Runbook — 把 子牧 移出 iCloud 同步盘（根治 git/Claude 卡顿）

**为什么**：`~/Documents` 开了 iCloud「桌面与文稿」同步，项目文件被驱逐成 dataless；
每次 `git status`（Claude Code 启动会跑）都强制 git 逐个下载文件而卡住。移到非同步目录（如 `~/dev`）根治。

**破坏面盘点（已核实）**：
- ✅ `docker-compose.yml` 全用相对挂载（`./docker`、`./scripts`、`./data-local`），且 `name: zimu-fulfillment` 钉死项目名 → 移动后运行中的容器不受影响，未来 compose 命令 `cd` 到新路径即可。
- ✅ `.mcp.json` 走 `docker compose exec`，与位置无关。
- ⚠️ 44 个 git worktree 的 `.git` 指针指向 `子牧/.git/worktrees/<name>`（旧绝对路径）→ 移动后需 `git worktree repair`。
  - 内部：`子牧/.claude/worktrees/*`（随主目录一起移动）
  - 外部：`~/Documents/Codex/2026-08-*/zimu-*` 与 `~/Documents/.ccg/子牧/*`（**这些也在 iCloud 下，同样有卡顿，考虑一并迁**）
- ⚠️ 当前有 Claude/Codex 会话运行在 `子牧/.claude/worktrees/` 内 → `mv` 前必须全部关闭。

---

## 执行顺序（严格按序，第 1 步是数据安全前提）

### 步骤 0 — 等文件全部实体化（**不做会丢数据**）

dataless 文件的完整内容只在云端。移出 iCloud 前必须先下载全部内容，否则移出后云端可能判定「已移除」丢掉未下载内容。

```bash
# 在 Finder 里：右键 ~/Documents/子牧 → 现在下载（Download Now），等到没有云朵图标/进度
# 或命令行确认已无 dataless（返回 0 才安全）：
find ~/Documents/子牧 -path '*/.claude/worktrees' -prune -o -type f -print0 \
  | xargs -0 ls -lO 2>/dev/null | grep -c dataless
```
> iCloud 下载很慢，这一步可能要挂很久。可以放着让它跑，别中断。
> `.claude/worktrees`（3.6G，多为陈旧 worktree）可以先不下载——见步骤 5 的清理建议。

### 步骤 1 — 关闭所有跑在 子牧 里的会话

关掉所有 cwd 在 `~/Documents/子牧`（含其 worktree）内的 Claude Code / Codex / 编辑器 / 终端。
**docker 容器可以继续运行**，不用停（也**不要** `docker compose down -v`）。

### 步骤 2 — 移动（同一 APFS 卷，是秒级 rename，不触发下载）

```bash
mkdir -p ~/dev
mv ~/Documents/子牧 ~/dev/子牧
```

### 步骤 3 — 修 worktree 指针

```bash
cd ~/dev/子牧
git worktree repair                      # 修内部 worktree 的回指
git worktree list | awk '{print $1}' | while read wt; do
  [ -d "$wt" ] && git -C "$wt" worktree repair 2>/dev/null
done
git worktree prune                       # 清掉 /private/tmp 下已失效的 prunable 项
```

### 步骤 4 — 验证

```bash
cd ~/dev/子牧
time git status --porcelain | head        # 应为秒级
git fsmonitor--daemon start 2>/dev/null; git fsmonitor--daemon status
cd ~/dev/子牧 && docker compose ps         # name=zimu-fulfillment 钉死,应仍看到运行中的栈
```

### 步骤 5 —（可选但推荐）清理陈旧 worktree，省 3.6G

```bash
cd ~/dev/子牧
git worktree list                         # 看哪些还需要
# 对已合并/废弃的：
git worktree remove <path>                # 或 git worktree remove --force <path>
```
`dsh-h04-static-analysis`(1.2G) 等 dsh-* 若已完成可优先删。

### 步骤 6 — 更新其它工具对旧路径的引用

- Claude Code / Codex 的项目书签、最近打开列表：重新在 `~/dev/子牧` 打开。
- 若某处脚本硬编码了 `~/Documents/子牧`，全局搜一下：`grep -rl "Documents/子牧" ~/dev/子牧/scripts`（已确认 compose 与 mcp 无此问题）。
- `~/Documents/Codex/*` 与 `~/Documents/.ccg/子牧/*` 的外部 worktree：若也想脱离 iCloud，同法移到 `~/dev/` 下并 `git worktree repair`。

---

## 回滚

`mv ~/dev/子牧 ~/Documents/子牧` 再 `git worktree repair` 即可退回原位（但会退回 iCloud 同步盘、卡顿复现）。

## 备选（不移动，只止血）

系统设置 → Apple ID → iCloud → 「优化 Mac 存储」关闭：文件不再被驱逐、留在本地，项目仍在 iCloud 备份。
改动最小，但项目仍受 iCloud 同步开销影响，且占满本地磁盘。本 runbook 选的是彻底移出方案。
