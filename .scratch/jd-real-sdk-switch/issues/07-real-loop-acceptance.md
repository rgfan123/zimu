# 07 — 真实链路端到端验收

**Type:** verification

**What to build:** 用一笔明确指定的真实订单，把「彩食鲜表格进 → 真实京东出库单 → 真实运单号 → 彩食鲜格式回填表」完整跑通一次，并留下可复核的证据。这是唯一能证明切换成立的凭据，Mock 成功不能替代。

**Blocked by:** 06 — 运单自动回填与彩食鲜格式回填表产出

**Status:** ready-for-agent

- [ ] 验收前由用户明确授权，并指定测试订单、目标仓、预期副作用与取消/处置方案；不得擅自对真实订单建单。
- [ ] 覆盖完整链路：导入 → 统一化 → 真实 `addSoOrder` → 真实运单取回 → 彩食鲜格式回填表下载。
- [ ] 记录真实业务码、请求 ID 与京东侧单号；证据中不含凭据与个人信息。
- [ ] 验证幂等：重放不产生第二张真实出库单。
- [ ] 验证回填表内容与来源表格逐行对应，单号与快递公司标识正确。
- [ ] 演练一次失败路径（如库存不足或校验不通过），确认不留半截业务批次。
- [ ] 按处置方案清理测试单据，并在 `.scratch/jd-real-sdk-switch/` 下留存验收记录。
- [ ] 记录未覆盖项与已知限制，不以「跑通一笔」代替全量投产结论。

## Comments

- 2026-08-17（01-06 全部 resolved 后，用户决定暂不授权真实建单，本票留给运营/对接人执行）：
  - **代码侧已就绪**：全量后端测试 739/739 通过（含 05 SDK 路由、06 自动回填与回填表产出的跨票端到端闭环）；前端 171/171 + build 通过。Mock 模式下「导入确认 → 自动建单 → 自动取回运单 → 下载彩食鲜回填表」链路可完整演示。
  - **验收前置（需外部提供）**：
    1. 真实凭据：`JD_LOP_APP_KEY/SECRET/ACCESS_TOKEN/PIN/SERVER_URL`（部署环境，见 .env 与 backend/.env.jd.uat.local 模板），以及 `sourceNo`/`carrierNo` 的 JDL 书面确认（spec「待外部提供」节）。
    2. 用户/运营明确授权：指定测试订单（来源表格行）、目标仓（当前为石家庄冷链 C仓1号库 `118085840`）、预期副作用与取消/处置方案。
    3. 开写门闩：`app.jd.write-mode=ON` + `app.jd.outbound-authorized-operators` 配置授权操作人；`app.jd.tracking-backfill.enabled=true` 开启自动回填。
  - **执行建议顺序**：真实 addSoOrder 前先 `GET /shipments/{id}/jd-so-order-preview` 确认 `submittable=true` 且 `townRequired` 由首次真实响应裁决（spec「乡镇必填策略」）；验收记录存 `.scratch/jd-real-sdk-switch/`（真实业务码、请求 ID、京东单号，不含凭据与个人信息）。
