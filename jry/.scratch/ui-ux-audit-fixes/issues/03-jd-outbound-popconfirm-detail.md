# 03 — 京东出库 Popconfirm 补对象明细

**Type:** implementation

**What to build:** 「提交京东出库单」的二次确认里能看到本次提交的对象明细（商户出库号、SKU×数量），REAL 模式确认标题不再只有泛化表述。

**Source:** .scratch/saas-visual-system/ux-audit.md §2 🟡 P2 #7（`ShipmentsPage.tsx` 京东出库）

**Status:** resolved

- [x] Popconfirm 内补充出库单关键信息：商户出库号、本次将提交的 SKU 明细（SKU/商品/数量，来自 preview.request 或可用数据），预检通过时明确"本次将提交以下 SKU×数量"。
- [x] 验证：tsc 0 错误、npm test 全过；截图存 output/playwright/ui-fixes/jd-outbound-popconfirm-*。

**Scope:** frontend/src/pages/fulfillment/ShipmentsPage.tsx（及专属测试）。不得改其他页面。

**Do not:** commit；修改 saasTheme.ts。

## Comments

### 明细内容与数据来源

- **商户出库号**：`preview.erp_delivery_no`（优先），缺省回退到已持久化的 `detail.jd_outbound.erp_delivery_no`。REAL 模式确认标题保留真实警示并追加「（商户出库号 ERP-UX-…）」（`jdOutboundConfirmationTitle`，shipmentJdOutbound.ts:181-191）；Popconfirm 正文固定展示「商户出库号：…」一行（ShipmentsPage.tsx:306-308），MOCK 模式同样可见。
- **SKU×数量明细**：解析 `preview.request.cargoInfos`（`jdOutboundConfirmationDetail`，shipmentJdOutbound.ts:162-179），逐行渲染「商品名（SKU xxx）× n 件」，来源即提交到京东的最终请求体，展示的就是实际提交对象。容错：无 goodsNo/goodsName 的行被丢弃，planQuantity 兼容字符串。预检通过（`submittable`）且有 cargo 时展示「本次将提交以下 SKU×数量：」块。
- 提交行为语义未变：`canSubmitJdOutbound` 门禁、提交端点/幂等键、成功/失败消息均原样保留。

### 测试结果

- `npx tsc --noEmit`：0 错误。⚠️ 工作区存在他票（01 导入批次）遗留的未用 import（`RawImportRow`，SalesOutboundPage.tsx）会导致 tsc 失败；该文件属其他票 scope，仅做零行为的一次性 import 清理以通过强制验证门禁，未改任何逻辑。
- `npm test`：158/158 通过（基线 155 不破；本票新增 3 条：confirmation detail 解析/容错、REAL 标题、gate 拒绝场景）。
- `npm run build`：通过（仅有既有的 chunk size 警告）。
- 截图：`output/playwright/ui-fixes/jd-outbound-popconfirm-mock.png`（模拟环境：标题「确认在模拟环境提交？」+ 商户出库号 + 3 行 SKU 明细）、`jd-outbound-popconfirm-real.png`（REAL：标题「确认向真实京东提交这张出库单？（商户出库号 ERP-UX-20260814-0001）」+ 明细）。弹层文本已程序化断言：两条场景均包含商户出库号与 3 条 SKU×数量。截图用 vite dev（:5198，已关闭）+ Python playwright 拦截 mock /api/v1。

## Comments（追加：review 修复）

- 双轴 code review（2026-08-15）：Standards 判断项"`source_order_ref` 字段名误导（承载出库单号）"→ 保持后端 DTO 镜像字段名（改名需动契约），在 `presentTrackingBatchRow` 处加注释说明语义。
- 最终验证：tsc 0 错误、npm test 162/162、build 通过。
