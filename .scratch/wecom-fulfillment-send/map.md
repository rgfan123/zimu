# 企微侧履约导出发送与回传提醒

Label: wayfinder:map

总览见 [wecom-upgrade-plan.md](../wecom-upgrade-plan.md)（企微能力升级总计划，本 effort 属于 Phase 1 T4）。

## Destination

把「第三方履约闭环」的企微侧补完：**系统通过企微主动把已生成的第三方履约导出（`发货清单` 24 列 XLSX）发送给第三方**，第三方按回传列返回物流单号（现有 `SUPPLIER_TRACKING` 链路已接收），**若按履约方 SLA 到期仍未收到回传，周期性向第三方业务群发送提醒消息**，直到全部回传或人工确认。

区别于既有 effort：`wecom-long-connection`（传输层只做被动接收+回执）、`wecom-message-intake`（业务层接收/草稿/复核）。本 effort 是**企微主动发送能力（aibot_send_msg / aibot_upload_media_*）+ 履约导出发送动作 + SLA 回传提醒**。

## 现状盘点（已核对）

| 能力 | 状态 |
|---|---|
| 履约导出生成（第三方 v1-24-columns XLSX，含收件人/电话/地址/品名/规格/单位/请求发货数量，19–24 回传列留空） | ✅ 已有（`ProviderFileService.generateThirdParty`，spec 第 9 节） |
| 生成后订单行推进 `WAITING_PROVIDER` | ✅ 已有 |
| 履约方 SLA 配置 `fulfillment_providers.tracking_sla_minutes`（Q43 已决策） | ✅ 已有（按履约方、可配置、改配置不追溯） |
| `fulfillment_exports.tracking_due_at` 生成时快照 | ✅ 已有（Q43 落地） |
| `TRACKING_OVERDUE` 幂等告警（operational_alerts，RED） | ⚠️ 设计已定（Q43），**扫描器/创建者未实现** |
| 企微主动发送（aibot_send_msg） | ❌ 未实现（协议支持） |
| 素材上传（aibot_upload_media_init/chunk/finish，512KB×100≈50MB） | ❌ 未实现（协议支持） |
| `template_card_event` 接收 | ❌ 当前按决策忽略（04），另一票负责翻转 |
| file 消息群聊可收 | ❓ 待实测（map 旧注：voice/file/video 仅单聊；官方 SDK 类型未过滤，需真实环境验证） |

## Decisions so far

- [01 — 履约导出发送与周期性回传提醒](issues/01-export-send-and-reminder.md) — 用户拍板：计时起点=企微发送时刻；提醒对象=第三方业务群（同一会话）；提醒=周期性重复（间隔可配置，直到回传或人工确认）；SLA 复用 `tracking_sla_minutes`。

## Out of scope

- 订单确认模板卡片（button_interaction / template_card_event）——另一票。
- 欢迎语、流式回复、被动媒体回复（aibot_respond_* 扩展）。
- 来源回填文件（飞象等来源平台）——既有链路，不归本 effort。
- 京东履约：京东走 SDK 直连建单，第三方始终走文件（现有决策，不改变）。
- 自动改换履约方、自动取消、超时后自动重试——Q43 明确禁止，提醒不改变订单推进机制。
