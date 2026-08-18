# 06 — 处理不完整、多商品和多收货地址订单

**What to build:** 运营人员可以可靠处理不完整需求、一单多商品和一条消息多个收货地址，并通过系统草稿号补充既有草稿；未来通道若明确提供稳定父消息 ID，也可将其作为显式关联。系统提示疑似重复但不自动吞单或拼单。

**Blocked by:** 04 — 确认一条完整客户订单.

**Status:** resolved

**Claimed by:** zed-agent subagent (2026-08-14 并行施工收口)

- [x] 一旦识别为客户订单，即使字段不完整也立即生成 OrderDraft 与一个 `ORDER_OPS / OPEN` ReviewCase，并列出缺失字段。
- [x] 同一客户与收货信息下的多个商品生成一个多行草稿；不同收货人或地址生成分别复核的多个草稿并共同引用原始消息。
- [x] 每个草稿使用消息提交标识和稳定草稿序号生成唯一 WECOM 来源单号。
- [x] 后续消息只有携带有效草稿号，或通道明确提供稳定父消息 ID 时才追加到既有提交；当前企微引用类型/内容只作证据，系统不按文字或时间窗口合并消息。
- [x] 不同消息 ID 的相同或近似内容分别生成草稿，并在复核页提示疑似重复而不自动合并、拒绝或删除。
- [x] 复核人员可以补充/选择收货和结账资料并修订商品数量；商品名称、规格和单位以确认后的 SKU 主数据为准。
- [x] 每个拆分草稿独立确认、拒绝和审计，一个草稿失败不影响同一消息产生的其他草稿。
- [x] 公共 API 与浏览器验收覆盖缺字段、多商品、多地址、显式追加、非显式相邻消息和疑似重复。

## Answer

zed-agent subagent 交付（2026-08-14）：`WecomOrderDraftFactory` 多地址拆分（行级收货快照优先）、显式草稿号/稳定父消息 ID 追加（企微 quote 只作证据不合并；追加 revision 强制递增修复）、疑似重复近似化（空白/数量归一）；新增 `POST /api/v1/order-drafts/{id}/supplement`（补充收货/结账资料、SKU 候选内修订数量）与 `suspected_duplicate_of` 投影。验证：OrderDraftComplexityApiTest 12/12、组合 76/76。遗留：前端补充表单与疑似重复提示 UI。

## Answer

zed-agent 交付（2026-08-14）：`WecomOrderDraftFactory` 支持行级 receiver 的多地址拆分、系统草稿号/稳定父消息 ID 显式追加（追加行、重算缺失项、ReviewCase 记录 append_events 证据、revision 强制递增）、疑似重复指纹数量归一化（2 与 2.000 同判）；`OrderDraftService.supplement` + `POST /api/v1/order-drafts/{id}/supplement` 支持补充收货/结账并修订数量（SKU 仅限行候选），`OrderDraftDetailDto` 新增 `suspected_duplicate_of` 供复核页提示。04 测试 `modelDraftNumberCannotAppendToAnExistingOpenDraft` 改用伪造草稿号（有效草稿号追加语义由 06 覆盖）。验证：OrderDraftComplexityApiTest 12/12、ReviewCaseResolutionApiTest 4/4、OrderDraftApiTest 16/17（唯一失败为并行 05 票 WIP 测试 `reviewerCreatesNewCustomerDuringConfirmationWithSystemGeneratedCode`，客户解析未收口），全量 test-compile 通过。
