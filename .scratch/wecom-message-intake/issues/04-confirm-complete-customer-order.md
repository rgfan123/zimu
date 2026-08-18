# 04 — 确认一条完整客户订单

**What to build:** 一条字段完整、单客户、单收货地址的文字需求能够生成可追溯的订单草稿和一个人工复核事项；运营人员选择已有 Customer 与 SKU、核对字段并确认后，系统通过现有业务用例创建真实 CanonicalOrder。

**Blocked by:** 03 — 异步解释消息并完成基础意图分流.

**Status:** resolved

**Claimed by:** codex-main → zed-agent (2026-08-14 接手收口：OrderDraftApiTest 12/12、ReviewCaseResolutionApiTest 4/4 全绿，前端 OrderDraftReviewPanel/ManualReviewPage 存在且前端 155/155 通过；后端闭环从消息解释走到可查询 CanonicalOrder 已覆盖)

- [x] `CUSTOMER_ORDER` 解释结果生成版本化 OrderDraft、草稿行、缺失项/候选信息和一个 `ORDER_OPS / OPEN` ReviewCase。
- [x] 模型输出只包含原始客户、收货与商品描述，不把任何内部 Customer ID、SKU ID 或履约方视为已确认事实。
- [x] 确定性映射唯一命中时只产生 Customer/SKU 候选，零命中或多命中明确显示待处理；履约方来自确认后的 SKU 主数据。
- [x] 复核页面显示原始消息、模型原值、候选依据和可编辑业务字段，完整前不允许确认。
- [x] 确认命令要求幂等键、草稿与 ReviewCase 期望版本以及服务端认证的操作员身份。
- [x] 单一事务创建 WECOM CanonicalOrder、记录证据引用、标记草稿已确认、解决 ReviewCase，并写入事件、订单版本和审计日志。
- [x] 重复确认、过期版本或并发确认不会创建重复订单；拒绝草稿会 `DISMISSED` ReviewCase 并保存理由。
- [x] HTTP 与浏览器验收从消息解释走到可查询的真实订单，不直接写业务表或通过内部 HTTP 自调用业务用例。

## Answer

codex 交付的后端闭环经 zed-agent 接手验证后收口：`CUSTOMER_ORDER` 解释 → 版本化 OrderDraft + 草稿行 + ORDER_OPS/OPEN ReviewCase → 复核页（OrderDraftReviewPanel/ManualReviewPage）→ 幂等+版本+认证的确认事务创建 WECOM CanonicalOrder 并记录证据引用。验证：OrderDraftApiTest 12/12（含伪造操作人拒绝、空草稿拒绝、错误原因拒绝、多事项拒绝、stale 确认不建单、确认后可查 CanonicalOrder）、ReviewCaseResolutionApiTest 4/4、前端 155/155、全量后端 443/443。

## Comments

- 2026-08-13：由 codex-main 认领并委派给 `/root/wecom_order_04`；以公共草稿确认 API 和订单查询为测试 seam。08 暂不并行修改共享复核 seam。
- 2026-08-14：codex 额度中断，由 zed-agent 接手验证并收口（见 Answer）。

- 2026-08-13：由 codex-main 认领并委派给 `/root/wecom_order_04`；以公共草稿确认 API 和订单查询为测试 seam。08 暂不并行修改共享复核 seam。
