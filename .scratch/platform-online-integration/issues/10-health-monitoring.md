# 10 — 健康检查与监控

**What to build:** testConnection 升级为真实只读探测（三平台各自的只读动作：彩食鲜 orderList 或任务列表、聚福宝 orders/query 空页或 userinfo、飞象统计接口）；拉取全程写 AuditLog；失败告警分级（黄=重试成功、红=连续失败、灰=接口结构异常）；告警通道随 D3 决策落地（先日志+状态文件+看板，或企微发送）。

**Blocked by:** 09 或 07 或 08（任一 Connector 落地），D3 决策

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/25

- [ ] testConnection 对三平台做真实只读探测并返回准确诊断
- [ ] 拉取/上传/确认关键动作均落 AuditLog
- [ ] 连续失败触发已确认的告警通道，结构异常（404/字段缺失）走灰级
- [ ] 接口失效演练一次（临时改端点验证告警与人工导表兜底流程）

---

## 合并修订（2026-08-18）

**D3 已裁决，阻塞解除**：告警只做**系统内可见**——`connector_configs.last_error` / `last_pull_at` + AuditLog + 现有 `ConnectorsPage` 健康态 + 工作台异常卡。**不建企微/邮件外发通道**（那是独立能力，与在线接入无关，已列范围外）。

修订后的 Blocked by：**07 或 08 或 09（任一）**——D3 不再是阻塞项。

验收项按此降级重写：

- [ ] testConnection 对三平台做真实只读探测并返回准确诊断
- [ ] 拉取/建批次/确认关键动作均落 AuditLog
- [ ] 连续失败写入 `last_error`，在 ConnectorsPage 与工作台异常卡上可见
- [ ] 结构异常（404 / 字段缺失 / 返回 HTML）走灰级，与「拉到 0 条」区分开
- [ ] 接口失效演练一次（临时改端点 → 验证系统内可见 → 人工导表兜底跑通）
