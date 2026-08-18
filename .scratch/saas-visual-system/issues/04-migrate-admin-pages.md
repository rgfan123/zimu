# 04 — 迁移主数据、采购与系统配置页面

**Type:** implementation

**What to build:** 管理与配置人员在商品、采购和系统设置页面看到与业务操作区一致的 SaaS 主题；复杂表单和配置状态依靠结构、文字和语义反馈表达，而不是依赖高饱和彩色块。

**Blocked by:** 01 — 扩展克制的 SaaS 主题 Token

**Status:** resolved

**Claimed by:** opencode（2026-08-15 接管收口；原 codex-main → `/root/saas_admin_04` 已完成主体，见 wip-assessment）

- [x] 商品与 SKU、采购、连接器和系统配置等生产页面采用统一主题，不新增局部硬编码调色板。
- [x] 表单分组、说明、验证错误、保存反馈和只读状态具有清晰层级，主要保存动作保持唯一视觉焦点。
- [x] 连接状态、权限门禁和外部验证结论使用稳定语义色，并通过文字或图标同时表达，不能只依赖颜色。
- [x] Alert、Tag、Badge、Button、Input、Table 和 Card 在配置页面与业务页面具有一致的状态和交互反馈。
- [x] 空态、加载、成功、警告、错误、禁用和无权限状态均完成代表性视觉检查。
- [x] 页面迁移不改变数据提交、权限、路由和错误处理行为，相关测试继续通过。

## Comments

- 2026-08-13：01 已完成并通过双轴复审，本票由 `/root/saas_admin_04` 认领。文件域限于主数据、商品/SKU、采购、连接器与系统配置页面及其专属测试/截图；不得修改共享主题入口、路由/API、经营分析、企微或 JD 文件。
- 2026-08-15（opencode 收口）：
  - **AuditLogsPage 迁移**：移除 antd 具名预设 Tag 色（`purple`/`blue`/`green`/`red`）与内联 rgba 阴影。数据域 Tag 改为 `AdminCategoryTag`（DEMO→MOCK 蓝软点、业务→REAL 紫点）；结果列改为 `AdminStatusTag`，在 `adminVisualCore.ts` 状态表新增 `AUDIT_SUCCESS`（成功/success/check）与 `AUDIT_INCOMPLETE`（未完成/error/warning）两个语义键；错误 Alert 换 `AdminFailureAlert`（复用 401/403 权限门禁 warning 呈现）；Empty 换 `AdminEmpty`；Card 内联阴影换 `admin-surface` CSS token 面。数据提交、查询参数、分页、路由、错误处理行为不变。
  - **补齐截图**（1440×900，vite dev @5194 + Playwright 路由拦截 mock `/api/v1/*`，页面自身无 dev mock）：`output/playwright/saas-admin-04-final/` 新增 `providers-1440.png`、`providers-empty-1440.png`、`providers-warning-403-1440.png`（403→AdminFailureAlert warning 门禁）、`system-config-1440.png`、`system-config-empty-1440.png`、`system-config-warning-403-1440.png`、`audit-logs-1440.png`（含演示/业务域与成功/未完成语义 Tag 证据）、`audit-logs-detail-drawer-1440.png`（详情抽屉 + AdminEmpty）。空态/warning 由 route mock 制造并在截图前以状态文案选择器确认渲染。此前已有 SKU loading/error-500/save/validation、connector、procurement、permission-403 证据。
  - **Checkbox 对账**：① 统一主题无局部调色板——AuditLogsPage 最后一处预设色已消除，`adminVisualSystem.test.ts` 无十六进制/具名色断言继续通过；② 表单层级——MasterDataCrud 弹窗表单/校验/保存反馈/禁用提交由 sku-validation、sku-save-pending-disabled、sku-save-success 截图佐证；③ 语义色+文字/图标双通道——adminVisualCore 状态表 + AdminStatusTag 图标 + permissionFailurePresentation 403（本次 providers/system-config warning-403 截图补充佐证）；④ 组件一致性——共享 Admin 组件 + antd token 主题覆盖，测试断言（adminPageState 门禁、AntApp.useApp 反馈、提交禁用）通过；⑤ 代表性视觉检查——空态/加载/成功/警告/错误/禁用/无权限均有 1440px 证据，本次补齐 warning、empty 及 providers/system-config/audit-logs 覆盖；⑥ 行为不变——`npm test` 155/155 通过、`npx tsc --noEmit` 0 错误。
  - `pages/shared/adminVisual*`、`MasterDataCrud.tsx` 等仍为未跟踪状态（已知，保持）。
