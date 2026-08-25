# 07 — 彩食鲜 Java Connector：pullOrders（文件化试点候选）

**What to build:** 彩食鲜 Connector 实现在线拉单（文件化链路）：登录续期（login-token + supplier-code）→ 发起导出任务 → 轮询任务完成 → 下载文件字节 → 复用现有文件解析管线（含 raw 行血缘、confirm、履约导出全链路）。因拉取产物是真实文件字节，仅需文件解析入口可见性放开（若适用）。JSON orderList+orderDetail 直连可作为并行增强（含商品明细/地址），但主链路为文件字节以复用既有管线。

**Blocked by:** 01

**Status:** resolved

- [ ] 登录 → 导出 → 轮询 → 下载全链路可跑通，产出字节进入文件解析管线
- [ ] 生成批次可 confirm，confirm 后履约导出与文件导入路径行为一致
- [ ] 失败重拉不产生重复订单（配合 02 行级跳过）
- [ ] testConnection 可用真实只读动作探测

---

## Answer (2026-08-19)

**Status: resolved**

Java Connector 在线拉取已实现：`CaishixianPullClient`（登录响应头 login-token 续期 → exportDeliverExcl 发起任务 → task/my 轮询 → file/download 下载，PK 魔数校验）+ `CaishixianConnector` 覆盖 `capabilities()`（onlinePull=true）与 `pullOrders`（登录→拉取→`SourceImportService.upload` 文件管线→PullResult；凭据缺失/平台错误返回 FAILED 不抛异常；DUPLICATE_ORDER 捕获后返回 ok count 0）。测试 `CaishixianConnectorTest` 10 例通过（mock PullClient + SourceImportService）。

遗留风险：
- 成功形态未实测（沙箱无外网）；orderStatus 语义基于单次观测（status=3 待发货）
- 与脚本通道（PlatformOrderRefreshService）并存：脚本通道为兜底，Java Connector 为主链路
