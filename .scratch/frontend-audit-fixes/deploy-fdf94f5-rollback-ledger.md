# real-fdf94f5 部署回滚账本（2026-08-28）

## ⚠️ 回滚标签是分裂的，不能一把梭

部署前生产三镜像标签**不一致**（同事核实）：

| 层 | 部署前标签（=回滚目标） |
|---|---|
| backend | `real-88cc262`（2 小时前单独发） |
| frontend | `real-6638925`（11 小时前） |
| nginx | `real-6638925`（11 小时前） |

**回滚时严禁把三层统一 sed 成同一个标签**——backend 回 `real-88cc262`，
frontend/nginx 回 `real-6638925`。统一改会把前端拖回错误版本
（`real-fdf94f5` 前端改动 1753 行，回错很难肉眼发现）。

## 本次部署

- 目标：三层全部 → `real-fdf94f5`（nginx 无内容变更，由旧镜像 `docker tag` 对齐）
- 包含：86da569 成本档案宽表 / be569dc 四处修复 / 95862f6 KPI+批量 / fdf94f5 票据
- 无数据库迁移（`git diff 88cc262..fdf94f5 -- migration` 为空，同事核实）——回滚安全
- #178 已确认为 fdf94f5 祖先，不会被本次部署回退

## override 检查（比 runbook 多一项，同事提供）

sed 改标签后、scp 之前，**两项都必须 ≥1**：

```bash
grep -c GATEWAY_BASIC_AUTH_ENABLED /tmp/off.yml && grep -c MCP_MODULES /tmp/off.yml
```

`MCP_MODULES: "masterdata,inventory,orders-read"` 被洗掉的后果：公网 30000 端口
从 11 个只读工具变成全部 28 个模块，**含 messages 模块的客户 PII**。

## 部署后验收清单

1. 容器健康 + 镜像标签正确（runbook 既有）
2. `docker logs zimu-fulfillment-backend-1 --since 5m | grep -c 订阅成功` **≥1**
   （企微长连接重订阅；不满足则卡片全哑）
3. `curl -sS -o /dev/null -w "%{http_code}" http://114.244.13.53:28443/` **必须 401**（边缘认证仍在）
4. 业务验收：商品档案关键列显真实成本数据（110 行档案在生产库）、
   订单列表异常原因列、调度台 KPI 可点、复核队列批量入口

## 时序提醒

用户欠一次 #178 企微实测（「测试」群 @孔小弟 问商品）。重启仅数秒；
若用户恰在测试中，请其重发一次即可。

---

# 第二次部署：real-beeb441（2026-08-28 晚）

## 回滚目标（本次是统一的，可一把梭）

部署前三层**均为 `real-fdf94f5`**（上一次部署已消除标签分裂）。
回滚：三层统一改回 `real-fdf94f5`，重跑 compose up 即可。

## 本次内容

- 8eaeb9c feat(workbench): 平台拉取批次快照弹窗（9 列，企微形态，来源口径）
- 59cdfb2 feat(agent): Token 列解析 + 随筛选历史汇总（后端 /token-usage 补 outcome/business_entity_id）
- beeb441 docs(scratch): 过程记录

无数据库迁移。后端两道 Testcontainers 门禁（TokenUsage 集成 + OpenAPI 契约）已在开发机真 PostgreSQL 跑绿。
nginx 层无变更，由 real-fdf94f5 打 tag 对齐。

## 验收补充项（在通用清单之上）

- 前端产物 grep：「当前筛选历史 Token 汇总」「批次快照」类字符串
- GET /api/v1/agent-runs/token-usage?outcome=FAILED 不再 400（新参数生效）

---

## 第三次部署 · real-a6375b70(2026-08-28 13:43)

- **内容**:集成发布 jry/integration-20260828——四路(卡片换 button_interaction+ADR-0014 / 聚福宝登录形状+界面凭据+AES-GCM / 彩食鲜 orderList JSON 直连+对账 / 飞象 JSON 拉取)+ 四红修复(V77 换货词表 + v2 回传统计读模型)+ 邻会话 ae98el/daba519(商品档案导出+数值格式化)
- **迁移**:V73(source_sync_auto_states)+ V77(换货事件类型,ON CONFLICT 幂等);V74–V76 预留未用跳号
- **新增环境变量**:CONNECTOR_CREDENTIAL_KEY(backend,值不入库不入账本)
- **回滚**:三层统一改回 `real-beeb441` 一把梭;V73 新表/ V77 插词表对旧代码无害,免迁移回滚
- **验收**:三容器 healthy / v77 到位 / 企微重订阅 1 / 公网 401 / 密钥+模块注入各 1 / 前端含「导出表格」

---

## 第四次部署 · real-07b8a548(2026-08-28 23:34)

- **内容**:分支收敛发布。在 real-0e1c2a41 基础上并入
  - `jry/store-lease-exception-sweep`(4 提交)—— @Repository 持久化异常翻译把租约/并发冲突改写成误导类型;新增 `ConcurrencyConflictException` + ADR
  - `jry/scheduled-pull-and-ship`(1 提交)—— 每日 09:00/18:00 定时拉取三平台(自动发货部分未完,续做中)
- **迁移**:V83(scheduled_pull_runs);V78–V82 为在途交付线预留后未用而跳号
- **ADR 改号**:store 并发异常 ADR 由 0015 改为 **0016**,避让隔壁会话先认领的 0015(MCP 模块 fail-safe)
- **回滚**:三层统一改回 `real-0e1c2a41` 一把梭;V83 是新建表,对旧代码无害,免迁移回滚
- **验收**:三容器 healthy / v83 到位 / 企微重订阅 1 / 公网 401 / 守门三项(认证+MCP模块+凭据密钥)各 1
- **过程记录**:合并后两个 schema 守卫如期变红(历史条数 74→75、快照缺 V83 段共 28 类结构差异),
  补齐后转绿。守卫按预期拦下了「新增迁移未同步快照」这类静默分叉。
