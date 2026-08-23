---
status: accepted
---

# Procurement scope: price layer + receipt evidence + minimally commercialized tickets

Grilling 2026-08-23（用户选定方案 3）。基于事实：子牧采购域是"缺货任务单"——
状态机/幂等/重试/取消机制扎实，但无供应商、无价格、无回执附件，且工单总数为 0（从未真实使用）；
`procurement-price-agent` 已注册（V2 active、只读）但 0 次运行；全库无任何价格表。
同事 Demo（牛羊肉采购比价 Agent，FastAPI+Playwright）验证了采集/换算/归一化逻辑与业务规则。

## In scope

1. **价格层（Demo 移植 + 子牧化）**：每日定时采集牧集 + 肉交所肉价（移植 Demo 爬虫及
   其防错价资产：单位换算防 1000 倍、厂号归一化 SIF→N厂、白名单厂）；价格历史
   append-only 落盘；建议 = 剔除极值后对盯盘品给出（被剔除的也落盘并展示理由——
   延续同事 ADR-002"全量报盘、AI 不藏牌"与子牧 #73 excluded_candidates 机制）。
2. **回执凭证附件**：`procurement_receipts` 补附件字段，落地同事 ADR-005 铁律
   （确认到货必传海关手续图/检疫凭证）。
3. **工单最小商业化**：`procurement_tickets` 补供应商、单价两个字段——采纳建议时
   记录"最终向谁、按什么价买"，建议闭环可复盘。

## Out of scope

- Demo 的下单/入账/库存/LLM 意图路由——子牧已有或与"确定性结果不让 LLM 决定"红线冲突。
- 金额汇总/对账（已拍板 Phase 3）。
- 自动下单——延续同事 ADR-003"比价价≠订单价，AI 不碰钱"：建议只能被人采纳，
  写操作全走既有人工端点。

## 后续

grilling 结束后按本 ADR 重切票（#118 将被扩充或取代，届时票面注明）。
