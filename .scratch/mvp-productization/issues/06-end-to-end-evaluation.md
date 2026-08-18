# 06 — MVP 公共端到端评价

Type: end-to-end-test
Status: resolved
Blocked by: 01, 02, 03, 04
Claimed by: /root/e2e_evaluation → zed-agent subagent (2026-08-14 codex 额度中断后接手)

**What to build:** 从公共 Nginx 入口验评关键角色动线，形成可重复执行的通过/失败/gate 报告。

- [x] 覆盖人工复核、订单查询、文件作业、采购操作、京东只读、Demo 隔离、分析与审计。
- [x] 浏览器证据覆盖主导航层级、空态/失败态和关键写操作。
- [x] 报告明确区分本地验收、外部系统 gate 与未授权的生产写操作。

## Answer

zed-agent subagent 交付（2026-08-14）：重建 compose backend（新镜像）后从公共 Nginx 8088 执行 8 域评价——**70 PASS / 0 FAIL / 4 GATE**，浏览器证据 5/5（e2e-evidence/）。外部 gate 如实标注：京东真实只读（MOCK）、企微长连接（compose 未注入凭据）、采购写全流程（数据依赖）；未授权生产写操作全部未执行且有失败关闭证据（jd-write 403、/internal 拒绝）。发现高优先级偏差 B1–B3（scripts/acceptance.sh 与当前语义不一致：客户自动建档、批次级 confirm 生成履约文件、首批 PARTIAL 即开 followup——ExcelClosedLoopApiTest 15 项已锁定新语义）与观察项 B4–B6，移交 07 票处置。报告：`.scratch/mvp-productization/e2e-report.md`。
