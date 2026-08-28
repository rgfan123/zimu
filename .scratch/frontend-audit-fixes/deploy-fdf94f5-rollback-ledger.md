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
