# 企微订单草稿确认卡片（#87 / #88）

## 1. 业务边界

订单草稿卡片只是既有人工复核入口的快捷方式，不新增自动成单规则。卡片显示草稿号、行数与待补充项数量，不显示收货人电话、地址等敏感字段；点击确认后仍由 `OrderDraftService.confirm` 校验当前草稿版本、唯一开放复核事项、Customer/SKU 确定性候选、收货与结账事实。缺少任何必需事实时只返回“待补充”，不写正式订单。

## 2. 发送与外部效果栅栏

草稿与 `wecom_order_draft_cards`、`WECOM_ORDER_DRAFT_CARD` 异步任务在同一事务创建。卡片固定使用协议安全、带版本断言且不可猜的 `task_id=order-draft_{draft_id}_v{draft_revision}_{128-bit授权引用}`，实体与版本只从该持久化投递行取得；同时固化 `route_type` 与 `chatid`：群聊保存 `GROUP + chatid`，单聊保存 `SINGLE + 发送人 userid`，不能仅凭两类标识的字符串恰好相同跨路由授权。**注意（4bf6c837 起）**：这里固化的 `chatid` 是**来源会话**（回调校验基准），不等于**实际投递目标**——`OrderDraftCardRunner` 在真正发送时才经 `app.wecom-reply.routes.order-draft-card` 解析投递目标（缺省 `ORIGIN` 回来源会话，两者一致；`OVERRIDE` 时改投配置的会话，两者分裂，卡行 `chatid` 仍如实保留来源会话作为证据，不随投递目标变化）。

发送状态为 `PENDING → SENDING → SENT`。只有外部调用明确尚未提交时才回到 `PENDING` 重试；平台非零 `errcode` ACK 是明确拒绝，进入 `FAILED`；ACK 超时、缺少合法 `errcode`、提交后断线或进程在 `SENDING` 崩溃都进入 `UNKNOWN`，禁止盲目重发，避免同一草稿重复卡片。真正触网前还会重新读取草稿：只有状态仍为 `OPEN` 且 revision 与卡片固化的 `draft_revision` 相同才发送；草稿已关闭或 revision 已变化时进入 `SUPERSEDED` 并成功终结异步任务，不发送一张点击必然过期的旧卡。

## 3. 点击事件与 actor

回调按官方企微 AI Bot SDK 的 `body.event.template_card_event` 读取 `event_key/task_id`，并兼容官方旧示例中的扁平字段。`from.userid` 是唯一人工 actor 来源；缺失时事件以 `WECOM_CARD_ACTOR_REQUIRED` 拒绝。事件原始载荷与白名单投影按 `(event_type,msgid)` 幂等保存到 `wecom_events`。

`confirm_order` 重新读取数据库当前事实并调用原人工确认用例，身份记为 `wecom:{userid}`；`supplement_order` 只返回当前缺失字段。回调只有在 `task_id` 能对应本系统已收到平台成功 ACK 的 `SENT` 卡片、草稿 ID 与持久化记录一致、且回调路由与卡片固化的**来源会话** `chatid` 一致时才有业务权限（`matchesRoute`）：群聊必须匹配原 `chatid`，单聊必须匹配原接收 `userid`。未知、未 ACK 或跨路由卡片只留证并拒绝。卡片保存的 `draft_revision` 也是确认命令的版本栅栏；草稿在发卡后被修改时，旧卡片不能确认点击人未看见的新事实，须回到工作台复核。

**已知限制（issue #220，4bf6c837 引入 OVERRIDE 改投后暴露）**：`matchesRoute` 校验的基准是来源会话，而 OVERRIDE 模式下点击回调来自实际投递到的会话——两者按设计不同，因此 **OVERRIDE + 可点击卡片当前一律会被 `matchesRoute` 拒绝**（`WECOM_ORDER_DRAFT_CARD_ROUTE_MISMATCH`），无法在改投的会话里点击确认；OVERRIDE 只对纯播报/不需要点击确认的场景是安全的。修复方案（校验改用投递目标而非来源会话，或改投场景本就不支持可点击回调）留待 #220 裁定，本文档不代为决策。

并发或重复点击不重复成单：同一事件先持久化带 UUID token 与 attempt 的 `PROCESSING` claim，业务确认另行事务提交；相同确认幂等键为 `wecom-card-confirm:{msgid}`。首次回调的 bot/chat/actor/create_time/event_key/task_id/order_draft_id/raw_payload 由数据库触发器保护为不可变，重投若用同一 msgid 指向另一草稿会被拒绝且不会更新原事件。若确认已提交但事件结果尚未收口，重放会获得一个新 token 的 fenced reconciliation attempt，并从草稿 `CONFIRMED` 终态恢复为 `ALREADY_CONFIRMED`；该次可收口一次 updateCard，此后的终态重投只读，不再发送卡片/文字，也不能覆盖首次 update/fallback 观测。若真实租户回调不提供 `from.userid`，替代路径是保留原 ReviewCase，由已认证运营人员在子牧工作台确认；系统不会用 chatid、昵称或配置值伪造人工 actor。

同一 `msgid` 在原处理仍为 `PROCESSING` 时发生并发重投，后到请求只写应用日志，不改事件的 update/fallback 观测、不更新卡片、不追发“失败”文字，避免用共享中的 claim token 覆盖仍在执行的原回调结果。超过 90 秒安全恢复窗、草稿仍未确认且 `order_draft.confirm` 原幂等租约已失效时，才以新 token/attempt 恢复；完成业务与记录 update/fallback 结果都用 token CAS，旧 worker 不能覆盖新尝试。

## 4. 5 秒 updateCard 快路径

业务事务返回后，处理器使用原事件 `req_id` 发送：

```json
{
  "response_type": "update_template_card",
  "template_card": {
    "card_type": "text_notice",
    "main_title": {
      "title": "订单已确认",
      "desc": "OD-... · 操作人：userid · 2026-08-23 20:00:00"
    },
    "task_id": "order-draft_123_v0_0123456789abcdef0123456789abcdef"
  }
}
```

WebSocket listener 只解析协议与关联 ACK。普通业务回调仍进入容量 64 的可关闭单线程队列并保持到达顺序；`template_card_event` 则进入独立的 4 并发快通道，不会排在普通回调后面。快通道另有最多 4 个等待位，等待中的事件继续使用 listener 原始到达时刻计算 deadline，不能靠排队重置 5 秒预算。任一回调池再饱和时都只拒绝超出的单次事件，不在 listener 线程降级执行业务，也不因本地积压重建共享连接；已经受理的卡片 update ACK 和无关外发 ACK 因此不会被一次普通或交互回调溢出打断。这样处理器可同步等待 update ACK，而 listener 仍能接收并关联该 ACK。

4.5 秒预算从 listener 收到完整帧的单调时钟时刻开始，不因线程切换而重置。一个绝对 deadline 覆盖本地提交、socket 提交和平台 ACK；发送队列里已过期的 update 帧会直接丢弃，绝不在窗口后补发。只有同 `req_id` 的企微 ACK 且 `errcode=0` 才记为 `SENT`，超时记 `TIMED_OUT`，平台拒绝/本地失败记 `FAILED`。update 非成功时发送文字兜底；无论卡片更新或兜底是否成功，都不回滚订单业务结果。

`wecom_events` 分开记录：

- `processing_status/business_code/processed_by/processed_at/processing_claim_token/processing_attempt`：业务结果与 fenced attempt；
- `update_status/update_latency_ms/update_error_code`：卡片快路径结果；
- `fallback_status/fallback_error_code`：文字补偿结果，因此 update 超时与兜底成功可以同时保留。

## 5. 上线验收

本地测试覆盖官方帧形状、扁平兼容、真实 callback→handler→update ACK、两张并发卡片各自立即进入快路径、普通或交互回调池溢出时保全已受理 update 与无关外发 ACK、普通回调有序队列的 listener 到达时刻、同 `req_id` 更新、绝对 deadline、过期发送帧不补发、文字兜底、关闭或 revision 已变化的积压卡片不触网、真实 PostgreSQL 首次事实不可变、幂等租约与 token CAS。生产启用前仍须在实际企微机器人中完成一次真实点击验收，确认租户回调确实携带 `from.userid`；若缺失，系统会按设计拒绝，不能用 chatid、昵称或配置值代替 actor。

协议实现依据：[WeCom AI Bot Node SDK](https://github.com/WecomTeam/aibot-node-sdk)。
