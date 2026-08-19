# 09 — QUALITY 链路：promptfoo 执行器 + 异步评测

**What to build:** QUALITY 指标评测（07 后半 + 11 中间路线）：① promptfoo YAML 生成器——由 `agent_eval_cases` 的 QUALITY 用例 + 定义 system_prompt 生成（deepseek provider + llm-rubric/javascript 断言，密钥走环境变量，绝不入 DB/日志/产物）；② 执行器——`ProcessBuilder` 跑 `npx promptfoo eval`（本地/CI 形态），结果回写 DB 比对；③ 异步任务（Spring Worker 模式）跑 QUALITY，`run_mode=PREVIEW` 落 `agent_runs`（03 已加列），不污染 LIVE 统计与 09 基线；④ 与 09 CI 门禁分工：CI 只钉 INVARIANT（03），QUALITY 是参考指标。

**Blocked by:** 03 — INVARIANT 评测数据化；05 — B/C 路径收敛（QUALITY 跑真实模型需 Adapter 运行路径）（设计源：meta-agent-platform 票 07、11）。

**Status:** ready-for-agent

- [ ] 生成的 YAML 可被 promptfoo eval 消费（可用样例冒烟）；密钥只经环境变量
- [ ] 结果回写 DB 可查（按 run_id / 用例关联）；PREVIEW 不污染 LIVE 与基线
- [ ] 异步任务失败不阻断草稿确认（参考指标语义）；与 CI 门禁分工文档化
- [ ] 单测覆盖 YAML 生成与结果解析（执行器可 mock）
