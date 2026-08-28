# 01 — 前端审计发现的 4 处缺陷（从另一条线迁移）

Type: implementation
Status: ready-for-agent
Priority: P1
Source: 2026-08-27 前端 IA/UX 全量审计（在 `Documents/子牧` worktree `beef-lamb-gift-costing` 上做，那条线迁移只到 V32，是落后历史线）

## 背景

审计在另一条线上发现并修复了一批前端缺陷。逐项比对本线（`zimu-work/main`，迁移已到 V72）后确认：**大部分本线已经做得更好，无需迁移**——

| 审计发现 | 本线状态 |
|---|---|
| 订单详情 → 复核跳转丢 case_no | **已修**（`ManualReviewPage` / `AlertsQueuePage` 均已用 `useSearchParams`，且告警队列已拆分独立页） |
| 发货记录订单号不可点 | **已修**，且用 `order_no` 业务编号 + `LongCode` 组件（优于落后线只有 `order_id` UUID） |
| 订单列表「重置」后控件与查询不一致 | **已修**（`handleReset` 统一走 `presetDef.filters`，控件与 `applyFilters` 同源） |

**只剩下面 4 处本线仍存在**，本票逐项修复。四项互不依赖，可拆 4 个 commit。

---

## A. 订单列表缺「异常原因」列

`OrderSummary.attention_reason` 后端已返回（DB 视图 `V1__baseline.sql:2375`：
`COALESCE(order_lines.exception_reason, operational_alerts.message)`，两者均为 `TEXT`），
但前端全仓 0 处渲染。异常订单预设视图只显示状态色块，**说不出异常原因**，用户必须逐单点进详情。

**注意**：`attention_reason` 是**自由文本**，不是原因码——`exception_code VARCHAR(64)` 才是码，且 DB 有约束
`CHECK ((exception_code IS NULL) = (exception_reason IS NULL))`。所以**直接渲染文本，不要走 `reasonLabel()`**。

修：`OrderListView.tsx` 加一列，长文本 `Typography.Text ellipsis={{ tooltip }}`，空值显示 `—`。
`scroll.x` 相应增加。注意 columns 的 `useMemo` 依赖数组要正确。

## B. JdReturn「返回明细」是死控件

`JdReturnQueryPage.tsx:282-284`：

```tsx
<Form.Item label="返回明细" style={{ marginBottom: 0 }} tooltip="...">
  <Select style={{ width: 120 }} options={FLAG_OPTIONS} allowClear placeholder="默认" />
</Form.Item>
```

`Form.Item` **没有 `name`** → Select 未注册到 form → `validateFields()` 取不到 → 用户选的「返回/不返回」**永远不会发给后端**。纯装饰控件。

修：加 `name`，名称按查询类型对应后端参数（退货入库走 `return_to_warehouse_details_flag`，退供走 `return_to_supplier_detail_flag`——**动手前先核对本线 `jdReturnApi` 的实际入参名**，不同查询类型参数名不同）。修完验证选中的值确实进入请求。

## C. 发货状态色双源

`ShipmentsPage.tsx:42` 自定义 `STATUS_COLORS`（用于 `:355` 列表与 `:471` Drawer），
而 `constants/labels.ts` 已导出 `SHIPMENT_STATUS_COLORS`。订单详情页走 `StatusTag kind="shipmentStatus"` 用的是后者
——**同一发货状态在两个页面可能显示不同颜色**。

修：删除本地表，统一用 `labels.ts` 的导出。

## D. 京东查询页结果截断无提示

5 个 `Jd*QueryPage` 的白名单收集器有行数上限（BasicInfo/Stock/Order 为 40，Serial 为 24），
到达上限**静默截断**，用户不知道结果不完整。

修：截断时在结果区给出提示（如「仅展示前 N 条，请调整查询条件或使用分页参数」）。
上限值集中为常量，不要继续散落字面量。

**先确认**：`JdReturnQueryPage` 无上限是**有意设计**（其 SDK 列表无分页字段，整页接收后由 Table 客户端分页）
——落后线已核实过，本线若一致则**保持不截断**，不要为了统一而加限制。

判断截断建议多收集一条再比较，避免恰好等于上限时误报。

---

## Files likely affected

- `frontend/src/pages/orders/OrderListView.tsx`（A）
- `frontend/src/pages/fulfillment/JdReturnQueryPage.tsx`（B）
- `frontend/src/pages/fulfillment/ShipmentsPage.tsx` + `constants/labels.ts`（C）
- 5 个 `frontend/src/pages/fulfillment/Jd*QueryPage.tsx`（D）

## Acceptance Criteria

- [ ] A：异常原因文本可见；空值显示 `—` 而非 `undefined`；未走 `reasonLabel()`
- [ ] B：JdReturn 选中的「返回明细」值确实出现在请求参数中，且按查询类型用对参数名
- [ ] C：发货状态在发货记录页与订单详情页颜色一致；`ShipmentsPage` 无本地颜色表
- [ ] D：截断时有明确提示；上限集中为常量；JdReturn 若确认无上限是有意则保持
- [ ] 四项各自可独立验证
- [ ] `npm run typecheck && npm test && npm run build` 全绿

## 工作区纪律（本工作区多会话并行）

- **不要 `git add -A`，不要 `git commit`，不要 `git checkout/restore`**——工作区里有其他会话的在制品
- 只改上面点名的文件
- 若需新增迁移，从 **V73** 起（生产已到 V72）

## Risk

低。四项都是小范围修复。B 的风险在参数名，必须核对本线 API 实际入参而非照搬落后线。

---

## 验收记录（2026-08-28，主导者实测）

### 代码审查（逐项 diff）

**A 异常原因列** — `OrderListView.tsx` 新增 `dataIndex: 'attention_reason'` 列，
`Typography.Text ellipsis={{tooltip}}` + 空值 `—`，**直接渲染文本未走 `reasonLabel()`**（正确，它是自由文本非原因码），
`scroll.x` 由 1350 → 1570 相应加宽。

**B JdReturn 死控件** — 修得比预期完整：不仅给 `Form.Item` 补了 `name`，还发现
`jdReturnApi.returnToSupplier()` **原本不接收该参数**，一并扩展了函数签名并传入 `params`。
只补 name 而 API 不传的话值照样发不出去。
参数名按查询类型动态取：`rtwList` → `return_to_warehouse_details_flag`，退供 → `return_to_supplier_detail_flag`。

> **参数名已与后端核对一致**：`JdReturnController.java:35` 与 `:68` 的 `@RequestParam(name=...)` 逐字相符。

**C 状态色双源** — 删除 `ShipmentsPage` 本地 `STATUS_COLORS`，列表（`:355`）与 Drawer（`:471`）两处
统一改用 `constants/labels.ts` 的 `SHIPMENT_STATUS_COLORS`。

**D 截断提示** — 采用「多收集一条再比较」判断 `rows.length > RESULT_ROW_LIMIT`，避免恰好等于上限时误报。
**`JdReturnQueryPage` 未被加上限**（正确——其 SDK 列表无分页字段，整页接收后由 Table 客户端分页，是有意设计）。

### 浏览器实测（vite dev 5173，mock 绕过 8088 的 401）

**A 项已实证**：订单列表表头出现「异常原因」列（位于「健康度」与「进度」之间，列索引 7）；
有值行完整显示 `京东库存不足，缺 3 件；已转采购待处理`，无值行显示 `—`。

> 本地 8088 已开启边缘 Basic Auth，全量 API 返回 401，且 `MasterDataCrud` / 列表组件在错误态会整页替换
> 导致表格不渲染。验收改用浏览器端 `fetch` 拦截注入 mock 响应完成，**未修改任何源码**。

B/C/D 三项依赖真实京东 SDK 响应与多页结果，本地无法构造等价场景，以代码审查 + 单元测试为准；
**建议 B 项上线后在真实环境点一次「返回明细」下拉并确认请求参数**（这是本票里唯一涉及请求语义变化的改动）。
