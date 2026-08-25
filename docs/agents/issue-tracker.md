# Issue tracker: GitHub Issues

**本仓库的 issue/spec 跟踪已迁移到 GitHub Issues（repo: `rgfan123/zimu`，私有）。**

## 约定

- **新 issue 一律开在 GitHub**：https://github.com/rgfan123/zimu/issues
  - 用 `gh issue create -R rgfan123/zimu --title "..." --body-file <file>` 或网页创建
  - 标题前缀建议 `<effort-slug>: <标题>`（如 `platform-online-integration: 10 — 健康检查与监控`），便于按 effort 过滤
  - issue 正文保留原本地票结构：`What to build` / `Blocked by` / checkboxes / `Status` 语义映射到 GitHub 的 open/closed
- **`.scratch/<effort>/` 目录降级为历史档案**：
  - 已迁移的票在本地文件头部保留一行 `**GitHub:** <url>` 指向对应 issue，之后以 GitHub 为准
  - 已完成（resolved / closed / wontfix）的票只留在 `.scratch/` 作为审计记录，不再更新
- **状态语义**（历史票）：`ready-for-agent` / `claimed` / `resolved` / `closed` / `wontfix` / `blocked-external` / `needs-user`。GitHub 侧用 open/closed + label（`jry`、`wayfinder:*`）表达。

## Skill 操作

- 发布新 issue：`gh issue create`（见上），并把 issue URL 记入对应 effort 的 map.md 或票文件
- 取一张票：`gh issue view <number> -R rgfan123/zimu`
- 更新状态：`gh issue close/edit -R rgfan123/zimu`
- 迁移历史票到 GitHub：复制 `.scratch/<effort>/issues/NN-<slug>.md` 全文为 issue body，在本地文件加 `**GitHub:**` 行后即视为迁移完成

## 迁移记录（2026-08-19）

- meta-agent-platform-impl T01–T13 已在 GitHub（#2–#14，label `jry`），本地文件补标 `**GitHub:** #N`
- 其余 15 张未完成票迁移为 #21–#35（manual-review-ready-for-human 02/03/04、mcp-order-fulfillment 04、platform-online-integration 10–16、wecom-fulfillment-send 01、mvp-productization 09、wecom-long-connection 07、wecom-message-intake 13）
- 全量核对（24 个 effort、120+ 票）已完成：已完成票保持本地 resolved 归档，未迁移
