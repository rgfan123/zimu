Parent spec: #120（D1/D6）

## What to build（纯后端，勿碰 frontend/）

- [ ] `procurement_tickets` 增列：供应商、实际成交价（人工填写，任何自动路径不得写入——比价价≠订单价红线）；写入走既有工单 API 扩展，含乐观锁语义
- [ ] `procurement_receipts` 增列凭证附件引用；上传/下载复用 ContentAddressedFileStore
- [ ] **确认到货缺凭证附件即拒绝**（所有单必传，Demo ADR-005 铁律）
- [ ] 回归测试：缺凭证被拒、商业字段写入与审计留痕
- [ ] API 形状定稿后在本票评论钉死（前端 F 票消费）

## Blocked by

无（与 #121 并行）。
