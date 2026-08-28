# 03 — 平台拉取结果统一为「批次快照弹窗」（不跳出界面）

Type: implementation
Status: ready-for-agent
Priority: P1
Requested: 用户 2026-08-28「修 统一拉取信息逻辑；不要跳出界面，每个平台一个大弹窗，弹窗里面是表格，就像发企业微信那样一个批次一个表格显示关键信息，就是我说的 snapshot」

## 需求

拉取结果的呈现改为**与企微发货确认卡同构的快照形态**：

> 企微既有形态（`preship-batch` 域，2026-08-27 上线）：**一批一卡**，卡面只放汇总
> （渠道 / 单数 / 总件数 / 收件人预览），**明细作为独立附件投递**（≤10 单发 PNG 表格图，
> >10 单发 Excel），全部走**订单行快照口径**。

搬到工作台上就是：**每个平台一个大弹窗 = 一张卡；弹窗内汇总在上、该批次的关键信息表格在下**。
点卡片**在当前页开弹窗**，不再跳走到文件作业页。

## 现状与缺口

拉取入口已收敛（「今日发货工作台」页头「开始今日订单同步」，`ShippingWorkbenchPage.tsx:100`），
结果按渠道渲染独立卡片（`ShippingSyncResults.tsx`），失败文案走 business_code 封闭映射
（`shippingPresentation.ts:46-57`，11 个业务码）——**这些都要保留，本票不动**。

要修的四点：

**A. 点卡片会跳走（本票主诉求）**
`shippingPresentation.ts:184` 是 `destination: batchId ? fileJobUrlForBatch(batchId) : null`，
卡片被 `<Link>` 整包（`ShippingSyncResults.tsx:161`），点击离开工作台。用户要求改为**原地弹窗**。

**B. 结果离开即丢**
同步结果只存在于 `ShippingWorkbenchPage` 的局部 `useState`（`SyncState`）。刷新/切页/回来即消失，
运营无法回答「今早同步过没有、上次什么结果」。后端**已有留痕**：
`PlatformOrderRefreshService:350` 每渠道写一条 audit（`audit(context, channel, "refresh", ...)`）。

**C. 聚福宝卡片是死胡同**
「仅报告未入库 + 拉取 N 单」之后没有任何下一步指引（`reportOnly` 分支无 `destination`，
因 JSON 直连缺收货人字段故无批次）。违反「看得到必须做得到」。

**D. 工作台指标区不认同步结果**
8 段流水线里「1 平台拉取」「2 落导入批次」恒为 `PLACEHOLDER`，顶部 KPI「仅报告未入库」也是占位
（`ShippingWorkbenchPage.tsx:82-83`, `:70`）。同步完成后指标区纹丝不动，与结果卡各说各话。

## 企微既有形态的权威事实（2026-08-28 定位，抄形态时按这里，不要按注释）

- **一批一卡**：`BatchPreShipConfirmCard.View` 卡面只有批级汇总（渠道 / 订单数 / 总件数 / 收件人预览），
  **明细故意不上卡**，作为独立消息在发卡**之前**投递（`WecomBusinessCardRunner.java:102-107`：
  「附件先行：先看清单、后见按钮」）。→ 弹窗对应做法：汇总在上、表格在下，同屏可见。
- **≤10 单发 PNG / >10 单发 Excel**：分界常量 `IMAGE_ROW_LIMIT = 10`。
  两种渲染共享同一份 `List<PendingRow>`，**保证图片与 Excel 口径完全一致**。
  → 弹窗是网页表格，不受此限；但**列序必须与之一致**，将来若要"导出这张表"才不会出现三套口径。
- **版本 = 批内订单 `lock_version` 之和**，且**渲染卡 / 渲染附件 / 点击确认三处各自独立核对同一版本**，
  任一处不符则整套作废——避免"清单是旧的但按钮还能点"。
  → 弹窗**不承担确认动作**，故不需要这套三重核对；但**汇总数字必须与卡片同源**，
  不得在弹窗内二次取数导致与卡片显示不一致（见 AC）。
- ⚠️ **一处注释与实现的偏差（Agent 实测）**：类注释说"全部取订单行快照、不回读主数据"，
  但 `pendingRows()` SQL 里单品行的商品名取的是 `p.product_name`（**当前主数据**），
  只有礼包组件行才真用 `product_name_snapshot`。这是设计意图（"京东品"要反映当前映射），
  **抄的时候按 SQL 实际行为，别按注释字面**。
- **前端此前从未渲染过这张表**——本票是第一次，没有现成组件可抄样式。

## 可直接复用的现成资产（不要重造）

| 资产 | 位置 | 用途 |
|---|---|---|
| 批次行明细 API | `GET /api/v1/import-batches/{id}/rows`（`endpoints.ts:768`） | 弹窗表格数据源 |
| **安全投影层** | `fileOperations.ts:151 presentImportRow()` | 注释即纪律：「禁止 JSON dump 和 PII 透出」。**必须经它投影**，不得直接渲染 `raw_cells` |
| 关键信息列定义 | `SalesOutboundPage.tsx:498-579` | 现成 13 列：所属来源/收货人/手机号/收货地址/商品/规格/发货数量/来源SKU/履约归属/状态/处理结果 |
| 京东货品单元格 | `fileOperations.ts:184 presentJdCargos()` | 多货品逐行列出 |
| 渠道呈现与业务码映射 | `shippingPresentation.ts` | 状态、文案、reportOnly 判定 |

## Implementation idea

**A. 卡片 → 弹窗**
- `ShippingChannelView` 把 `destination: string|null` 改为携带 `batchId`（或并存），卡片 `onClick` 开 Modal，
  **不再用 `<Link>` 整包**。
- Modal 宽一些（建议 `width={1100}`，表格列多）；一次只开一个渠道。
- **保留去文件作业页的能力**：Modal 底部放「去文件作业页确认整批」链接（整批确认动作**不搬进弹窗**——
  它是不可逆的对外承诺，既有页面已有完整的二次确认与影响说明，重复实现风险高）。

**B. 弹窗内容（快照口径）**
- 顶部汇总条（对应企微卡面）：渠道 / 批次号 / 共 N 行 · 已接收 · 待复核 · 拒绝 / 拉取窗口日期。
- 主体表格（对应企微附件）：**列定义必须对齐企微那张表**（用户原话「就像发企业微信那样」）。

  企微权威列定义在 `BatchPreShipConfirmCardSource.java:283-298`：

  ```java
  record PendingRow(int seq, String sourceRef, String receiverName, String receiverPhone,
      String receiverAddress, String goods, String quantity)
  static final String[] HEADERS = {"序号", "渠道单号", "收件人", "电话", "收货地址", "发货明细", "件数"};
  ```

  **弹窗采用「企微 7 列 + 拉取场景必需 2 列」= 9 列，顺序如下：**

  | # | 列 | 来源（`presentImportRow` 投影后的 `ImportRowView`） |
  |---|---|---|
  | 1 | 序号 | 行序（1-based，对应企微 `seq`） |
  | 2 | 渠道单号 | `sourceOrderRef`（企微用 `source_ref` 而非 36 字的 `order_no`，此取舍照抄） |
  | 3 | 收件人 | `receiverName` |
  | 4 | 电话 | `receiverPhone` |
  | 5 | 收货地址 | `receiverAddress` |
  | 6 | **商品**（⚠️ 不叫「发货明细」，见下） | `productName`（来源口径） |
  | 7 | 件数 | `quantity`（来源口径） |
  | 8 | **状态** | `status`（ACCEPTED / NEED_REVIEW / REJECTED） |
  | 9 | **处理结果** | `reason`（`importIssueReason` 已做安全文案映射） |

  **为什么加第 8、9 列**：企微卡面向的是**已确认订单**（要不要发货），拉取弹窗面向的是**原始导入行**
  （这批拉到了什么、哪些能用哪些卡住）。丢掉状态与原因，弹窗就回答不了拉取场景最核心的问题。
  这是"以企微为基底按场景扩展"，不是另起一套。

  **不含**「本次确认」勾选列与「操作」列——弹窗只看不做（确认动作留在文件作业页，见下）。

### ⚠️ 第 6 列的口径决策（2026-08-28，执行者提出前提冲突后重新裁定）

执行者正确指出：`presentJdCargos(jdCargos)` 对第三方履约恒为 `—`，与企微 `goods` 不同口径。
核实后发现**问题比这更根本——这里本就不该照抄企微口径**。三个口径是三回事：

| 口径 | 数据源 | 覆盖范围 |
|---|---|---|
| 企微 `goods` | `orders` + `order_lines` + `order_line_components`（`BatchPreShipConfirmCardSource:212-220`，含礼包展开） | 仅**已建单**订单 |
| 前端 `jd_cargos` | 后端投影 `ImportRowJdCargoProjectionService`（`SourceImportService:701-708`），京东出库货品 | 仅**走京东且已建单**的行 |
| **来源口径**（本票采用） | `raw_import_rows.raw_cells` / `parsed`，经 `presentImportRow` | **所有行，含未建单** |

**决定性事实**：`raw_import_rows.order_id` 可为 NULL（`V1__baseline.sql:374`）——
**NEED_REVIEW / REJECTED 的行根本没建单**。而这些行恰恰是拉取弹窗最要给人看的
（"这批哪些卡住了、为什么"）。照抄企微口径 → 这些行的商品列**全空**，弹窗核心价值归零。

**场景差异**：企微卡是**发货前确认**（已建单，问"这批要不要发"，京东口径正确）；
拉取弹窗是**入库核对**（含未建单，问"平台给我发来了什么"，来源口径正确）。

**因此**：第 6 列用 `productName`（来源口径），**列名改为「商品」而非「发货明细」**——
它确实不是发货明细，沿用企微列名会误导运营以为看到的是京东发货口径。
第 7 列「件数」同理用来源 `quantity`。

> 用户说的「就像发企业微信那样」指的是**形态**（一批一表、汇总+明细、关键信息、不跳出界面），
> 不是字段逐一照搬。在本场景里严格照搬**反而是错的**。

**因此不需要后端扩展 `/rows`**——执行者提的方案 1（加后端 goods 字段）不采纳，方案 2（接受第三方显示 `—`）
也不采纳，改用第三条路：来源口径，对所有行都有值，零后端改动。
- 分页：行数可能上百，用表格分页（后端 `/rows` 已支持 page/size），**不要一次性拉全量**。
- 空态/加载/失败三态齐全；失败给重试，不要静默。

**C. 聚福宝分支**
无批次 → 弹窗内不显示表格，而是**明确说明**：JSON 直连拉到 N 单，因来源缺收货人字段未入库，
并给出**可执行的下一步**（走文件作业页上传该平台 Excel 补录）。宁可一句人话，不要空弹窗。

**D. 指标区接真数（本票范围内的最小一步）**
把「1 平台拉取」「2 落导入批次」两段与本次同步结果接起来（拉取渠道数/成功数、生成批次数/行数），
「仅报告未入库」KPI 用聚福宝 `order_count` 填。**做不到的继续显式占位**，不要塞假数。

**B 的持久化（谨慎处理）**
「上次同步结果」持久显示需要后端提供按日/按渠道的拉取历史查询——**当前无该端点**，
`audit_logs` 有数据但无面向该场景的读接口。**本票不新增后端端点**：
先用 `sessionStorage` 让同步结果在**本标签页内**跨路由跳转不丢（刷新可丢），并在票尾记录
「需后端拉取历史端点」作为后续票。**不要为此伪造一个查不到数据的 UI。**

## 不做的事

- 不动拉取入口、不动 business_code 封闭映射、不动 `PlatformPullSingleFlight`
- **不把「确认整批发货」搬进弹窗**（见上）
- 不新增后端端点（B 的完整持久化另开票）
- 不碰频控（每平台每日 ≤2 次配额是独立的跨栈合规票，见下）

## Acceptance Criteria

- [ ] 点渠道卡片在**当前页**打开弹窗，不跳走；工作台状态（同步结果、滚动位置）不丢
- [ ] 弹窗顶部汇总与卡片数字一致（同一快照，不二次取数导致漂移）
- [ ] **表格列序对齐企微**（序号/渠道单号/收件人/电话/收货地址/**商品**/件数），其后追加状态、处理结果
- [ ] 第 6 列走**来源口径** `productName`，**未建单行（NEED_REVIEW/REJECTED）也必须有值**——
      这是本票的核心验收点，用 `jd_cargos` 会让这些行全空
- [ ] 列名是「商品」不是「发货明细」（口径不同，不得沿用企微列名）
- [ ] 执行者自查发现的两处缺陷一并修：**未知数字误显示 0**、**失败后可能恢复旧成功快照**
- [ ] 弹窗表格经 `presentImportRow` 投影，**无 JSON dump、无越权 PII 字段**
- [ ] 表格分页取数，不一次性拉全量；空/载入/失败三态齐全且失败可重试
- [ ] 聚福宝弹窗有明确说明与可执行下一步，不是空弹窗
- [ ] 「去文件作业页确认整批」链接仍在，跳转带 `?import_batch=`
- [ ] 指标区「1 平台拉取」「2 落导入批次」在同步后显示真实数字；仍无数据的继续显式占位
- [ ] 同步结果在本标签页内跨路由返回后仍在
- [ ] 既有渠道卡片状态/文案/业务码映射零回归
- [ ] `npm run typecheck && npm test && npm run build` 全绿

## 工作区纪律

多会话并行：禁 `git add -A` / `git commit` / `git checkout|restore|stash`。只改点名文件。迁移从 V73 起。

## Files likely affected

- `frontend/src/pages/workbench/ShippingSyncResults.tsx`（卡片 → 弹窗）
- `frontend/src/pages/workbench/shippingPresentation.ts`（destination → batchId）
- `frontend/src/pages/workbench/ShippingWorkbenchPage.tsx`（指标区接真数、sessionStorage）
- 新增弹窗组件（如 `PlatformPullSnapshotModal.tsx`）
- `frontend/src/pages/fulfillment/fileOperations.ts`（**只读复用**，如需导出类型可加导出，不改投影逻辑）
- 对应测试

## Risk

中。表格含收货人/手机号/地址，**PII 边界是本票最高风险**——必须走既有投影层，
不得因为"弹窗里方便看"而放宽字段。分页与既有 `/rows` 契约对齐，不要新造参数。

## 后续票（本票不做，避免范围膨胀）

1. **拉取历史持久查询**（后端读端点 + 前端「上次同步 HH:mm / 结果」）——B 的完整解。
   注意：只做"上次拉取是什么时候、结果如何"的可见性，**不做次数配额**（见下）。

~~2. 每平台每日 ≤2 次配额~~ —— **已作废（2026-08-28 用户明确：这条是莫须有的，不做）**。
代码里本就没有该实现；`PlatformPullSingleFlight` 用 pg advisory lock 挡的是**并发**（同时只能一个），
这层是需要的、保留。**不要再提日配额、也不要在 UI 上显示「今日 N/2」。**
