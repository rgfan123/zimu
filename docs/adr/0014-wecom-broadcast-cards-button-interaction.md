# ADR 0014：播报类企微卡采用 button_interaction 而非 text_notice

- 日期：2026-08-28
- 状态：已接受
- 决策者：产品负责人、Claude

## 背景

企微 aibot 模板卡的 `text_notice` 卡型在协议上**强制要求一个安全的 `card_action` 深链**
（缺失时平台报 42045，本仓库的 `WecomCardBuilder.validateCardType` 在渲染期就会抛异常）。
而深链基址受 `CardDeepLinks.safeAbsoluteHttpBase` 门禁约束，只接受 https 或回环 http——
深链会带上业务单号进企微会话，走明文 HTTP 等于把它交给链路上的任何人，这条规则是对的。

冲突在于部署现实：本部署的公网入口只有明文 HTTP、不支持 TLS，https 基址永远配不出来。
于是三张播报卡（整批确认完成 `BatchConfirmedCard`、发货结果 `ShipmentResultCard`、
客户跟进审批终态 `BusinessFollowUpResultCard`）渲染必然失败：投递任务每 30 秒空转重试
（生产可见 `batch_49/50/51` 这类 task_id 的积压），一张卡也没发出去。

中间态修复（`fdcf2fa`，supersede 收口）把必然失败的渲染改成可诊断的终态，止住了空转——
但代价是这三张卡从此彻底不发，通知闭环缺了最后一环。

## 决策

三张播报卡改用 `button_interaction` 卡型，从根上不再需要深链：

- `button_interaction` 的 `card_action` 官方标注可选；生产里 6 张交互卡在深链基址为空的
  情况下一直正常发送，这条路径是被验证过的。深链降级为可选装饰——有基址就带整卡跳转，
  没有就只发信息，卡面完整性不依赖跳转。
- 交互卡必须有按钮：每张卡带**单个零参数、零业务写的「知道了」**回调按钮。点击由
  `WecomBusinessCardInteractionService.broadcastAck` 受理，只记日志、不产生任何业务写、
  不入队任何任务；灰化复用既有的整卡替换机制（`style=4` 灰钮 + `ack_` 前缀幂等收口，
  见 `WecomMessageDispatchHandler.updateResponse`），不另造一套。
- **播报卡不做版本断言**：`preship`/`alert` 那类按钮会改业务状态，点旧卡等于对着过期
  事实下命令，必须比对版本；播报卡没有状态可改，一次「我看到了」在哪个版本上都成立。
  拿版本去卡它，只会让读者点一张播报卡收到「这张卡已过期」——纯噪音。
- `WecomTaskId` 的域名与版本语义不变：`batch_<id>_v<version>` 等存量 task_id 继续有效，
  改卡型不破坏回调侧的版本断言契约。
- `CardDeepLinks` 的 https-only 规则**不放宽**；`textNoticeAvailable` 门闩保留为纵深防御，
  由 `TextNoticeCardSourceTest` 作为契约测试盯守——将来任何新增的 `text_notice` 卡漏配
  base-url 时，必须收口成可诊断的终态（empty → SUPERSEDED），而不是每 30 秒空转。
- 公网入口将来上了 HTTPS，配上 `app.wecom-business-card.base-url` 即自动恢复整卡跳转，
  无需再改代码。

## 考虑过的方案

- **维持 supersede 收口（`fdcf2fa` 原方向）**：止住了空转，但三张卡永远不发。播报卡是
  闭环的最后一句话（「确认之后实际发生了什么」），永久静默等于让人回后台翻结果。
- **放宽 `CardDeepLinks` 接受明文 HTTP 公网基址**：深链带业务单号，明文暴露给链路上的
  任何人。规则本身是对的，部署现实不构成放宽它的理由，否决。
- **给公网入口配 HTTPS 后按原卡型发**：入口目前只有明文 HTTP、不支持 TLS，这条路当前
  是死的。留作将来恢复跳转的增强路径，而不是现在发卡的前提。

## 影响与风险

- 播报卡带了按钮，读者可能误以为有事要做——按钮文案「知道了」与卡面「事后播报」措辞
  共同消解；点击零业务写、天然幂等，误点无害。绝不允许出现会产生第二次外部副作用的
  按钮（整批确认对应京东建单，「再点一次」正是最不该发生的事），测试逐卡钉住了
  唯一按钮就是 ack。
- 三个新域必须登记进 `WecomBusinessCardInteractionService.DOMAINS`，漏接的表现是点击
  落回订单草稿卡处理器报「无法识别这张卡片」——已有测试钉住三个域的 `handles()` 判定。
- 积压的 `batch_49/50/51` 不会自动回来：投递行停在 FAILED（围栏允许重发，且新渲染不再
  依赖深链），但对应 `async_tasks` 行已耗尽尝试、进入终态 FAILED，轮询不再认领；扫描
  也按设计跳过已建卡实体。需要一次把任务置回 PENDING 的运维动作（部署后由用户执行），
  边界由 `WecomBusinessCardPipelineIntegrationTest`
  `aCardLeftFailedByTheOldRenderCanBeResentButIsNoLongerRediscoveredByScanning` 钉住。
