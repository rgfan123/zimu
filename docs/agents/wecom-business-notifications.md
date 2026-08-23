# 企微业务事件主动通知（Issue #90）

## 决议

复核事项、订单创建与发货完成不逐条实时推送，统一进入 **5 分钟固定窗口汇总**。原因是复核事项
可能在同一导入/解释事务内集中产生，逐条推送会轰炸个人；5 分钟仍保持运营可用的及时性，同时让
同一责任团队、同一窗口的事实合并成一条有判断信息的消息。窗口和待发事实均落 PostgreSQL，应用
重启不会丢失，也不依赖内存延迟任务。

建批的短事务使用 PostgreSQL 事务级 advisory lock 串行化；多个 Worker 不会各自 `SKIP LOCKED`
同一窗口中的不同事项，误把本应一条的汇总拆成两条。发送阶段仍由批次租约和逐收件人 fence 控制。

## 捕获与内容

- `review_cases` 新增 OPEN 行时，在同一事务捕获 `REVIEW_CASE`；订单关联为 DEMO 时不捕获。
- BUSINESS `order_events.ORDER_RECEIVED` 捕获为 `ORDER_CREATED`；
  `order_events.TRACKING_RECEIVED` 捕获为 `SHIPMENT_COMPLETED`。
- 复核按自身 `responsible_team` 路由；订单创建固定给 `ORDER_OPS`，发货完成固定给
  `FULFILLMENT_OPS`。
- outbox 摘要只保存 case_no、reason_code、order_no、source_channel、shipment_id 等判断所需
  的业务标识，不复制事件 payload，也不保存收货人、电话、地址等 PII。
- 消息列出事项类型、复核原因、订单号和发货批次；#62 真实可分享路由落地前不拼伪链接。

## 路由降级

每次发送前实时调用 #89 `OperatorResolver`，因此登记、换组、绑定和停用下一批立即生效：

- 已绑定 userid 的 active 人员各收到一条窗口汇总。
- 同团队部分人员未绑定时，已绑定人员照常收到；每个未绑定人员形成 `BLOCKED /
  WECOM_USERID_UNBOUND` 投递记录，不静默过滤。
- 团队无人时不调用企微，形成 `BLOCKED / OPERATOR_TEAM_NO_MEMBERS`。
- 「从未与机器人会话」等带非零 errcode 的企微 ACK 是明确拒绝，形成 `FAILED / WECOM_{errcode}`；
  ACK 超时或提交后断线才是 `UNKNOWN`。本地测试不冒充外部可达性验收。

收件人 fence 不以列表序号或单独 userid 标识，而使用稳定 `operator_id + userid 代际`。每次重试先和
当前 active 团队成员对账：停用、换组、换 userid 后遗留的 `RETRY_PENDING` 收敛为 `BLOCKED`，
遗留 `SENDING` 收敛为 `UNKNOWN`；新 userid 使用新代际发送，旧代际不会让批次无限 PENDING。

## 恢复和不盲重发

`wecom_notification_batches` 保存窗口批次和 Worker 租约，`wecom_notification_deliveries` 保存每个
收件人的发送 fence。只有传输层明确返回 `retryable=true`（帧未提交）才退避重试，最多三次。
TIMEOUT、提交后断线、进程在 `SENDING` 后重启或发送调用抛出异常，都收敛为 `UNKNOWN`，不得盲目
重发。一个批次同时包含 SENT 与 BLOCKED/FAILED 时为 PARTIAL；这些状态同步投影到批次内所有源
事实，形成稳定追踪链。

每次企微外呼前都以 `(batch_id, lease_owner, lease_until)` 做 CAS 续租；续租失败的旧 Worker 立即
停止，不再外呼也不结束批次。`BLOCKED/FAILED/UNKNOWN` 同时投影到
`wecom_notification_alerts`：每个 `delivery × source item` 使用稳定 alert key 去重，能关联订单侧
主体时保存外键；只有导入、消息或草稿主体时仍持久保留。重复落库或应用重启后的恢复扫描不会重复
建告警，管理查询返回 alert id/key/severity。

管理端可用：

`GET /api/v1/admin/wecom-notifications/deliveries?source_type=REVIEW_CASE&source_id=...`

逐收件人查看 SENT/BLOCKED/UNKNOWN/FAILED、尝试次数、req_id、稳定原因码和持久告警摘要，从而
回答「为什么没推出去」。接口要求网关认证并携带 `X-Operator`。

## 配置

`WECOM_NOTIFICATION_ENABLED` 未显式设置时跟随 `WECOM_ENABLED`；默认窗口固定 5 分钟。可配置
轮询间隔、租约、单批最多事项数和数据库领取失败抑制窗口。单批默认 20 项，超出部分形成后续摘要。

## 本地门禁

- `WecomBusinessNotificationRunnerTest`：有用摘要、部分未绑定、无人团队、可重试失败和未知 fence。
- `WecomNotificationStoreIntegrationTest`：真实 PostgreSQL 触发器、BUSINESS/DEMO 隔离、同窗口
  汇总、并发 Worker 不拆批、短租接管、收件人代际对账、部分投递、退避复领、SENDING 重启
  UNKNOWN 与运营告警重启去重。
- `OpenApiContractConsistencyTest`：管理查询契约与运行时契约一致。

真实企微个人主动消息仍需部署后用已会话/未会话成员各做一次外部验收；本地实现证据不等于生产已
送达。
