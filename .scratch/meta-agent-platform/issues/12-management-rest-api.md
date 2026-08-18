# 12 — 管理 REST API 端点与权限设计

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** 03 ✅、04 ✅、06 ✅、07 ✅、08 ✅（设计输入全部落定）

## Question

一期交付面主体：Agent 平台的管理 REST API。已确认决策（直接采用，不再重问）：

- **定义域**（03/06/08）：`agent_definitions` 唯一真源 + 版本链，draft→active→retired 无回边；草稿→人工确认→启用；active 版本不可改（要改=新版本草稿）；`enabled`（启停）与 `status` 正交；`allow_write` 布尔；确认动作记录 activated_by/activated_at；审计只做流水。
- **评测域**（07）：`agent_eval_cases` 绑定 (agent_slug, agent_version)，PENDING/CONFIRMED 联动确认；metric_kind INVARIANT/QUALITY。
- **Meta-Agent**（06）：slug=meta-agent 由 V30 播种，变更只能人工经本 API（草稿→确认），全工具禁改。
- **运行域**（04/08）：`agent_runs` 加 run_mode（LIVE/PREVIEW）+ intent/provider；outcome 维度 SUCCESS/NEEDS_INPUT/REJECTED/FAILED；stdio MCP 面一期只读。
- **权限**：沿用既有 Basic Auth（/api）+ internal-auth（/internal）体系，无新多租户/角色体系（Out of scope）。

待决策点（grilling，一次一个，带推荐答案）：

1. **端点清单与分组**：定义 CRUD（草稿列表/详情/创建草稿(人工)/确认/拒绝/启停/版本历史/回滚预览）、评测用例管理（列表/补用例→新版本草稿）、运行记录查询（agent_runs 按 run_id/slug/时间范围/outcome 过滤）、Meta-Agent 调用端点（自然语言→草稿）——哪些进 /api（Basic Auth）哪些进 /internal（服务身份）。
2. **DTO 形态**：AgentDefinitionDto / DraftConfirmCommand / AgentRunQuery 等的字段与校验；与 03 表字段、04 outcome、08 allow_write 的映射。
3. **确认/拒绝动作的幂等与并发**：同一草稿并发确认、确认后再次确认、拒绝后恢复——语义（参考 MCP 写工具幂等模式）。
4. **回滚 UI 语义**：03 已定回滚=复制成新草稿再确认——API 是否需要「rollback」便捷端点（复制 active 为 draft）还是完全由 create/update 表达。
5. **Meta-Agent 调用端点**：同步返回草稿结果 vs 异步任务（跑 INVARIANT 门禁 + 异步 QUALITY，07 已定两级）——响应形态（202 + 查询端点？）。
