# Wayfinder 本地 Markdown 跟踪器

本仓库没有 issue tracker，wayfinder 地图以本地 Markdown 表达。

## 约定

- **地图**：`wayfinder/map.md`（frontmatter `label: wayfinder:map`）。地图是索引，不是仓库——决策只存在于各自的票里。
- **票**：`wayfinder/tickets/<slug>.md`，每张票是地图的子项（无原生父子关系，靠 `parent: wayfinder:map` frontmatter 标识）。
- **标签**：frontmatter `label: wayfinder:<type>`，type ∈ `research` / `prototype` / `grilling` / `task`。
- **阻塞**：frontmatter `blocked_by: [票名列表]`（Markdown 无原生依赖，用正文/元数据约定）。
- **前沿（frontier）**：open 且所有 blocker 已 closed 且无人认领（`claimed_by` 为空）的票。
- **认领**：开工前先把 `claimed_by` 写为自己（"first, before any work"），并发会话以此跳过。

## 一张票的生命周期

1. 创建：`wayfinder/tickets/<slug>.md`，body 是 `## Question`（决策/调查问题）。
2. 认领：`claimed_by: <session>`。
3. 解决：HITL 票与用户对话；AFK 票独立完成。产物作为资产链接到票（`## Assets`），不粘贴进 body。
4. 记录并关闭：把结论写进票的 `## Resolution`，`status: closed`，并在地图 `Decisions so far` 追加一行 `- [票名](链接) — 一句话结论`。
5. 新票：resolve 后创建新浮现的票（create-then-wire），把已可精确化的雾区从地图 `Not yet specified` 毕业为票；超出终点的关闭并记入地图 `Out of scope`。

## 一次会话只解决一张票

例外：research 票（AFK 子代理并行解决）。
