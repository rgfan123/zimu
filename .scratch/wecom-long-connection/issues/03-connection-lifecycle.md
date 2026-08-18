# 03 — 连接生命周期与运维可见性

Type: grilling
Status: resolved
Blocked by: 01 — WS 长连接客户端选型与协议基线

Label: wayfinder:grilling

## Answer

意外断线（onClose/onError）：指数退避重连，1s 起步、翻倍、30s 封顶 + 抖动。被踢（`disconnected_event`）：**停止自动重连**、状态标记 KICKED、告警——单实例下被踢说明有外部新连接抢占，自动重连会形成互踢死循环，恢复需人工介入。订阅失败（凭据错误等）：连续 3 次后停止重试，readiness 标 FAILED。readiness 增加连接状态维度：DISCONNECTED / CONNECTING / SUBSCRIBED / KICKED / FAILED（非密投影）。应用关闭时优雅断开，不触发服务端踢线告警。

WS 长连接的生命周期策略与运维可见性怎么定？

- 生命周期：应用启动时建连并订阅；30s 心跳；断线检测与指数退避重连（上限与抖动策略）；应用关闭时优雅断开（不触发服务端踢线告警）。
- 单连接约束：单机器人只能一条连接。若被 `disconnected_event` 踢掉（说明有新连接抢占），自动重连会继续踢新连接——是**停止重连并告警**（主备切换应人工处理），还是延迟重连？本 effort 单实例，被踢场景仅可能来自外部测试或误操作。
- 运维可见性：连接状态（已连接/订阅中/断线/被踢）、心跳计数、最近错误如何暴露——沿用现有 `/api/v1/connectors` + readiness 风格，还是新增状态端点？
- 订阅失败（凭据错误等）时：退出还是重试？凭据不可用时应给出一次性、不泄密的诊断。
