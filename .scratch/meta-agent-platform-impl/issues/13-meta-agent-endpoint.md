# 13 — Meta-Agent REST 端点（202 闭环）

**What to build:** 自然语言创建 Agent 的完整闭环（12 决策 5 + 06）：`POST /api/meta-agent/run`——自然语言输入 → 202 异步任务（任务 = Meta 运行：list_agent_tools 工具发现 + create/update_agent_draft 建草稿 + 静态门禁 + INVARIANT stub 评测闭环，`run_mode=PREVIEW`）；NEEDS_INPUT（澄清问题）/ REJECTED 结果完整返回；轮询复用 `GET /api/agent-runs/{runId}`（12）；QUALITY 评测按 09 在草稿创建后另起异步链路（不阻塞确认）；与人工建草稿（11）两入口行为一致。

**Blocked by:** 10 — Meta-Agent 工具面；11 — 异步任务基建 + 定义域写端点（设计源：meta-agent-platform 票 06、12）。

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/14

- [ ] 自然语言 → 草稿全链路：202 → 轮询 → 草稿 JSON（含门禁结果、建议用例 PENDING 记录）
- [ ] 澄清路径（信息不足）返回 NEEDS_INPUT + 澄清问题；冲突 slug 拒绝不改名
- [ ] 两入口（人工建草稿 / meta-agent run）任务形态一致；审计与观测完整
- [ ] 端到端演示可用：自然语言建 Agent → 确认 → 上线运行（配合 02/11 端点）
