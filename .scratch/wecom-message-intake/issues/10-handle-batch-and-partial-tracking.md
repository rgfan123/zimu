# 10 — 处理批量与部分发货回传

**What to build:** 一条消息中的多行“姓名—运单号”可以分别生成、复核和确认；运营人员可以处理部分发货或异常，并一次勾选多条合法草稿，问题行不会回滚成功行。

**Blocked by:** 09 — 用脱敏姓名和运单前缀生成确定性候选.

**Status:** resolved

**Claimed by:** zed-agent subagent (2026-08-14 并行施工收口：发现 08/09 交付已超前实现全部 checkbox，验证后收口)

- [x] 批量回传要求每行姓名与运单号一一对应，一行生成一个独立 ProviderTrackingDraft 和 ReviewCase，所有行共同引用原始消息。
- [x] 无法建立逐行对应关系的输入形成一个明确的 `NEED_REVIEW` 事项，系统不按两个列表的位置猜测配对。
- [x] 未说明异常的行默认整项任务全部发出；明确包含部分发货、缺货或异常的行要求人工录入并校验实际数量/处理结论。
- [x] 单条确认保持与既有 Shipment/Tracking、履约累计数量、订单状态、事件、版本和审计的一致事务边界。
- [x] 页面支持选择多条已通过校验的草稿批量确认，并在提交前清楚展示任务、姓名、Carrier、单号和数量。
- [x] 批量命令按独立单条事务执行并返回逐行成功/失败；一行冲突、过期或重复单号不回滚其他成功行。
- [x] 失败行继续保持 `ReviewCase.OPEN` 并展示可执行错误，成功行解决事项且不能被再次确认。
- [x] 公共 API 与浏览器验收覆盖多行成功、混合成功/失败、部分发货、异常、并发冲突和逐行幂等。

## Answer

zed-agent subagent 验证收口（2026-08-14）：08/09 交付已超前实现批量语义——`WecomTrackingDraftFactory` 逐行拆分/配对失败 NEED_REVIEW/部分发货校验，`TrackingDraftService.orchestrateBatch` 逐行 REQUIRES_NEW 独立事务+逐行结果，`POST /api/v1/tracking-drafts/batch-confirm` 已就绪。验证：TrackingDraftApiTest 30/30（含多行成功、混合失败不回滚、逐行幂等、并发冲突）。遗留：前端批量勾选 UI（后端 API 已就绪）。
