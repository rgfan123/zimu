# UX 审计：批量确认 / 危险操作 / 状态切换的「确认前明细」检查

- 审计人：UI 测试员（操作逻辑 + 显示逻辑），只读，未改任何代码、未提交
- 审计时间：2026-08-15
- 环境：Vite dev `http://localhost:5195`（/api 代理到网关），Python playwright（chromium headless shell）
- 方法：真实后端读数据 + 写端点全量 route-mock（上传/确认/批量确认等 POST 均未触达后端，零状态污染）
- 截图目录：`output/playwright/ux-audit/`（15 张）

---

## 1. 核心问题复现：订单导入「确认本批次」无确认前明细（🔴）

**用户反馈**：*"订单导入的时候，它只有一个批量确认按钮，但是没有批量确认的明细，导致我不知道该不该确认"*

**复现路径**：`/fulfillment/sales-outbound` → 「来源订单导入」卡片 → 选择文件 → 点「开始导入」→ 弹出 Alert + 「确认本批次」按钮
（`frontend/src/pages/fulfillment/SalesOutboundPage.tsx:156-175`）

**复现过程**（mock 后端返回批次：共 6 行 = 已接收 3 / 待复核 2 / 拒绝 1）：
1. 上传后页面只出现一个 Alert：`批次 UX-TEST-… · CAISHIXIAN | 共 6 行，已接收 3 行，待复核 2 行，拒绝 1 行；请核对整个批次后统一确认。` + 按钮「确认本批次」（**禁用**）。
2. 下方有一个「导入明细（含已接收的原文件行）」表格，列出 6 行（来源订单号/来源 SKU/来源商品/状态/处理结果/系统订单 ID）——但该表**与确认动作完全脱节**：
   - 表标题是"导入明细"，不是"本次确认明细"；没有"确认后将写入以下 N 行"的表述；
   - 行没有"将被确认"标记，已接收行显示的是后端预先写的"已写入系统订单"，用户无法区分"这批里哪些行会因本次确认生效"；
   - **确认后的影响（"生成履约文件 N 份"）只出现在确认完成之后**（`confirmed_at` 分支），确认前对"点了会怎样"零说明——而文件头注释明确"文件一旦生成即形成履约承诺"（不可逆的对外承诺动作）。
3. 点击「确认本批次」→ **无任何二次确认**（Popconfirm 数为 0），直接 POST 提交（`SalesOutboundPage.tsx:97-110`）。
4. 确认后反馈：仅 Alert 改为 `批次已确认，生成履约文件 2 份` + 一条 toast；明细表行内容**逐行无任何"本次确认成功"标记**（已接收行在确认前就显示"已写入系统订单"，确认后原样不动）。

**证据**：
- `import-batch-03-after-upload-mixed.png`（混合批次：Alert + 禁用按钮 + 明细表，按钮无任何原因提示）
- `import-batch-04-mixed-confirm-click.png`（禁用按钮悬停：title=None、tooltip=0）
- `import-batch-05-clean-confirm-no-popconfirm.png`（全通过批次点击确认：Popconfirm 数 0，直接提交）
- `import-batch-06-clean-after-confirm.png`（确认后：Alert 出现影响描述，但明细行无逐行结果）
- `import-batch-07-mixed-table-detail.png`（6 行明细完整渲染证据）

**应行为（对标已有先例 `TrackingDraftReviewPanel.tsx:351-450` 批量确认区）**：
| 应有要素 | 运单批量确认区（已实现） | 订单导入确认（现状） |
|---|---|---|
| 批量区标题 + 行为说明 | 「批量确认同批回传」+ "逐行独立事务，失败行保持待确认" | ❌ 只有一行 Alert 汇总 |
| 将确认的行清单（行级明细） | 表格 + 勾选框 + 任务/姓名/Carrier/单号/数量 | ⚠️ 有明细表，但标题与确认无关、无勾选、无"将确认"标记 |
| 按钮带数量 | 「批量确认已勾选运单（2）」 | ❌ 「确认本批次」（无数量、无行范围） |
| 确认影响紧邻按钮 | "成功后发货批次进入已发货，实际发货时间保持为空" | ❌ 影响只在确认后出现 |
| 逐行结果反馈 | 失败行逐行列出（草稿 id + 失败原因） | ❌ 无 |

---

## 2. 全站同类扫描结果

### 🔴 P0 —— 无法安全决策（1 个）

| # | 位置 | 按钮 | 问题 |
|---|---|---|---|
| 1 | `/fulfillment/sales-outbound` 来源订单导入 | 「确认本批次」 | 确认前无"将确认哪些行 + 确认后影响"；无二次确认；确认后无逐行结果。见 §1 完整复现 |

### 🟠 P1 —— 决策信息不足但可推断（3 个）

| # | 位置 | 按钮/控件 | 问题 |
|---|---|---|---|
| 2 | 同上 | 「确认本批次」（禁用态） | 批次含待复核/拒绝行时按钮直接禁用（`SalesOutboundPage.tsx:168`），**无 tooltip/无原因**（悬停 title=None、tooltip=0），用户只能从 Alert 汇总自行推断"为什么不能点、该怎么办"；应像运单区那样显示"哪些行不可确认 + 去人工复核的路径" |
| 3 | 同上 | 「导入明细」表格 | ① 表标题"导入明细（含已接收的原文件行）"与确认动作脱节，不是"确认明细"；② 每状态最多取 200 行（`SalesOutboundPage.tsx:63`），大文件显示"共 X 行，当前展示 Y 行"，确认范围对用户不完整；③ rows 接口失败时表显示**误导性空态**"暂无可展示的导入明细"，与 Alert 声称的"共 6 行"自相矛盾（复现中已实际出现） |
| 4 | `/fulfillment/sales-outbound` 回传履约结果 Modal | 「校验并接收」 | 无二次确认（Popconfirm=0，`SalesOutboundPage.tsx:307-309`）。影响声明已有（"先整批校验，再原子写入发货与运单结果"），但写入发货/运单为不可逆事实，仍建议二次确认；确认后只有 shipped/partial/failed 三个数字，无逐行失败原因 |

### 🟡 P2 —— 体验改进（3 个）

| # | 位置 | 控件 | 问题 |
|---|---|---|---|
| 5 | `/system/connectors` 编辑弹窗（`ConnectorsPage.tsx:202-204`）；同类：`/system/fulfillment-providers`、`/system/config` 的启用开关 | 「启用」Switch | 切换无任何影响说明（停用后渠道是否停止接收文件/轮询？对已导入批次有无影响？）。模态内只展示客户端模式/凭据状态，未说明开关后果 |
| 6 | 同 §1 页面 | 确认后明细表 | 已接收行确认前后显示完全相同（"已写入系统订单"），无"本次确认"结果状态；若后端不返回逐行确认结果，前端应至少展示"确认完成：成功 N 行"的逐行粒度反馈 |
| 7 | `/fulfillment/shipments` 京东出库建单 | 「提交京东出库单」 | 有 Popconfirm + 影响声明（正面），但预检通过时无"本次将提交的 SKU/数量明细"（preview.request 未展示）；REAL 模式下标题"确认向真实京东提交这张出库单？"缺出库单关键信息（商户出库号 ERP-UX-… 仅在上方 Descriptions 里，Popconfirm 本身无对象明细） |

### ✅ 正面先例（全站对比基准，建议订单导入照抄其结构）

| 位置 | 交互 | 明细 + 影响 + 结果 |
|---|---|---|
| `/workbench/reviews` 运单草稿批量确认区（`TrackingDraftReviewPanel.tsx:351-450`） | 批量确认已勾选运单（N） | 行级表格（行号/草稿编号/姓名/任务/数量/Carrier/单号）+ 勾选 + 按钮带数量 + 影响说明紧邻按钮 + 逐行失败结果。证据：`scan-workbench-tracking-batch-preview.png` / `-result.png` |
| `/workbench/reviews` 订单草稿 | 「拒绝草稿」Popconfirm | "确认拒绝这份订单草稿？拒绝后不会生成正式订单。"（`OrderDraftReviewPanel.tsx:541-553`） |
| `/fulfillment/shipments` | 「提交京东出库单」Popconfirm | "确认在模拟环境提交？系统会再次执行 SKU 映射、数量换算和实时库存门禁。" 证据：`scan-shipments-jd-popconfirm.png` |
| `/procurement/tickets` | 「取消剩余缺口」Modal | "仅取消尚未补齐的数量，已经发生的到货和发货事实不会回滚。" + 必填处理依据。证据：`scan-procurement-cancel-modal.png` |
| `/fulfillment/tasks` | 「创建续发批次」Modal | Alert "续发会新建独立发货批次和第三方履约导出" + "数量不得超过剩余可续发数量"。证据：`scan-tasks-continuation-modal.png` |
| `/product/sku-mappings` | 「确认并启用」Modal（`SkuMappingsPage.tsx:219-252`） | 展示来源商品/京东商品/包装乘数 + "确认后将建立该内部 SKU 的京东商品编号映射及来源渠道包装换算映射"——与订单导入确认最接近的正面模板 |
| `/workbench/reviews` SOURCE_FOLLOWUP | 「确认已完成后续回传」 | Alert "系统会重新校验履约终态与所有真实运单" + "任一 Fulfillment 未终局或 Shipment 缺少 Tracking 时，提交会被明确拒绝"。证据：`scan-workbench-source-followup.png` |
| `/system/connectors` | 「测试连接」 | 结果 Alert："彩食鲜 连通性测试：通过 \| 文件 Adapter 可用"。证据：`scan-connectors-test-result.png` |

---

## 3. 结论与最小修复建议（仅建议，未实施）

1. **订单导入确认区改造为运单批量确认区的结构**：Alert 内按钮改为「确认本批次（已接收 3 行）」；Alert 描述补充确认影响（"确认后已接收行将写入系统订单，并生成履约文件，形成履约承诺"）；明细表标题改为「确认明细」，行加"将确认"标记；按钮包一层 Popconfirm（"确认对整个批次 X 行生效？"）；确认后按行展示结果（成功/失败 + 原因）。
2. 禁用确认按钮时提供原因（Tooltip 或 Alert 细分："待复核 2 行、拒绝 1 行，请先处理后再确认"）。
3. 明细表空态文案区分"加载中/加载失败/确实无数据"，避免与 Alert 汇总矛盾。
4. 连接器/履约方/系统配置的「启用」开关旁补一句影响说明。

---

### 附录：完整截图清单（`output/playwright/ux-audit/`）

```
import-batch-01-initial.png                   页面初始（确认前，无任何确认区）
import-batch-02-file-selected.png             选文件后
import-batch-03-after-upload-mixed.png        🔴 混合批次上传后：Alert+禁用按钮+明细表
import-batch-04-mixed-confirm-click.png       🔴 禁用按钮悬停（无 tooltip）
import-batch-05-clean-confirm-no-popconfirm.png 🔴 全通过点确认：无二次确认
import-batch-06-clean-after-confirm.png       🔴 确认后：影响出现，无逐行结果
import-batch-07-mixed-table-detail.png        🔴 6 行明细完整渲染
scan-workbench-tracking-batch-preview.png     ✅ 先例：批量确认前（行表+数量按钮+影响）
scan-workbench-tracking-batch-result.png      ✅ 先例：批量确认后（逐行失败原因）
scan-shipments-jd-popconfirm.png              ✅ 京东出库 Popconfirm
scan-procurement-cancel-modal.png             ✅ 取消剩余缺口 Modal
scan-tasks-continuation-modal.png             ✅ 创建续发批次 Modal
scan-connectors-test-result.png               ✅ 测试连接结果
scan-connectors-edit-modal.png                🟡 编辑弹窗「启用」开关无影响说明
scan-workbench-source-followup.png            ✅ SOURCE_FOLLOWUP 影响 Alert
```

复现脚本（临时，未入库）：`/var/folders/7l/hfq22bfx5ll23zgl36k5qcs80000gn/T/opencode/ux-audit-a-import.py`、`ux-audit-b-scan.py`
