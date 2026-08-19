# 12 — 读端点 + /internal 只读镜像

**What to build:** 管理 REST 的读面（12 决策）：① /api（Basic Auth）读端点——`GET /api/agents`（列表：slug/name/status/enabled/version）、`/api/agents/{slug}`（详情）、`/api/agents/{slug}/versions`（版本历史）、`/api/agents/{slug}/versions/{version}/eval-cases`（评测用例查看）、`GET /api/agent-runs`（过滤 run_id/slug/时间范围/outcome/run_mode）+ `/{runId}`（详情含工具调用序列）；DTO record 直映射 + 校验；② `/internal`（服务身份）只读镜像——agent-runs 查询 + agents 列表/详情/版本历史，无任何写端点；③ 与 13 的轮询复用关系：agent-runs 端点即 202 任务轮询面。

**Blocked by:** 02 — 注册表切 DB 真源；03 — INVARIANT 评测数据化（设计源：meta-agent-platform 票 12）。

**Status:** ready-for-agent

- [ ] 读端点契约测试（过滤条件生效、字段投影正确、无 PII/凭据外泄）
- [ ] /internal 镜像只读（无写端点）；Basic Auth 与 internal-auth 各自鉴权正确
- [ ] agent-runs 详情含工具调用序列（可支撑 202 任务轮询展示）
