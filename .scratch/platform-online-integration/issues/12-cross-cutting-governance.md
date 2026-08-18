# 12 — 横切治理（凭据轮换 / golden 样本 / 失效演练 / 配置补齐）

**What to build:** 支撑在线接入长期运行的治理项：三平台凭据轮换机制（责任人 + 日程，HAR/凭据文件勿外传、定期改密）；每平台冻结一份真实响应 golden 样本（拉取时字段级校验用）；接口失效演练排期（改 endpoint → 验证告警 → 人工导表流程）；`.env.example` 补齐三平台配置项与 ingest/.gitignore 约定；D1 上传方式（A/B）与 D2 网关服务主体的最终落地记录。

**Blocked by:** None — can start immediately（与各票并行推进）

**Status:** ready-for-agent

- [ ] 凭据轮换日程与责任人已定，脚本从环境变量读取不变
- [ ] 三平台 golden 样本入库（脱敏），拉取校验引用
- [ ] 失效演练完成一次并记录结果
- [ ] .env.example / gitignore / 配置文档与最终 D1/D2 决策一致

---

## 合并修订（2026-08-18）

**D1 / D2 已随 Phase 0 出范围**：上传方式 A/B 与网关服务主体 `svc-platform-pull` 只服务 Phase 0 脚本上传通道，该通道已不交付，两项一并作废，本票不再需要记录其落地。

**`ingest/.gitignore` 一项同样出范围**（无 ingest 目录）。

保留并仍然成立的治理项：三平台凭据轮换（责任人 + 日程）、golden 样本、接口失效演练、`.env.example` 补齐三平台配置项（沿用 `app.jd.*` 的 env 注入模式）。

**新增一项**：`data-local/` 下现有三份 `*-credentials.txt` 明文密码与三个 `*.har`（含明文密码与当时有效的会话）——gitignore 有覆盖，但**无轮换日程、无责任人**，会话劫持面真实存在。本票要给出改密日程。

- [ ] `data-local/` 现存凭据与 HAR 的处置与改密日程已定并执行一次

## Comments

- 2026-08-18: 已落地 agent 部分：三平台 golden 样本（docs/research/golden/）、.env.example 三平台配置项、凭据轮换机制（docs/research/platform-credential-rotation.md，90 天日程）。D1/D2 随 Phase 0 出范围作废（合并 spec）。待办：失效演练（等任一 Connector 落地后执行）、D1/D2 决策记录归零。
