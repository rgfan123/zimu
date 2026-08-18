# 02 — 「启用」开关补影响说明

**Type:** implementation

**What to build:** 渠道接入/履约方/系统配置页的「启用」Switch 切换前用户能知道后果（停用后渠道是否停止接收文件/轮询、对已导入批次有无影响）。

**Source:** .scratch/saas-visual-system/ux-audit.md §2 🟡 P2 #5（`ConnectorsPage.tsx:202-204`，同类：FulfillmentProvidersPage、SystemConfigPage）

**Status:** resolved

**Claimed by:** opencode 分派 subagent

- [x] 三处「启用」开关旁补一句影响说明（如"停用后该渠道停止接收新文件/轮询；已导入批次与既有事实不受影响"），语义与实际行为一致（先核对后端 enabled=false 的实际语义再写文案，不臆造）。
- [x] 验证：tsc 0 错误、npm test 全过；截图存 output/playwright/ui-fixes/connectors-switch-*。

**Scope:** frontend/src/pages/system/ConnectorsPage.tsx、FulfillmentProvidersPage.tsx、SystemConfigPage.tsx（及其专属测试）。不得改其他页面与后端。

**Do not:** commit；修改 saasTheme.ts。

## Comments

### 后端语义核对（先核对再写文案）

**Connector `enabled=false`（connector_configs.enabled）**
- 唯一消费点：`ConnectorService.check` → `ExcelPlatformConnector.testConnection`（backend/src/main/java/cn/zimu/fulfillment/connector/ExcelPlatformConnector.java:18-19）——停用后连通性测试直接判定失败 `CONNECTOR_DISABLED`。
- **没有任何文件轮询/自动接收逻辑受 enabled 约束**：文件导入是用户在作业中心手动上传触发的，`TrackingFileService` 仅 join connector_configs 读 carrier_mappings 配置（非 enabled）。
- **WECOM 例外**：`WecomConnector.testConnection`（WecomConnector.java:36-72）完全忽略 runtime.enabled()，走受权 readiness 诊断。因此对 WECOM 而言该开关无任何后端行为约束。
- 已导入批次、既有事实不受影响（无代码路径读取该字段）。

**FulfillmentProvider `active=false`（fulfillment_providers.active）**
- 受 `AND fp.active` 约束（停用后停止）：
  - `ProviderFileService.candidateRows` / `continuationRow`（ProviderFileService.java:510,554）——不再生成新的履约导出文件（含续发批次导出）。
  - `InventoryOverviewService`（InventoryOverviewService.java:45）——库存不计入库存总览。
  - `TrackingTaskResolver`（TrackingTaskResolver.java:124，THIRD_PARTY）——不再生成新的第三方运单回传任务。
  - `JdSkuMappingCheckService`（JdSkuMappingCheckService.java:171）——京东 SKU 映射检查需启用的 JD 云仓履约方。
- 不受 active 约束（既有事实照常处理）：`ShipmentJdOutboundService`（京东出库建单/提交）、`ShipmentJdTrackingBackfillService`（回传补录）、`FulfillmentStockDecisionService`、`TrackingFileService`（用户上传回传文件处理）——join 均无 active 过滤。
- 已导入批次不受影响。

### 改动

- `ConnectorsPage.tsx` 编辑弹窗「启用」Form.Item `extra` 按渠道区分：
  - 彩食鲜/聚福宝/飞象：`停用后，该渠道的连通性测试将判定为失败；文件导入、已导入批次与既有事实不受影响`
  - 企业微信：`企业微信长连接由受权 readiness 诊断独立判定，此开关不影响其消息接收；已导入批次与既有事实不受影响`
- `FulfillmentProvidersPage.tsx` 编辑弹窗「启用」extra：`停用后不再生成新的履约导出文件，库存不计入库存总览；既有订单、已导入批次与既有运单回传处理不受影响`
- `SystemConfigPage.tsx` 只读总览两条脚注同步修正（渠道脚注补 WECOM 例外；履约方脚注改"既有运单回传处理"）。
- 未改 enabled/active 行为语义，仅加说明；未动其他页面、saasTheme.ts；未 commit。

### 验证

- `npx tsc --noEmit`：0 错误（注：首轮有 3 个错误位于 ticket #01 的 SalesOutboundPage/fileOperations WIP，并行 agent 修复后归零，与本票改动无关）。
- `npm test`：162/162 pass（当前基线 162 个用例，票面 155/155 为旧基线数；期间并行 agent 的一次中途改动曾致 21 个级联失败，根因同为 ticket #01 的 fileOperations.ts:126 语法错误，非本票改动）。
- `npm run build`：通过。
- 截图（mock /api/v1，vite dev :5197，chromium headless shell；脚本断言每处文案出现后才截图）：
  - `output/playwright/ui-fixes/connectors-switch-edit-excel-channel.png`（Excel 渠道编辑弹窗）
  - `output/playwright/ui-fixes/connectors-switch-edit-wecom-channel.png`（企业微信编辑弹窗）
  - `output/playwright/ui-fixes/connectors-switch-provider-edit.png`（履约方编辑弹窗）
  - `output/playwright/ui-fixes/connectors-switch-system-config.png`（系统配置只读总览）
- 临时脚本：`/var/folders/7l/hfq22bfx5ll23zgl36k5qcs80000gn/T/opencode/connectors-switch-shots.py`（未入库）；dev server 已关闭。

## Comments（追加：review 修复）

- 双轴 code review（2026-08-15）：Standards 判断项"「受权」疑为「授权」误写且混中英"→ 用户可见文案改为「连接就绪度诊断」（ConnectorsPage L208、SystemConfigPage L141）。
- 最终验证：tsc 0 错误、npm test 162/162、build 通过。
