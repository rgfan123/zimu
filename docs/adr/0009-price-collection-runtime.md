---
status: accepted
---

# Price collection runtime: reuse the platform-pull script channel, fail loud, keep yesterday visible

Grilling 2026-08-23（用户拍板账号与限频，其余按推荐记录）。

## Decisions

1. **通道**：两个爬虫（牧集 / 肉交所，移植 Demo 的 Playwright 实现）落入子牧既有
   Python 脚本通道——脚本进 `scripts/`（挂载进后端容器）、凭据进 `data-local/`（0600
   不入库）、由后端按 `PlatformScriptRunner` 模式调度执行。不新起服务。
2. **节奏**：每日早晨定时一次（默认 08:00，配置可调）+ 采购工作台"立即更新价格"
   手动按钮。**手动触发不限频**（用户拍板；平台侧账号风险由用户自担并知情）。
3. **账号**：沿用同事 Demo 中的牧集 / 肉交所账号（用户拍板）。会话材料按 Demo 的
   `get_muji_session` 交互式登录脚本人工生成，存 `data-local/`。
4. **失败呈现（绝不静默）**：采集失败（会话失效 / 页面改版 / 网络）→ 运营告警中心
   落一条告警 + 采购工作台价格区显示"今日价格未更新：<原因>，需人工重新登录"，
   附上次成功采集时间；**页面继续显示最近一次成功的价格，并明确标注数据日期**——
   不留空、不装新。
5. 恢复动作 = 人工重跑交互式登录脚本刷新会话，属运维动作，不做自动重登
   （避免触发平台风控）。
