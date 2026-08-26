# 发货前确认卡片：企微形态可行性调研

状态：**调研稿（2026-08-25）**，只读分析，**不含任何业务代码改动**；所有「新增/建议」均为待实施建议，须评审后另行开工。
外部依据：企业微信开放文档（**智能机器人 aibot 场景**，逐条附 URL）。
仓库依据（只读核实）：`backend/src/main/java/cn/zimu/fulfillment/connector/wecom/`、`.../order/card/`、`.../fulfillment/`。
生产依据（只读查询）：`ssh zimupc` 上 `zimu-fulfillment-postgres-1` 与 `zimu-fulfillment-backend-1`，2026-08-25。

> **选址理由**：本文 60% 是外部一手 API 规格核实（企微 aibot 模板卡片官方字段表），40% 是基于该规格的内部设计建议，
> 且**尚未决议**。仓库约定里 `docs/agents/` 放的是**已决议**的交付说明（`wecom-order-draft-cards.md`、
> `wecom-business-notifications.md` 开头都是「决议」/「本地 Resolution」），`docs/research/` 放外部契约与**待评审设计稿**
> （`platform-api-integration-design.md:3-8` 就是「设计稿…待评审…只读分析…待实施建议」的同型文档）。
> 本文形态与后者完全一致，故放 `docs/research/`。**评审通过、开票实施后**，交付说明应另写入 `docs/agents/`。

---

## 0. 一句话结论

**能做，但不能做成「一张卡直接发京东」，也不能靠深链兜底**——
企微 aibot 官方硬性 5 秒回包窗口 vs 生产上「确认 → N 次京东 HTTP 建单」的长事务不可调和；
而深链这条退路四层全断（内网 IP / 链接本身是坏的 / 手机上完不成确认 / **认不出点击人**）。
可落地的形态是**「一批一卡（单聊）+ 卡内摘要（地址放 `sub_title_text`，112 字够装 44 字真实地址）+ 全回调按钮不放跳转 + 确认只落『已授权发货』意图 + 京东建单异步跑 + 结果追发播报卡」**。

---

## 1. 「只做一件事」的建议

如果这一轮只做一件事，做这个：

> **把「整批确认」拆成「授权（快）」与「建单（慢）」两段。**
> 卡片按钮只写一条 `batch_release_authorizations` 记录（纯 DB 写，毫秒级，能进 5 秒窗口），
> 京东建单由既有 `AsyncTaskStore` worker 异步执行，结果复用**已经在生产跑通的** `BatchConfirmedCard`（15 张 SENT 成功）播报。

理由：这一刀之外的所有东西（卡片字段排布、多单折叠、深链）都是可迭代的样式问题；**这一刀不切，卡片必然停在「处理中…」永远不动**——
因为 5 秒后**官方没有任何路径能再更新那张卡**（§3.4）。

⚠️ 但在做这件事之前，必须先解掉 §6.4 的**三个门闩**（回调没接线 / 京东建单 403 / 单聊 userid 无处可取），否则功能写完也点不动。

---

## 2. 你给的「已知硬约束」逐条核验

| # | 你的前提 | 核验结论 | 证据 |
|---|---|---|---|
| 1 | `horizontal_content_list ≤ 6 项` | ✅ **对** | [官方 101032](https://developer.work.weixin.qq.com/document/path/101032)：「长度不超过 6」 |
| 2 | `keyname ≤ 5 字`、`value ≤ 26 字` | ✅ **数值对** | 同上 |
| 3 | `main_title.title ≤ 26 字` | ✅ **对** | 同上 |
| 4 | `sub_title_text ≤ 112 字` | ✅ **对** | 同上 |
| 5 | 「这些是**物理约束**」 | ⚠️ **措辞不准** | 官方原文一律是「**建议**不超过 N 个字」。真正的硬约束是**字节**级的：`task_id ≤ 128 字节`、`button.key ≤ 1024 字节`、`option.id ≤ 128 字节`。汉字上限是**渲染建议**，不是接口校验。超长会不会被拒 → **未核实**（§9） |
| 6 | 「绝不上卡：手机号与详细地址（PII）」那条**只针对群聊** | ✅ **你说得对，而且代码注释就是这么写的** | `WecomBusinessCardSource.java:50`「**群聊必须脱敏**（收件人手机号与详细地址不得进群）」；`WecomBusinessCardRouteProperties.java:19`「**群聊路由**要求 source 在渲染时脱敏」；`WecomCardBuilder.java:20-21`「脱敏是业务语义（**单聊可见全量、群聊必须脱敏**），由调用方在投影阶段决定」 |
| 7 | （隐含）该纪律有代码落点 | ❌ **没有。纯文档约定，零代码强制** | 见 §2.1 |
| 8 | 真实地址 44 字远超 26 字 | ✅ **对，且生产数据比你说的更糟** | 生产 `app.shipments` 三条：`char_length(receiver_address_snapshot)` = **25 / 44 / 46**。你举的天津那条正是 44 字（`raw_import_rows.id=9`, batch 20, 2026-08-25 16:43）。**人工确认后的** `jd_receiver_detail_address` 仍是 17 / 22 / **30** 字——**连拆完的「详细地址」单段都能超 26** |
| 9 | 「协议允许 6 个按钮，实用上限 3 个」 | ✅ **协议 6 对**；3 是本仓库自己的设计裁定 | 官方：`button_list` 长度不超过 6；`WecomCardBuilder.java:35` `MAX_BUTTONS = 3`，注释自认「§C 裁定，不是协议限制」 |
| 10 | 评审 note 说 PII 纪律「V20 `sanitize_wecom_message_public_history` 已有先例」 | ❌ **引用错位，V20 根本不管收件人 PII** | `V20__sanitize_wecom_message_public_history.sql` 是**一次性数据回填 UPDATE**（不是 VIEW，不是 TRIGGER）：`:5-21` 收 `async_tasks.last_error`、`:23-37` 收 `message_interpretations.error`、`:39-148` 白名单重建 `review_cases.detail`。**它一列收件人姓名/手机/地址都没碰。** 照这个「先例」去实现的人拿不到任何收件人脱敏能力 |

### 2.1 ⚠️ PII 纪律的真实强度：文档约定，且**架构上做不到**

这一条对本需求是**决定性**的——发货前确认卡**必须**带收货人姓名、手机、详细地址，正好踩在这条纪律上。

| 层次 | 有没有强制 | 证据 |
|---|---|---|
| 卡片构造器 | ❌ 明确声明不管 | `WecomCardBuilder.java:20-21`：「本类不判断哪些字段该脱敏…由调用方在投影阶段决定」 |
| source 投影层 | ⚠️ 只是「SQL 不 SELECT 那些列」，**省略式白名单**，无脱敏函数、无断言、无守卫 | 四个 `*CardSource` 的 SQL 里根本没取收件人列；`ReviewCaseCardSource` 连 `rc.detail` 都刻意没取 |
| DB 视图 / 触发器 | ❌ 无（对卡片路径） | 全部 57 个 migration 只有 3 个 VIEW，均与 PII 无关 |
| 出站网关 | ❌ 零拦截 | `WecomOutboundGateway.java:29-43` 只 `transport.send(message)` + 事后审计；`:77-90` 的「脱敏」只作用于**审计记录**（TEMPLATE_CARD 只记 chat_id/类型/bytes/sha256，`:145-147`）——是「不落库」不是「不发出」。`WecomLongConnectionClient.java:343` 原样直传 |
| 文档 / 注释 | ✅ 主要载体（4 处） | `wecom-card-review.md:70-71`、`application.yml:133`、`WecomBusinessCardRouteProperties.java:19`、`WecomBusinessCardSource.java:50` |
| 唯一真落地的那条 | ✅ **投递表不存卡片正文** | `V55__wecom_business_cards.sql:12-35` 表结构无正文列；发送时按当前事实重渲染 |

⚠️ **最关键的结构性障碍**：`WecomBusinessCardSource.render(long entityId, long entityVersion)`（`WecomBusinessCardSource.java:26`）的**签名里没有 Route**，`WecomBusinessCardRunner.java:77` 调用时也不传。
**render() 根本不知道自己要发去群聊还是单聊**——「单聊可见全量、群聊必须脱敏」这条规则在当前架构里**无法实现**。
`RouteType` 在 `src/main` 的全部引用只有存/读/默认值，**零处 `if/switch` 影响渲染内容**；它唯一被用于行为的地方是**回调鉴权**（`WecomOrderDraftCardInteractionService.java:207-217`），不是脱敏。

> 唯一的「守卫」是一个近乎同义反复的单测：`BusinessCardRenderingTest.java:106-133` 用**本来就不含 PII 的手写 View** 渲染，断言 JSON 不含 `"phone"/"receiver"/"address"` 等**英文 key**。
> 若 SQL 改成 SELECT `o.receiver_name`，值是「张三」，字符串里既无 `receiver` 也无 `phone`，**测试照样绿**。

**已知真实缺口**：`operational_alerts.message` 是**无过滤自由文本**，直通群卡 `sub_title_text`（`OperationalAlertCardSource.java:48` → `OperationalAlertCard.java:64`）。今天各创建点写的都是常量，但告警创建点是散的（含 3 处绕过 Service 的裸 INSERT），**没有任何机制**阻止将来有人把收件人写进 `message` 然后自动进群。

> ✅ **可复用的正面先例**：`V51__wecom_business_notification_outbox.sql:173-194` 的 outbox 触发器用 `jsonb_build_object` 固定列清单做 DB 级 PII 白名单，消费侧 `WecomBusinessNotificationRunner.java:160-217` 再做一层 key 白名单——**而它服务的正是单聊**（`:120` 发给 `member.wecomUserid()`）。
> **建议：发货前确认卡的 PII 投影照抄这套，而不是照抄四张业务卡的「省略式白名单」。**

### 2.2 ⚠️ 评审 note 里有 3 处与官方 aibot 规格**不符**，且已写进代码

| # | 仓库现状 | 官方 aibot 规格 | 影响 |
|---|---|---|---|
| A | `WecomCardBuilder.jumpButton()` 生成 `button_list[].{type:1, url:...}`（`WecomCardBuilder.java:190-193`） | ⚠️ **aibot 的 Button 结构体只有 `text` / `style` / `key` 三个字段，没有 `type`，没有 `url`** —— [101032](https://developer.work.weixin.qq.com/document/path/101032)（我另用 WebFetch 逐字复核了参数表） | **卡上的跳转按钮在 aibot 场景不成立**。4 张卡里有 4 处 `jumpButton`：`BatchConfirmedCard.java:57`、`ReviewCaseCard.java:57`、`OperationalAlertCard.java:70`、`JdOutboundFailureCard.java:61` |
| B | `BatchConfirmedCard` 是 `text_notice`，却调 `jumpButton` → 产出 `button_list`（`BatchConfirmedCard.java:48,57`） | ⚠️ **`text_notice` 的参数表里根本没有 `button_list`**（只有 button_interaction 有） | 该字段大概率被平台静默忽略。**注意：这 15 张卡在生产是 `SENT`（errcode=0）的**，说明平台**不拒绝**多余字段——所以「发送成功」**不等于**「按钮渲染出来了」 |
| C | `cardAction` 是**条件性**的（`WecomCardBuilder.java:196`，detailUrl 空则不写） | ⚠️ `text_notice` 的 `card_action` 是**必填（是）** | detailUrl 为空时产出的 `text_notice` 缺必填字段。实际是否被拒 → 未核实 |

> **`text_notice` 上正确的放链方式是 `jump_list`（≤3 项，title ≤13 字）**，本仓库的 builder 没有实现这个区块。

---

## 3. 卡片能力边界（官方 aibot 规格 vs 仓库实现）

### 3.1 ⚠️ 最容易踩的坑：企微有**两套**模板卡片规格，数值不同

| 字段 | **aibot 场景**（本项目用的） | 应用消息场景 |
|---|---|---|
| 文档 | [101032](https://developer.work.weixin.qq.com/document/path/101032) | [90236](https://developer.work.weixin.qq.com/document/path/90236) |
| `main_title.title` | **26** | 36 |
| `main_title.desc` | **30** | 44 |
| `sub_title_text` | **112** | 160 |
| `horizontal_content_list.value` | **26** | 30 |
| `emphasis_content.desc` | **15** | 22 |
| `jump_list.title` | **13** | 18 |
| `horizontal_content_list.type` | 0 / 1 url / **3 成员详情**（**无 type=2 附件**） | 0 / 1 / **2 media_id** / 3 |
| `button_list[]` | `text` / `style` / `key` | `type` / `text` / `style` / `key` / `url` |

**照抄 90236 会写出在 aibot 下渲染截断或字段无效的卡。** 本仓库 `WecomCardBuilder` 的 6 个常量（26/30/112/5/26/6）**全部与 aibot 规格一致**——评审 note 的数字是对的，包括那个自认「未见于评审文档，按协议常见值取保守值」的 `MAX_DESC = 30`（`WecomCardBuilder.java:25-31`），**猜对了**。

### 3.2 `button_interaction` 的完整顶层字段表（aibot，逐字复核）

| 字段 | 必填 | 上限 | 能装多长的中文 |
|---|---|---|---|
| `card_type` | 是 | — | — |
| `source` | 否 | `desc` 建议 ≤ 13 | 13 |
| `action_menu` | 否 | `action_list` 1~3 项 | — |
| `main_title` | **是** | `title` ≤ 26、`desc` ≤ 30 | 26 / 30 |
| `quote_area` | 否 | `title` / `quote_text` **未声明上限**，支持 `type=1 url` | **未知** |
| **`sub_title_text`** | 否 | **112** | **112 ← 最长** |
| `horizontal_content_list` | 否 | ≤ 6 项，`keyname` ≤ 5，`value` ≤ 26，`type` 0/1/3 | 26/项 |
| `button_selection` | 否 | ≤ 10 选项，`title` ≤ 13，`option.text` ≤ 10 | 下拉选择器 |
| **`button_list`** | **是** | ≤ 6，`text` ≤ 10，**仅 text/style/key** | — |
| `card_action` | 否 | `type` 1 url / 2 小程序 | 整卡跳转 |
| `task_id` | **是** | ≤ 128 字节 | — |
| **`jump_list`** | ❌ **button_interaction 不支持** | — | — |
| **`emphasis_content`** | ❌ **仅 text_notice 支持** | — | — |
| **`vertical_content_list`** | ❌ **仅 news_notice 支持**（≤4 项，desc ≤112） | — | — |

### 3.3 直接回答「有没有比 `horizontal_content_list` 更能装长文本的区块」

| 排名 | 区块 | 上限 | 带按钮的卡能用吗 | 44 字地址装得下吗 |
|---|---|---|---|---|
| 🥇 | **`sub_title_text`** | **112 字** | ✅ | ✅ **装得下，余量 68 字** |
| — | `vertical_content_list.desc` | 112 字 | ❌ 仅 `news_notice`，该卡型无 `button_list` | — |
| ? | `quote_area.quote_text` | **未声明** | ✅ | ⚠️ 未声明 ≠ 无限，别赌 |
| 3 | `main_title.desc` | 30 字 | ✅ | ❌ 截断 |
| 4 | `horizontal_content_list.value` | 26 字 | ✅ | ❌ **截断（最直觉但最错的选择）** |
| 5 | `emphasis_content.desc` | 15 字 | ❌ 仅 text_notice | — |

**结论：带按钮的卡上，44 字地址唯一安全落点是 `sub_title_text`。** 「收货地址：」这种标签只能写进字符串本身，没有 keyname 可用。
⚠️ 但 `sub_title_text` **只有一个**——地址占了它，其他长文本（如渠道商品名 + 京东商品名对照）就没地方放了。

### 3.4 ⚠️ 5 秒窗口：官方明文，且**超窗后没有任何更新原卡的路径**

| 结论 | 官方原文 | URL |
|---|---|---|
| 5 秒是硬窗口 | 「收到事件回调后需在 **5 秒内**发送回复，超时将无法更新卡片」 | [101463](https://developer.work.weixin.qq.com/document/path/101463) |
| 连接层佐证 | 「只会发起一次请求，企业微信服务器在**五秒内**收不到响应会断掉连接」 | [101027](https://developer.work.weixin.qq.com/document/path/101027) |
| 更新时 task_id 必须一致 | 「模板卡片中的 task_id 需跟回调收到的 task_id 一致」 | [101031](https://developer.work.weixin.qq.com/document/path/101031) |
| SDK 互证 | `updateTemplateCard(...)`「需在收到 `event.template_card_event` 事件 **5 秒内**调用」 | [aibot-node-sdk](https://github.com/WecomTeam/aibot-node-sdk) |
| ⚠️ **「同 task_id 重发新卡能否顶掉旧卡」** | **官方从未声明该行为**。task_id 被文档赋予的语义只有两条：唯一（「同一个应用任务 id 不能重复」）+ 更新时一致 | [90236](https://developer.work.weixin.qq.com/document/path/90236) / [101031](https://developer.work.weixin.qq.com/document/path/101031) |
| 超窗后唯一出路 | **追发一条新消息**：`response_url`（**有效期 1 小时，只能调用 1 次**，支持 `markdown` / `template_card`，但**它是发新消息，不是原地更新**）或主动 `aibot_send_msg` | [101138](https://developer.work.weixin.qq.com/document/path/101138) |
| 提交后置灰 | `checkbox.disable` / `selection.disable`「**仅更新卡片时有效**」 | [101032](https://developer.work.weixin.qq.com/document/path/101032) |

> **对评审 note §E 第 2 档的裁定**：它建议「先做 spike 实测同 task_id 能否覆盖原卡」。
> **不用做这个 spike 了——官方文档已经给了答案：不存在这个机制，task_id 反而要求唯一。** 该 spike 大概率白做。
> 正确的问题不是「怎么更新那张卡」，而是「**怎么让业务动作短到不需要在窗口外更新**」。

### 3.5 ⚠️ 频率限制：官方**有**声明（评审 note 与你的任务书都以为没有）

| 场景 | 限制 | URL |
|---|---|---|
| **aibot 对同一会话** | 「无论是**回复还是主动推送**消息，总共给某个会话发消息的限制为 **30 条/分钟，1000 条/小时**」（合并计数） | [101463](https://developer.work.weixin.qq.com/document/path/101463) |
| aibot 并发 | 「用户跟同一个智能机器人最多同时有**三条**消息交互中」 | [100719](https://developer.work.weixin.qq.com/document/path/100719) |
| aibot 长连接 | 每个机器人同一时间只能一个有效长连接；心跳建议 30 秒 | [101463](https://developer.work.weixin.qq.com/document/path/101463) |
| 群机器人 webhook（**本项目不走这条**） | 20 条/分钟 | [91770](https://developer.work.weixin.qq.com/document/path/91770) |

**生产实测佐证**：2026-08-25 16:13 一分钟内向同一个群 chatid 连发了 **16 张**业务卡（`app.wecom_business_cards`，12 张 SENT + 4 张失败），
未见频率错误——与 30 条/分钟的官方口径一致。

---

## 4. 仓库现有实现：已验证可用的字段集合

### 4.1 传输层（已跑通）

| 事实 | 位置 |
|---|---|
| `TEMPLATE_CARD` 消息类型存在 | `WecomOutboundMessage.java:24`；构造期强校验 `card_type` 非空、必须是 JSON 对象、不得同时带 text/media（`:47-56`） |
| 发送走 `aibot_send_msg`，body 挂 `template_card` | `WecomLongConnectionClient.java:335,343` |
| 更新走 `aibot_respond_update_msg`，透传事件 `req_id` | `WecomLongConnectionClient.java:309-316` |
| 更新帧走 `INTERACTIVE` 优先级，插在普通业务帧之前 | `WecomLongConnectionClient.java:314` |
| **`sendFrame` 至今零业务调用方**（评审 note §A 的纪律守住了） | `WecomLongConnectionClient.java:319` |

### 4.2 现有 4 张卡实际用到的字段（这就是「已验证可用」的全集）

| 卡 | 卡型 | main_title | desc | sub_title_text | horizontal_content_list | button_list | card_action |
|---|---|---|---|---|---|---|---|
| `BatchConfirmedCard` | `text_notice` | 「整批确认已完成」 | `batchNo` | 确认人 + 来源渠道 | 订单 / 京东 / 导出 共 **3** 项 | ⚠️ 1 个 jumpButton（**该卡型无此字段**） | detailUrl |
| `ReviewCaseCard` | `button_interaction` | — | — | — | — | 我来处理(回调) + ⚠️去后台处理(jump) | detailUrl |
| `OperationalAlertCard` | `button_interaction` | — | — | — | — | 知道了(回调) + ⚠️去处理(jump) | detailUrl |
| `JdOutboundFailureCard` | `button_interaction` | — | — | — | — | 重试建单(回调) + ⚠️去对账(jump) | reconUrl |
| **`OrderDraftCardRunner.card()`**（生产参照） | `button_interaction` | 「订单草稿待确认」 | 草稿号·行数·就绪度 | **未用** | **未用** | 确认订单 + 需要补充（**均为回调，无 jump**） | **未用** |

> ⚠️ 注意 `OrderDraftCardRunner.card()`（`OrderDraftCardRunner.java:106-120`）**手搓 JSON，绕过了 `WecomCardBuilder`**——
> 它是唯一一张**完全符合 aibot Button 规格**的卡（只有 text/key/style），恰恰因为它没用 builder。

### 4.3 ⚠️ 生产投递证据：`button_interaction` 从未被平台成功 ACK 过

`app.wecom_business_cards`（生产，23 行）：

| 域 | 卡型 | 状态 | 条数 | 失败原因 |
|---|---|---|---|---|
| `batch` | `text_notice` | **SENT** | **15** | — |
| `batch` | `text_notice` | FAILED | 1 | CONNECTION_NOT_READY |
| `review` | `button_interaction` | FAILED / UNKNOWN | 2 / 4 | CONNECTION_NOT_READY / CONNECTION_LOST_AFTER_SUBMIT |
| `alert` | `button_interaction` | UNKNOWN | 1 | CONNECTION_LOST_AFTER_SUBMIT |

- 全部 23 张的 `route_type` 都是 **GROUP**，**单聊路由生产零使用**。
- 失败全是**传输层**（连接未就绪 / 提交后断线），**不是协议拒绝**——所以 §2.2 的 A/B/C 三处偏差**既没被证实也没被证伪**。
- `app.wecom_events` 生产共 **7** 行，**全部是 `disconnected_event`**：⚠️ **`template_card_event` 在生产从未发生过一次**。
  `docs/agents/wecom-order-draft-cards.md:53` 自己也写了「生产启用前仍须在实际企微机器人中完成一次真实点击验收」——**这个验收至今没做**。
- `app.wecom_order_draft_cards` 生产 **0 行**：订单草稿卡从未发出过。

---

## 5. 多单场景：方案对比

**先看生产真实规模**：`app.import_batches` 共 16 个批次，`raw_import_rows` 每批 **0 / 1 / 2** 行，**最大 2 行**。
「一次 20 单」目前是**假想负载，不是观测事实**。

| 方案 | 优点 | 缺点 | 刷屏风险 | 频率限制风险 |
|---|---|---|---|---|
| **A. 一单一卡（N 张）** | 每单信息完整；地址能独占 `sub_title_text`；确认粒度 = 业务粒度，天然幂等 | 20 单 = 20 张卡 + 20 次点击；手机上要滑很久；漏点一单无从察觉 | ⚠️ **高**（20 张卡刷屏） | ⚠️ 20 张卡本身 < 30/分钟，但官方口径是「**回复与主动推送合计** 30 条/分钟」——卡片 update 与结果播报同吃这个额度，一分钟内容易摸到上限；`WecomBusinessCardWorker` 无节流 |
| **B. 一批一卡 + 深链看明细** | 1 张卡 1 次点击；不刷屏 | ❌ **深链四层全断**（§7）：内网 IP 打不开、四条链接本身是坏的、手机上完不成确认、**且认不出点击人**。卡上只有数字 → 退化成盲签 | ✅ 无 | ✅ 无 |
| **C. 一批一卡 + 卡内前 N 单摘要 + 「看全部」回调追发文字** | 1 张卡；少量单（生产实测 ≤2）时**信息完整**；多单时至少能看见前几单 | `horizontal_content_list` 只有 6 行 × 26 字 = 156 字预算，1 单就要吃掉 3~4 行 → **卡内最多摘要 1~2 单**；第 3 单起要靠追发的文字明细（**不能靠深链**，§7.4） | ✅ 无 | ✅ 无 |
| **D. 一批一卡 + `button_selection` 下拉逐单确认** | 官方支持 ≤10 选项；1 张卡内选单 | ⚠️ 选择结果只在**点提交按钮时**随 `selected_items` 回传，**选中后卡面不会自动展开该单详情**（要展开就得 update，又落回 5 秒窗口）；仓库 `CardEventInput`（`WecomOrderDraftCardInteractionService.java:291-303`）**根本没解析 `selected_items`** | ✅ 无 | ✅ 无 |

**建议：C 为主，按批次行数自适应降级到 A。** 生产真实批次 ≤2 单，C 在真实负载下就是「全量可见」；
只有在假想的 20 单场景才退化成「前 2 单 + 折叠」；⚠️ **折叠的出口不能是深链**（§7.4），只能是「回调按钮 → 5 秒内追发一条文字明细」。

---

## 6. 交互与回调的现实约束

### 6.1 5 秒窗口：现有订单草稿卡是**怎么绕过去的**（重点，这是最好的参照）

**它没有解决「5 秒后更新原卡」，它是让业务动作短到不需要更新。**

| 环节 | 实现 | 位置 |
|---|---|---|
| 本地预算 4.5s（给发送留 500ms） | `UPDATE_CARD_BUDGET_NANOS = 4_500_000_000L` | `WecomMessageDispatchHandler.java:44,149` |
| 绝对 deadline 从 **listener 收帧时刻**起算，不因线程切换重置 | `onFrame(cmd, frame, receivedNanos)` → `deadlineNanos = startedNanos + BUDGET` | `WecomMessageDispatchHandler.java:79,187` |
| **业务确认同步跑在回调线程上**（⚠️ 与评审 note §G.2「业务写必须扔进 AsyncTaskStore」**相反**） | `outcome = cardInteractions.handle(frame)` 直接调用，没有异步 | `WecomMessageDispatchHandler.java:153` |
| 之所以敢同步：**`confirm` 是纯 DB 事务，不打任何外部网络** | `OrderDraftCardConfirmationService.confirm` → `orderDrafts.confirm(...)`，全程无 HTTP | `OrderDraftCardConfirmationService.java:137-141` |
| 超预算就直接判超时，**绝不在窗口后补发** | `elapsed >= BUDGET → TIMED_OUT / FAST_PATH_DEADLINE_EXCEEDED` | `WecomMessageDispatchHandler.java:195-197` |
| update 不成功 → **发一条纯文字兜底**（不是新卡） | `sendFallback(outcome)` → `WecomOutboundMessage.text(...)` | `WecomMessageDispatchHandler.java:217-221, 270-290` |
| 三段观测分开落库：`update_status` / `fallback_status` / `processing_status` | — | `docs/agents/wecom-order-draft-cards.md:45-49` |
| 交互回调走**独立 4 并发快通道**，不排在普通回调后面 | `interactiveFrameHandlerExecutor` | `WecomLongConnectionClient.java:398` |

> **所以「超过 5 秒还能不能更新原卡」的答案是：不能，而且生产实现根本没试图更新——它降级发文字。**
> `response_url`（1 小时 / 1 次，可发新 `template_card`）是官方给的另一条追发路径，
> ⚠️ **本仓库完全没用**：全库 grep `response_url` 只在两个测试的脱敏断言里出现（`TrackingDraftApiTest.java:2134`、`MessageInterpretationApiTest.java:913`）。

### 6.2 ⚠️ 发货前确认卡片和订单草稿卡的**根本差异**：确认会打网络

| | 订单草稿卡（已上线） | 发货前确认卡（本需求） |
|---|---|---|
| 点确认后做什么 | 写 `orders`（纯 DB） | 整批确认 → **N 次京东 HTTP 建单** |
| 调用链 | `orderDrafts.confirm` | `SourceImportController.java:53` `service.confirm(...)` 然后 **`:62` `service.submitJdOutboundsForBatch(...)`** |
| 京东建单形态 | — | `SourceImportService.java:1048-1071`：**for 循环逐个 shipment 顺序调 `shipmentJdOutboundService.submit(...)`** |
| 能否进 4.5 秒窗口 | ✅ 能 | ❌ **不可能**（N 次外网 HTTP 串行） |

**这就是为什么 §1 的「拆成授权 + 建单两段」不是优化，是前提。**

### 6.3 `task_id` / `event_key` 语义（仓库实现）

| 项 | 实现 |
|---|---|
| 业务化格式 `{domain}:{id}:v{version}` / `:g{gen}` | `WecomTaskId.java:41` 正则 `^([a-z][a-z-]{0,31}):([0-9]{1,19}):([vg])([0-9]{1,19})$` |
| task_id 本身即版本断言 | `WecomTaskId.matchesCurrent(...)` `:91-93` |
| 长度上限 128 字节，超限抛异常 | `WecomTaskId.java:39,97-99`（与官方一致） |
| ⚠️ 但订单草稿卡**用的是另一套格式** `order-draft:{id}` | `WecomOrderDraftCardInteractionService.java:307` 正则 `order-draft:[1-9][0-9]*`，**没有版本段** |
| `event_key` 约束 | `WecomCardBuilder.java:137` `^[a-z][a-z0-9_]{0,63}$`（比官方 1024 字节严得多，安全方向） |

### 6.4 ⚠️ 三个「现在点了也没用」的门闩（必须先解）

| # | 门闩 | 证据 | 后果 |
|---|---|---|---|
| ⚠️1 | **`review` / `alert` / `jd-outbound` 三张卡的回调压根没接线** | `WecomMessageDispatchHandler.java:140` 把**所有** `template_card_event` 都交给 `cardInteractions`（订单草稿专用）。该服务在 `:301` 用 `draftId(taskId)` 解析，正则只认 `order-draft:N` → `review:6:v0` 解析为 null → `:165-172` 返回 `REJECTED / WECOM_CARD_TASK_ID_INVALID` → 卡片被更新成**「卡片操作被拒绝」**（`WecomMessageDispatchHandler.java:317`） | 今天点「我来处理」/「知道了」/「重试建单」，得到的是**拒绝**。新卡若沿用 `WecomTaskId` 格式，**会掉进同一个坑** |
| ⚠️2 | **京东建单有操作人白名单，卡片操作人必然不在其中** | `ShipmentJdOutboundService.java:630-640` `requireAuthorized`：要求 `authenticatedOperator == operator` **且**在白名单内。生产 `JD_OUTBOUND_AUTHORIZED_OPERATORS=zimu-admin`。卡片路径的 context 是 `new CommandContext(requestId, requestId, "wecom:"+userid, "wecom:"+userid)`（`OrderDraftCardConfirmationService.java:135`） | `"wecom:xxx"` ∉ `{zimu-admin}` → **403 `JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED`**。⚠️ 而 `app.internal_operators` 生产 **0 行**，连「谁是谁」都还没登记 |
| ⚠️3 | **单聊目标 userid 无处可取** | 生产三个路由 env 全指向**同一个群** `WECOM_CARD_{BATCH,ALERT,JD_OUTBOUND}_CHAT_ID`（同一个 `wr...` 群 chatid），无 `ROUTE_TYPE` 覆盖 → `WecomBusinessCardRouteProperties.java:44-46` 缺省 **GROUP**。`internal_operators` 0 行 | 「发给操作员本人（单聊）」**当前没有数据支撑**；且 `docs/agents/operator-wecom-userid.md` 提示「首次使用前先与机器人打招呼」 |

---

## 7. 深链方案可行性：**四层全断，不是「配一下就好」**

### 7.1 ⚠️ 第 1 层：地址本身

| 事实 | 证据 |
|---|---|
| **仓库里从未配过值** | `.env.example:107` 空、`docker-compose.yml:116` `${WECOM_BUSINESS_CARD_BASE_URL:-}`、`application.yml:130` `${...:}` 均默认空；`git log --all -S` 只有 2 个提交，**历史上任何分支都没写过真实值** |
| **生产配的是内网 IP** | `docker inspect zimu-fulfillment-backend-1` → **`WECOM_BUSINESS_CARD_BASE_URL=http://192.168.1.22`**（经 `.env`，不在 git 里） |
| 未配置时的行为是「宁可不跳」 | `CardDeepLinks.java:17-25`：blank → 字段置 `null`，`of(path)` 返回 **`null`**；四张卡都有 `detailUrl != null && !isBlank()` 守卫（`BatchConfirmedCard.java:56` 等）→ 跳转按钮与 `card_action` 整块跳过。`.env.example:106` 注释：「宁可不跳，也不发点了 404 的链接」 |
| 没有任何隧道 / 穿透 | 全仓 grep `ngrok|cloudflared|frpc|frps|localtunnel|tunnel` **零命中** |
| 没有 TLS | `docker/nginx/default.conf:29` 只有 `listen 80`；`ssl_certificate` / `443` / `letsencrypt` / `certbot` 全仓零命中 |
| **只绑回环** | `docker-compose.yml:264` `"${APP_BIND_ADDRESS:-127.0.0.1}:${APP_PORT:-8088}:80"`；`README.md:27` 明文裁决：「若需对其他主机发布，**必须先实施真实用户认证/授权与 HTTPS 终止**，再显式调整 `APP_BIND_ADDRESS`」 |
| 即使可达，边缘还有 Basic Auth | `docker/nginx/default.conf:35` `include edge-auth.inc`，默认 `GATEWAY_BASIC_AUTH_ENABLED=true`；豁免只有 `/healthz` 与 `/wecom/callbacks/`。深链目标 `/workbench/*`、`/fulfillment/*` 走 `location /`（`:130-135`）→ **手机上会弹 HTTP Basic 登录框** |

> **架构补充**：企微入站早已从 Webhook 换成智能机器人**长连接主动外拨**（`WecomProperties.java:16` `wss://openws.work.weixin.qq.com`），
> 所以「公网入口」从来就**不是**这个系统的需求——不是漏配，是架构上没有。

### 7.2 ⚠️ 第 2 层：四条深链**即使地址通了也全是坏的**

| 卡片源 | 生成路径 | 前端实况 |
|---|---|---|
| `ReviewCaseCardSource.java:87` | `/workbench/review-inbox?case_no=` | ❌ **路由根本不存在**。`frontend/src/routes.tsx:69-118` 无此项 → 命中 `App.tsx:19` 的 `path="*"` → **静默重定向 `/dashboard`**，`case_no` 丢失。真实路由叫 `/workbench/reviews`（`routes.tsx:71`） |
| `OperationalAlertCardSource.java:66` | `/workbench/alerts?alert_no=` | ⚠️ 路由在，但 `AlertsQueuePage.tsx:28` 只读 `status`，`alert_no` **被忽略** |
| `BatchConfirmedCardSource.java:83` | `/fulfillment/shipments?batch_no=` | ⚠️ 路由在，但 `ShipmentsPage.tsx` **完全没有 `useSearchParams`**，筛选是 `useState`（`:257-260`），`batch_no` **被忽略** |
| `JdOutboundFailureCardSource.java:63` | `/fulfillment/outbound-recon?erp_delivery_no=` | ⚠️ 路由在，但 `OutboundReconPage.tsx:325-326` 读的是 `query_type`/`query_value`，`erp_delivery_no` **被忽略** |

**没有任何测试守住「后端深链 ↔ 前端路由」这条契约。**

### 7.3 ⚠️ 第 3 层：前端在手机上完不成「确认」动作

栈：React 18 + Vite + **Ant Design 5**（`frontend/package.json:16-22`）；**无 Tailwind**（`sm:`/`md:`/`lg:` 命中数 0）。

| 方向 | 证据 |
|---|---|
| ✅ viewport meta 写法正确 | `frontend/index.html:9` `width=device-width, initial-scale=1.0` |
| ✅ 有若干断点兜底 | `shell.css:341`（1080px）、`global.css:22,28`、`analytics.css:370,380` 等 |
| ✅ 32 处 Table 都配了 `scroll.x`（`DataTable.tsx:22` 默认 `{x:960}`），表格横滑不撑破 body | — |
| ❌ **S1：手机上没有任何导航** | `shell.css:341-344` 把 `.zs-side` 整个 `display:none`，而 `AppLayout.tsx:100-166` **没有汉堡按钮、没有抽屉菜单、没有顶栏**。全局搜索另一入口是 ⌘K（`:83`），手机不可能触发。**从卡片落到某页后，去不了任何其他页** |
| ❌ **S2：确认表单被裁在屏幕外** | 订单草稿确认跑在 `ReviewCaseDrawer.tsx:401` 的 **`width={920}`** 抽屉里（antd Drawer 是 `position:fixed;right:0`），375px 屏上左侧约 545px 被裁且滚不到。抽屉内还硬编码 `'1fr 1fr'` 两列（`OrderDraftReviewPanel.tsx:420,429`——**正是收货人/电话/省/市/区/街道那几格**）。同类固定宽抽屉共 11 个 |
| ❌ **S3：真正横向溢出** | `workbench.css:441-445` `.zs-pgrid{grid-template-columns:repeat(auto-fit,minmax(424px,1fr))}`，424px > 375px 屏可用宽。**`workbench.css` 575 行、承载全部工作台 UI，零个 `@media`** |
| ❌ **S4：触控目标偏小** | `saasTheme.ts:121` `controlHeight:34`（低于 iOS HIG 44pt）、`:154` `Menu.itemHeight:32`、`:156` 字号 13.5px |
| ❌ **S5：代码里零移动端意识** | `matchMedia`/`useMediaQuery`/`innerWidth`/`useBreakpoint` 在 `frontend/src/` 命中数 **0** |

**这不是疏漏，是有记录的范围裁决**——`docs/PLAN-restart-20260823.md:55-56`：「**窄屏/响应式：本轮不做。** 侧栏 <1080px 直接隐藏…不做折叠态、不适配 iPad」。

且**没有任何页面是为「从卡片深链进来、只做一件事、然后离开」设计的**——所有确认动作都藏在抽屉/面板里，无独立路由（订单草稿确认 `OrderDraftReviewPanel.tsx:525-530`、整批确认 `ShippingWorkbenchPage.tsx:72,88,214`、京东地址批量确认 `ShipmentsPage.tsx:165,193`）。

### 7.4 ⚠️⚠️ 第 4 层（最致命）：深链页面**认不出是谁点开的**

| 事实 | 证据 |
|---|---|
| **全仓没有任何企微网页授权** | `oauth2/authorize` / `getuserinfo` / `wwauth` / `jsapi` 在 `backend/src` 与 `frontend/src` **零命中** |
| 前端也不是 session/JWT 登录 | `frontend/src` 下**没有任何 login/auth/session 文件**；`api/client.ts:6`「操作人身份由受信网关覆盖，浏览器不得提供 `X-Operator`」；`writeHeaders.ts:20` 把 `authorization`/`x-operator` 列为保留字直接过滤 |
| 身份由 nginx 注入，**全局共享一个账号** | `docker/nginx/entrypoint.sh:34-40` 用 `APP_ADMIN_USER/PASSWORD` 生成 `.htpasswd` 与 `proxy_set_header X-Operator`；`README.md:25` 明说是「**单一共享 Basic 凭据**」，`:27`「**does not provide per-user attribution**」 |
| 前端的 `workbenchRole` 不是身份 | `frontend/src/workbenchRole.ts:4` 自述：「它不是身份也不是权限：不发请求、不进 URL、不进请求头；存 localStorage」 |

**对比——回调按钮反而有身份**：入站 `template_card_event` 带 `from.userid`（`WecomOrderDraftCardEventStore.java:37,58,280`），且 `internal_operators.wecom_userid` 映射表已就绪。

> ### 🔑 这条不对称性直接决定了方案选型
>
> **「谁确认的」这件事，在回调按钮上可解，在深链网页上不可解。**
> 发货前确认是**有外部副作用（真建京东单）的授权动作**，审计主体必须是具体的人。
> 走深链，所有确认都会记成同一个共享管理员——**这在审计上不成立**。
> 所以本需求**必须走回调按钮，不能走深链**；深链最多只能当「只读看详情」的补充，而它今天连这个都做不到。

## 8. 推荐方案

> 以下**全部是建议**，未经评审、未实施。

**三个方案的取舍一览：**

| | 方案一 一批一卡 | 方案二 一单一卡 | 方案三 只读预检卡 |
|---|---|---|---|
| 满足「不在电脑前也能办」 | ✅ | ✅ | ❌ |
| 信息完整度 | ⚠️ ≤2 单完整，超出折叠 | ✅ 完整 | ✅ 完整 |
| 需要解 §6.4 门闩 | 全部 3 个 | 全部 3 个 | **0 个** |
| 需要新建 DB 表 | 是（授权表） | 是 | 否 |
| 触及真实京东写（`JD_LOP_WRITE_MODE=ON`） | 是 ⚠️ | 是 ⚠️ | 否 |
| 频率限制风险 | ✅ 无 | ⚠️ 20 单会超 30 条/分钟 | ✅ 无 |
| 建议 | **主方案** | 20 单场景成真后再上 | **先做，当方案一的第 0 步** |

⚠️ **共同前置（方案一/二）**：§6.4 的三个门闩必须先解——否则代码写完，点了是 `WECOM_CARD_TASK_ID_INVALID` 或 403。

### 方案一（推荐）：一批一卡 · 授权/建单两段式

**卡型**：`button_interaction`。`task_id = release:{batchId}:v{batchVersion}`（复用 `WecomTaskId`）。

| 业务字段 | 落哪个区块 | 超长怎么办 | 生产实测长度 |
|---|---|---|---|
| 「发货前确认」+ 批次号 | `main_title.title`（≤26） | 批次号本身短，够用 | — |
| 渠道 + 单数/行数 + 金额 | `main_title.desc`（≤30） | 例：`飞象 · 2 单 2 行` | 8 |
| **收货地址（最终发京东的那份）** | **`sub_title_text`（≤112）** | 生产最长 46 字，**余量充足**；多单时只放第 1 单，其余靠 §5-C 折叠 | 25 / 44 / 46 |
| 收货人 | `horizontal_content_list[0]` keyname=`收货人` | 生产最长 3 字 | 1~3 |
| 渠道订单号 | `[1]` keyname=`渠道单` | **用 `source_ref`（20 字）不要用 `order_no`（36 字必截断）** | 20 vs 36 |
| 渠道商品名 | `[2]` keyname=`渠道品` | 生产 14 字，够 | 14 |
| **京东商品 + 数量** | `[3]` keyname=`京东品` | `筋头巴脑(500g) ×2` = 13 字，够；若要带 `goodsNo`（16 字）则 `EMG4418705676249 ×2` = 20 字，也够，**但两者都放会超** | 10 / 16 |
| 省市区 | `[4]` keyname=`省市区` | `天津天津市滨海新区` = 9 字 | 9 |
| 手机号 | `[5]` keyname=`电话` | 11 位固定 | 11 |

**按钮**（≤3，全部零参数回调，**不放 jumpButton**）：`[确认发货](style=1)` `[驳回](style=2)` `[看全部](style=3, 回调→追发文字明细)`

**PII 投影**：这张卡**必须**带姓名/手机/详址，是全系统第一张携带收件人 PII 的卡。
⚠️ 因为 `render()` 拿不到 Route（§2.1），**不能沿用四张业务卡的「省略式白名单」**。建议：
① 路由**硬编码为 SINGLE**，在 source 里显式拒绝 GROUP（`route()` 返回 GROUP 时直接不入队并记 INFO）；
② PII 字段走 `V51` outbox 那套 **DB 级 `jsonb_build_object` 固定列白名单**（`V51__wecom_business_notification_outbox.sql:173-194` + `WecomBusinessNotificationRunner.java:160-217`）；
③ 单测断言**具体的中文值**不出现在群路由渲染结果里，而不是断言英文 key（现有 `BusinessCardRenderingTest.java:123-129` 那种写法拦不住）。

⚠️ 「看全部」故意**不做跳转按钮**——两个独立原因：aibot Button 结构体没有 url 字段（§2.2-A），且深链四层全断（§7）。改成回调 → 5 秒内追发一条 markdown 文字明细。

**回调时序**：

```
点击 → listener 收帧(t0) → INTERACTIVE 快通道
  → [DB, 毫秒级] 写 batch_release_authorizations(batch_id, version, actor=from.userid, 幂等键=msgid)
     ⚠️ actor 只能来自回调 from.userid（缺失即拒绝，不用 chatid/昵称伪造，照抄订单草稿卡语义）
  → [DB] 入队 AsyncTask: JD_BATCH_OUTBOUND
  → t0+4.5s 内 aibot_respond_update_msg：卡片改成「已授权发货 · 操作人 X · 建单中…」，按钮消失
  → 失败则文字兜底（沿用 WecomMessageDispatchHandler:217-221）
--- 窗口关闭，原卡再也不动 ---
  → 异步 worker 顺序调京东 submit（复用 SourceImportService:1048-1071 的逐条幂等）
  → 结果追发【BatchConfirmedCard】(text_notice) —— 这张卡生产已 15/15 SENT，是唯一被验证过的卡
```

**它解决不了什么**：
- ❌ **多于 2 单时看不全**（6 行 × 26 字预算写死）。第 3 单起只有数字，没有明细。
- ❌ **驳回之后怎么改仍然要回电脑**——卡上没法改地址、改数量（要参数的动作 aibot 卡做不了，`button_selection` 只能选不能填）。
- ❌ **建单结果卡是「事后播报」**，失败了还是要人回后台（而后台手机上打不开）。
- ❌ 5 秒内那次 update 只是「收到了」，**不是「京东建单成功了」**——用户看到「已授权」不等于货真的发出去了。

### 方案二：一单一卡 · 逐单确认

**卡型**同上，`task_id = release-order:{orderId}:v{version}`。字段排布同方案一，但**每张卡只描述一个订单** → `sub_title_text` 独占给这一单的地址，`horizontal_content_list` 6 行全部给这一单。

| 优点 | 代价 |
|---|---|
| 信息**完整无截断**，真正做到「看清楚再点」 | 20 单 = 20 张卡 + 20 次点击 |
| 确认粒度 = 业务粒度，部分确认天然成立 | ⚠️ 官方 **30 条/分钟**且「**回复与主动推送合计**」（[101463](https://developer.work.weixin.qq.com/document/path/101463)）。20 张卡本身没超，但**卡片 update 与结果播报都计进同一个额度**——20 卡 + 若干 update + 播报卡很容易在一分钟内摸到 30。而 `WecomBusinessCardWorker` **现在没有任何节流**（生产已实测过一分钟内连发 16 张） |
| 单张卡失败不影响其他 | 手机上滑动成本高，**漏点一单无提示** |

**它解决不了什么**：刷屏；「这一批到底还剩几单没确认」没有全局视图；限速逻辑是新增复杂度。

### 方案三（最小可行 / 先验证协议）：只发一张只读预检卡

**不做确认按钮**，`text_notice` + `card_action`，把拉取结果的「最终发京东的样子」播报出来，人**回电脑**确认。

| 优点 | 代价 |
|---|---|
| **零门闩**：不碰 §6.4 的白名单、不需要单聊 userid、不需要回调接线 | ❌ **完全没满足「不在电脑前也能办」** |
| 复用生产唯一被验证的路径（`text_notice`，15/15 SENT） | 只是把信息提前给人看，决策仍在电脑上 |
| 可作为**协议 spike 载体**：一次性验掉 §9 的第 1/3/7 条 —— `sub_title_text` 112 字真能渲染 44 字地址、`jump_list` 是否可用、`text_notice` 的 `button_list` 到底渲不渲染 | — |
| — | ⚠️ `text_notice` 的 `card_action` 是**必填**，而现有四条深链全是坏的（§7.2）。要么先修 `ShipmentsPage` 的 `useSearchParams`，要么接受它落在一个忽略参数的列表页 |

**建议把方案三当作方案一的第 0 步**：它能在不动任何业务语义的前提下，把 §9 里一半的「未核实」变成事实。

---

## 9. 我**没能核实**的部分（明确列出，未猜测）

| # | 未核实项 | 为什么没核实 | 影响 |
|---|---|---|---|
| 1 | **超过「建议字数」会被平台拒绝，还是只是渲染截断** | 官方措辞是「建议」，未给错误码；生产无越界样本 | 决定「截断」还是「放行」的策略 |
| 2 | **`button_list` 里多传 `type`/`url` 会怎样**（§2.2-A） | 生产 `button_interaction` 卡**从未成功 ACK 过**，无证据 | 4 处 `jumpButton` 的实际行为 |
| 3 | **`text_notice` 带 `button_list` 时按钮渲不渲染**（§2.2-B） | 15 张卡 errcode=0，但**没人看过手机上长什么样** | 「查看批次」按钮是否形同虚设 |
| 4 | **真实点击回调是否携带 `from.userid`** | `app.wecom_events` 生产 7 行**全是 disconnected_event**，`template_card_event` 零发生。`docs/agents/wecom-order-draft-cards.md:53` 自己标注此验收「上线前仍须完成」——**至今未做** | ⚠️ 若不带 userid，整个「谁确认的」链条塌方（系统设计为拒绝，不伪造 actor） |
| 5 | **`quote_area.title` / `quote_text` 的字数上限** | 官方两套文档均**未声明** | 它是理论上的第二个长文本落点，不敢用 |
| 6 | **手机企微内置浏览器对 `http://`（非 https）的处理** | 无法在此环境实测 | 深链方案的附加风险（深链已因 §7.4 被否，影响下降） |
| 7 | **`sub_title_text` 在手机端渲染 112 字时是否换行 / 折叠 / 被「展开」吞掉** | 生产从未发过带 `sub_title_text` 的 `button_interaction` 卡，无截图 | 44 字地址方案的唯一落点，值得在方案三里先验 |
| 8 | **`aibot_send_msg` 主动推送是否有独立频率限制** | 101463 只写了「回复与主动推送**合计** 30/分钟」，无独立数值 | 方案二的限速阈值 |
| 9 | **同 task_id 重发新卡的实际行为** | 官方未声明，也无对应错误码 | 已建议放弃该路线（§3.4），故影响可控 |
| 10 | **20 单场景的真实存在性** | 生产 16 个批次最大 2 行；用户口述「可能 20 单」未被数据证实 | 多单方案的优先级 |

---

## 10. 附：生产实测数据（2026-08-25，只读）

| 数据 | 值 |
|---|---|
| `app.orders` / `app.shipments` / `app.import_batches` | 4 / 3 / 16 |
| 单批次 `raw_import_rows` 行数 | 0 / 1 / **2**（最大 2） |
| `receiver_address_snapshot` 字符数 | 25 / **44** / 46 |
| 人工确认后 `jd_receiver_detail_address` 字符数 | 17 / 22 / **30** |
| 三条 shipment 的 `jd_receiver_confirmed_by` | 全部 `zimu-admin`（**都被人工改过**，印证 `ShipmentJdOutboundPreparer.java:269-271` 的注释） |
| 飞象真实行样例（`raw_import_rows.id=9`） | 订单号 `D2026825436037811697`(20) · 商品名 `子牧原切牛腩块500g*2`(14) · 可发货数量 1 · 地址 44 字 |
| 对应京东侧 | `goodsNo=EMG4418727173231`(16) · 品名 `牛腩块(500g)`(10) · **`mapping_multiplier=2` → 京东发 2 件** |
| ⚠️ 京东写门闩 | `JD_LOP_WRITE_MODE=ON`（**指向生产，任何确认都是真建单**） |
| `app.internal_operators` | **0 行** |
| `app.wecom_events` | 7 行，全部 `disconnected_event` |
| `app.wecom_order_draft_cards` | **0 行** |

---

## 参考

**官方（企业微信开放文档）**
- [模板卡片类型（智能机器人）101032](https://developer.work.weixin.qq.com/document/path/101032) ← **本项目该看的那份**
- [被动回复消息（智能机器人）101031](https://developer.work.weixin.qq.com/document/path/101031)
- [接收事件（智能机器人）101027](https://developer.work.weixin.qq.com/document/path/101027)
- [接收消息（智能机器人）100719](https://developer.work.weixin.qq.com/document/path/100719)
- [主动回复消息 / response_url 101138](https://developer.work.weixin.qq.com/document/path/101138)
- [智能机器人长连接（5 秒窗口 + 频率限制）101463](https://developer.work.weixin.qq.com/document/path/101463)
- [消息类型及数据格式（**应用消息**，数值不同，勿照抄）90236](https://developer.work.weixin.qq.com/document/path/90236)
- [更新模版卡片消息（应用消息，response_code 72 小时）94945](https://developer.work.weixin.qq.com/document/path/94945)
- [访问频率限制 90312](https://developer.work.weixin.qq.com/document/path/90312)
- [群机器人 webhook（20 条/分钟，本项目不走）91770](https://developer.work.weixin.qq.com/document/path/91770)
- [WecomTeam/aibot-node-sdk](https://github.com/WecomTeam/aibot-node-sdk)

**仓库内部**
- `docs/agents/wecom-card-review.md`（#87/#88/#90 开工前评审）
- `docs/agents/wecom-order-draft-cards.md`（**最好的参照**：5 秒快路径的完整语义）
- `docs/agents/operator-wecom-userid.md`（#89 运营人员 ↔ userid）
- `docs/PLAN-restart-20260823.md:55-56`（「窄屏/响应式：本轮不做」的范围裁决）
- `README.md:25,27`（单一共享 Basic 凭据，无 per-user attribution）
