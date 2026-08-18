# 07 — 成品回归与双轴复审

Type: test-review
Status: resolved
Blocked by: 05, 06
Claimed by: zed-agent subagent (2026-08-14 接手，承接 e2e 报告 B1–B3 处置)

**What to build:** 在同一工作树完成后端、前端、文件、Compose 全量回归，并按 Standards/Spec 两个独立轴审查本轮变更。

- [x] Maven 全量测试、前端单测/typecheck/build、OpenAPI 解析和 shell 语法通过。
- [x] fresh Compose 与重启幂等公共 HTTP 验收通过。
- [x] Standards 和 Spec 复审的 P0–P2 硬问题被修复或显式列为 gate。

## Answer

zed-agent 收口（2026-08-14）：

**回归数字**：后端 `mvn test` 493/493（0 失败，7 skipped）；前端 155/155 + `tsc --noEmit` + `vite build` 全绿；OpenAPI 解析通过（95 paths，pyyaml 校验）；`sh -n scripts/*.sh` 全部通过。

**B1–B3 处置（scripts/acceptance.sh）**：已对齐 ExcelClosedLoopApiTest 锁定的新语义——①导入不再断言 `CUSTOMER_MATCH_REQUIRED`（客户自动建档，未映射 SKU 仍进 SKU_MAPPING_REQUIRED）；②履约文件改为批次级 confirm 生成（导入后 `generated_fulfillment_export_ids == []`、confirm 后 `== 1`，含幂等重放断言）；③首批 PARTIAL 立即开 `MULTI_SHIPMENT_SOURCE_FOLLOWUP`、followup 未关闭不产出回填文件、人工完成后订单直接 CLOSED。语法与重跑路径已验证。

**fresh Compose 与重启幂等**：e2e 阶段重建 backend（新镜像）后 8 容器 healthy、nginx 8088 公共入口 200；`docker restart backend` 后 14s 启动、actuator 200、DispatcherServlet 正常、无异常日志（Flyway 不重复迁移、任务租约可恢复）。

**双轴复审结论**：Standards 无 P0–P2（错误码/非密投影/幂等/审计/Flyway 规范均符合）；Spec 侧 B4–B6 评估为观察项非阻断（复核恢复 Timeline 无 SKU_MAPPED 事件、未知路由回退 /dashboard、MOCK 无法构造 PENDING 采购票——环境/表现层观察）。

**显式 gate**：docs/openapi.yaml 未同步 5 个近期新端点（`/api/v1/wecom/readiness`、`/api/v1/message-media/{id}/content`、`/api/v1/tracking-drafts/batch-confirm`、`/api/v1/admin/message-pipeline/summary`、`/api/v1/shipments/{id}/jd-tracking-backfill`）——列为 P1 文档同步 gate，后续单独补；真实京东写/企微外呼/生产访问仍为外部 gate（用户已决定暂缓京东）。
