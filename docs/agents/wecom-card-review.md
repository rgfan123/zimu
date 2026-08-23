# 企微交互卡片评审意见（给 #87 / #88 / #90 开工前）

**评审时间**：2026-08-23 · **基准**：`master` @ `4547244`
**时机说明**：全仓 `template_card` / `button_interaction` / `aibot_respond_update_msg` **零命中**，
一行卡片 JSON 都还没写——这份意见是开工前的约束，不是事后返工。

---

## 一句话结论

传输、ack、审计、幂等的地基（#81/#82/#84）已经建得很扎实。
#87/#88 唯一的真实风险是**图快绕过它们**：直接 `sendFrame` 拼 JSON、在回调线程跑长事务、
`task_id` 用随机 UUID、以及没想清楚「超过 5 秒之后怎么更新卡片」。

把下面 **A（走 Gateway）** 和 **B（task_id 带版本）** 两条钉死，其余样式细节都可迭代。

---

## A. 架构级：卡片必须走 `WecomOutboundGateway`，禁止 `sendFrame`

`WecomLongConnectionClient.sendFrame(cmd, body)` 目前**零业务调用方**——正是最容易被顺手抓起来的锤子。
一旦 `sendFrame("aibot_send_msg", cardJson)`，就绕过 #81 建立的全部纪律：
ack 关联、`TIMEOUT` 不可重试、审计脱敏、背压优先级。

**正确做法**：`WecomOutboundMessage.Type` 增加 `TEMPLATE_CARD`，在 `WecomLongConnectionClient.send()`
的分支里加 `body.set("template_card", cardNode)`，复用 `awaitAck`。
审计沿用现有思路：只落 `card_type` / `task_id` / 按钮数 / `content_sha256`，**不落卡片正文**（正文含客户名）。

## B. `task_id` 业务化 = 免费拿到防重放

建议格式 **`{domain}:{id}:v{version}`**，版本号直接当乐观锁的 `expected_version` 用：

- `review:1234:v0` → 对齐 `review_cases.resolution_version`
- `alert:5678:v2` → 对齐 `operational_alerts.lock_version`
- `export:99:g3` → 对齐 V49 的 `initial_generation`

好处：回调带回的 `task_id` 本身就是版本断言 → 点旧卡片自动 `409 VERSION_CONFLICT`，
不需要另造防重放机制，且与现有 `expected_version` 契约天然同构。

## C. 按钮：只放「零参数 + 幂等」的状态跃迁

- **`type=2` 回调按钮**：仅限零额外参数的跃迁——知道了 / 我来处理 / 重试 / 忽略
- **`type=1` 跳转按钮**：任何要选客户、选 SKU、填数量的动作，一律深链回后台
- 协议允许 6 个按钮，**实用上限 3 个**；主操作 `style=1`(蓝)、驳回/危险 `style=2`(红)、次要 `style=3`(灰)
- 按钮文案 ≤ 10 汉字

## D. 操作人可溯性：#89 已铺好地基，别浪费

#89 已落 `internal_operators`（`wecom_userid` partial unique）+ `OperatorResolver`。

**要求**：「点击者身份解析不出来」必须是一等路径——
未登记的人点按钮 → 卡片更新为「操作人未登记，请先在 系统管理→运营人员 登记」，
**不许静默执行，也不许抛 500**。
审计 `actorType` 必须是 **`HUMAN`**（对齐 `SourceImportService.confirm`），不是 `SYSTEM`——
点卡片的是人，这决定确认动作的审计主体能否成立。

## E. 更新卡片的三档时机（第 2 档最容易漏）

| 档 | 时机 | 做法 |
|---|---|---|
| 1 | **5 秒内快路径** | `aibot_respond_update_msg` 用事件帧的 `req_id`，**只做「按钮已收到」的即时反馈**，按钮换禁用态「处理中…」。绝不在此档等业务事务——`confirm` 走 idempotency + `FOR UPDATE` + 京东批量建单，必然超 5s |
| 2 | **业务落定后（>5s）** | 协议窗口已过，**同一张卡再也 update 不了**。⚠️ **这是最容易漏的设计缺口**——不提前想，线上就会出现「卡片永远停在处理中」。**建议 #87 先做 spike 实测**：`aibot_send_msg` 带**相同 `task_id`** 能否覆盖原卡？能覆盖优先覆盖（不刷屏），不能则追发新卡并在卡面引用原 `task_id`。**这个未知项应写进票的验收前置** |
| 3 | **外部事件致卡片失效** | 没有 `req_id` 可用。**建议不发新卡**，靠 §B 版本校验兜底：点旧卡 → `VERSION_CONFLICT` → 回文本「该事项已由 XXX 于 HH:mm 处理」。与 V49 的 `SUPERSEDED` 语义一致，照抄该模型 |

## F. 字段取舍

**物理约束**（逼你克制）：`horizontal_content_list` ≤ 6 项、`keyname` ≤ 5 字、`value` ≤ 26 字、
`main_title.title` ≤ 26 字、`sub_title_text` ≤ 112 字。

**绝不上卡**：`media_id`（3 天临时引用，已有纪律）、任何 `secret`/`aeskey`/原始 URL、
客户手机号与详细地址（PII——V20 `sanitize_wecom_message_public_history` 已有先例）。

**必须上卡**：稳定业务号 `case_no` / `alert_no` / `batch_no` / `erp_delivery_no`——
群里的人要拿它去后台搜、也要拿它在群里回话。

| 事件 | 卡型 | 字段 | 按钮 |
|---|---|---|---|
| 复核事项创建 | `button_interaction` | `case_no`、`case_type`、`reason_code`→人话、`responsible_team`、关联订单/批次号、创建时间 | `[我来处理]`(回调，认领到点击人) `[去后台处理]`(跳转)。**不放「确认」**——resolveCustomer/resolveSku 需要参数 |
| 运营告警 | `button_interaction` | `alert_no`、`severity`(RED→`desc_color=1`，YELLOW→`2`)、`message`、关联 shipment/order、`detail.business_code` | `[知道了]`(回调→`acknowledge`，已有 `IdempotentResult`+`lock_version`，零参数幂等，完美适配) `[去处理]`(跳转) |
| 整批确认完成 | **`text_notice`**（播报型） | `batch_no`、来源渠道、订单数/行数、`jd_sdk_shipment_ids` size、第三方导出数、确认人 | **无回调按钮**，只放跳转——动作已完成，卡片是事后播报 |
| 京东出库失败 | `button_interaction` | `erp_delivery_no`、`failure_phase`、`business_code`、`retry_count` | **把 `retryable` 直接映射成按钮的有无**（代码已算好 `!"RECONCILIATION_REQUIRED".equals(safeCode)`）：true → `[重试建单]`；false → **只给 `[去对账]`**。别让人点了才报错 |
| 运单回填失败 | — | — | **不做**：它是同步 API 的 422/409，当场返回调用方，没有异步观众。真正需要推送的下游 #84 已完成 |

---

## G. 回调接线的两个必验坑（#90 相关）

hook 点唯一且明确——`WecomMessageDispatchHandler.java:115`：
```java
case "template_card_event", "feedback_event" -> log.debug("按决策忽略企微事件: {}", eventType);
```
翻转方式：`template_card_event` 移到新分支 → 先 `persistEvent`（`wecom_events` 的
`ON CONFLICT (event_type, msgid) DO NOTHING` 天然幂等，企微重推不会重复执行）→ 再派发业务。

1. ⚠️ **`persistEvent` 在 `msgid` 为空时直接丢弃**。`template_card_event` 载荷里有没有 `msgid`
   **需实测**——没有的话要用 `task_id + event_key + create_time` 组合做幂等键，否则**丢事件**。
2. ⚠️ **`handleEvent` 跑在回调线程上，而 update 窗口只有 5s**。回调线程只准做
   `persistEvent` + 立即 respond「处理中」，**业务写必须扔进 `AsyncTaskStore`**（#84 已有设施）。
   绝不在回调线程里跑 `confirm` 这种带 `FOR UPDATE` + 京东建单的长事务。

## H. #90 的最大坑：复核事项创建有 13 个散点

无统一入口。JPA 路径 6 处（`OrderCreateService:221/298-300`、`IntentRouter:90`、
`WecomOrderDraftFactory:273`、`WecomTrackingDraftFactory:255/291`、`McpReviewRequestService:82`）；
裸 SQL `INSERT INTO app.review_cases` 7 处（`ProviderFileService:379/839`、`TrackingFileService:617`、
`ShipmentJdOutboundAuditService:189`、`ShipmentJdStockCheckService:403`、
`ShipmentJdTrackingBackfillService:410/451`、`ShipmentTrackingService:299/326`）。

**#90 要么先做一个收口 seam，要么改 13 个地方——必须在票里写死，否则一定漏。**

同理，**运营告警 hook 挂在 `OperationalAlertService.createSystem` 上会漏**：
三处绕过 Service 裸 INSERT（`ShipmentJdOutboundService:589` ← **正是京东出库失败那条**、
`FulfillmentStockDecisionService:264/441`）。

## I. 迁移版本要提前裁：#87/#88 与 #90 会抢 V50

V49 注释已写明 #90 用 V50。若 #87/#88 也要建表（`wecom_card_deliveries` / `wecom_card_interactions`），
**必须先裁定 V50/V51 归属**，否则两个分支各占 V50，合并即炸。
建议 #87 开工前先写一行版本预约。
