# 04 — 「商品中心」文案口径统一为「主数据」

**Type:** implementation

**What to build:** 消除旧导航命名残留：页面文案中的「商品中心」统一为当前板块名「主数据」（或具体页面名"商品档案"）。

**Source:** .scratch/saas-visual-system/layout-audit.md §3-2（文件作业页提示"请在商品中心维护 SKU 映射"）

**Status:** resolved

**Claimed by:** opencode 分派 subagent

- [x] 全前端 grep「商品中心」，逐个核对语义后替换为「主数据」/「商品档案」（SKU 映射维护提示用「主数据 → SKU 映射」最准确；不能无脑全局替换——先核对每处指向的页面）。
- [x] 验证：tsc 0 错误、npm test 全过；涉及页截图存 output/playwright/ui-fixes/naming-*。

**Scope:** 仅文案（frontend/src 字符串）。不得改路由、组件结构、后端。

**Do not:** commit；修改 saasTheme.ts。

## Comments

- 2026-08-15 完成。核对导航口径（`frontend/src/navigation.ts`）：板块 `/product` 标签为「主数据」，子页「商品档案」（/product/skus）与「SKU 映射」（/product/sku-mappings），确认全部 9 处「商品中心」均为旧导航命名残留（1 处用户可见文案 + 8 处源码注释），逐处语义核对后统一为「主数据」口径，无路由/组件/后端改动，未触碰 saasTheme.ts。
- 替换明细（替换前 → 替换后）：
  1. `src/pages/fulfillment/SalesOutboundPage.tsx:119`（用户可见，作业中心→文件作业导入提示）：`…请在商品中心维护 SKU 映射。` → `…请前往主数据 → SKU 映射维护。`
  2. `src/api/endpoints.ts:201`（注释）：`// ---------- 商品中心（主数据，openapi MasterData 组） ----------` → `// ---------- 主数据（openapi MasterData 组） ----------`
  3. `src/api/types.ts:429`（注释）：`// ---------- 主数据（商品中心 / 系统配置） ----------` → `// ---------- 主数据 / 系统配置 ----------`
  4. `src/pages/shared/MasterDataCrud.tsx:3`（注释）：`商品中心五个页面（品类/商品/Internal SKU/来源映射/履约方映射）共用` → `主数据板块五个页面（品类/商品/Internal SKU/来源映射/履约方映射）共用`
  5. `src/pages/product/index.tsx:2`（注释）：`商品中心：品类 / 商品 / Internal SKU / SKU Mapping。` → `主数据：品类 / 商品 / Internal SKU / SKU 映射。`
  6. `src/pages/product/ProductsPage.tsx:2`（注释）：`商品中心 · 商品（…）` → `主数据 · 商品（…）`
  7. `src/pages/product/CategoriesPage.tsx:2`（注释）：`商品中心 · 品类（…）` → `主数据 · 品类（…）`
  8. `src/pages/product/masterOptions.ts:2`（注释）：`商品中心各页面共用：主数据下拉选项（…）` → `主数据各页面共用：下拉选项（…）`
  9. `src/pages/product/SkuMappingsPage.tsx:1`（注释）：`商品中心 · SKU 映射矩阵：…` → `主数据 · SKU 映射矩阵：…`
- 验证结果：`npx tsc --noEmit` 0 错误；`npm test` 155/155 通过（基线未破，测试无「商品中心」断言，无需同步）；`npm run build` 成功（chunk 体积警告为既有问题，与本票无关）。grep 复核 `src/` 无「商品中心」残留；仅剩 `dist/` 陈旧产物（build 已重新生成）、`prototype/dashboard-prototype.html` 与 `.playwright-cli/*.yml` 历史快照，均不在本票范围（frontend/src 文案）。
- 截图：未生成（本票无新增页面、无视觉变化；用户可见改动仅 1 行辅助文案，Playwright 截图验证收益低）。

## Comments（追加：review 修复）

- 双轴 code review（2026-08-15）：Spec 轴指出 checkbox「涉及页截图」未交付 → 已补 `output/playwright/ui-fixes/naming-sales-outbound-1440.png`（页面含「主数据 → SKU 映射」新口径文案，无「商品中心」残留）。
- 最终验证：tsc 0 错误、npm test 162/162、build 通过。
