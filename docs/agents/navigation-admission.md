# 菜单准入规则与入口收敛（Issue #98）

本文件是侧边栏菜单（`frontend/src/navigation.ts` 生产导航树）的**准入规则**：一级板块放什么、
什么入口应降级为上下文二级入口、上限与评审条件、旧路径兼容要求，以及后续新增菜单的检查步骤
与测试门禁。导航单一事实源是 `appNavigation`（无 React 元数据），路由/图标在 `routes.tsx`
按 path 绑定，侧边栏/顶栏/路由注册全部由它驱动。

## 1. 准入规则（可执行）

### 1.1 判定：primary menu vs contextual secondary entry

- **primary menu（一级板块可见叶子）**：只放**日常高频动线**——运营每天处理闭环要进入的页面。
  可执行判定（满足其一即可主张可见）：
  1. 属于 CONTEXT.md 业务闭环的主线步骤：来源导入/复核 → 履约 → 发货 → 回传、采购协同、
     订单查询、库存总览、主数据维护、系统/渠道配置等每日动线页面；
  2. 有明确的**动线证据**：页面是某条每日动线的必经环节，或由真实高频父页面承载触发路径
     （证据写进变更说明，不能只写「用户需要它」）。
- **contextual secondary entry（上下文二级入口）**：**低频专用查询/工具**——按单据查询、对账、
  专项分析，非每日动线，但可由某个高频父页面的业务上下文自然触发。处置固定为：
  - `navigation.ts` 节点加 `hideInMenu: true`（保留路径与 label）；
  - `routes.tsx` 的 `routeElements` **必须保留**（路由照常注册）；
  - 在真实高频父页面提供清晰的上下文 Link/Button，`href` 指向原路径。
- **上限（硬约束）**：每个一级板块的**直接可见子项**（可见叶子或可见分组节点各计 1 项）≤ 6；
  作业中心按设计口径固定为 **6 个可见叶子**（business-object-navigation 01）。超限必须评审并
  降级或分组，不得直接加可见叶子。分组内的叶子数量不限，但必须能说明动线归属（如系统管理 →
  京东工具收纳 6 个低频 JD 查询工具）。
- **评审条件**：新增一级板块、新增可见叶子、降级、移动入口，必须：
  1. 引用 CONTEXT.md 业务对象，说明页面归属哪条动线、与哪个业务对象边界一致；
  2. 给出高频证据（每日动线）或低频证据（专用查询 + 上下文父页面）；
  3. 超限时给出降级方案或豁免理由（豁免必须写进本文件「当前收敛清单」留痕）；
  4. 更新本文件第 3 节计数；
  5. 通过第 5 节测试门禁。
- **所有者**：菜单变更由变更提交者提出并写明上述证据，代码评审确认；导航测试是自动门禁，
  不通过不得合入。
- **旧路径兼容（硬约束）**：降级 ≠ 删除。禁止删除/改写任何既有 URL，禁止把路径重定向到
  错误的业务对象；旧书签、分享链接与 `?query=` 直达 URL 必须原样可达。降级只改菜单可见性
  （`visibleNavigationTree` 过滤 `hideInMenu`），`navigationTrail`/`navigationOpenKeys`/
  `navigationContext` 仍从完整导航树解析，直达隐藏页时侧边栏展开与顶栏归属照常工作。
- **运行期模块可见性（票 03，spec `unified-business-frontend` D3）**：菜单可见性现在是两层
  过滤的复合——`visibleNavigationTree`（编译期 `hideInMenu`）∘ `moduleVisibleNavigationTree`
  （运行期已开放业务模块清单，取自 `GET /api/v1/business-modules`）。第二层用于让入口可见性
  与后端接通开关联动，不再出现「菜单承诺了系统给不出的能力」。约束：
  1. **只做过滤**：运行期清单不新增节点、不改写路径。导航树仍是唯一事实源，
     `routes.tsx` 的 `routeConfig` 与归属解析一律取完整 `appNavigation`，因此未接通模块的
     既有 URL 仍可直达、仍解析出正确板块归属（与降级入口同一口径）；
  2. **保守**：清单尚未返回或读取失败时按空集处理——宁可少显示，不因读不到清单而放出
     未接通的模块；
  3. **受控是显式的**：只有在节点上写 `requiresModule` 才受清单控制，未声明的节点永远可见。
     全部模块开放时可见结果必须与未引入该层时完全一致（第 5 节有回归断言）；
  4. **与 `MCP_MODULES` 是两件事**：那个控制 MCP 工具对外暴露面，本层控制功能可用性。
     两者不共用配置、不互相推导，导航可见性不得直接读 `MCP_MODULES`。

  受控入口的计数口径（票 04 起生效）：**第 3 节计数一律按「全部模块开放」口径记**，同时把
  受控入口关闭时的计数写在同一格里（如「5（客户中心已接通）/ 4（未接通）」），并在第 2 节
  收敛清单标注它依赖哪个模块。上限 ≤ 6 按「全部模块开放」口径判定——受控只会让可见子项变少，
  不可能因为某个模块关闭而突破上限。

  截至票 06，生产导航树里受控的有两个：「客户跟进」（`/workbench/business-followups` →
  `customer-center`）与「原料库存」（`/inventory/raw-materials` → `raw-material-inventory`）。
- **访问数据触发（前瞻条款）**：当前没有访问埋点，本规则以动线证据裁定，不机械按「最近 N 周
  无访问」隐藏。若后续接入访问统计，可将「连续 8 周零访问」作为**补充触发条件**（仍需按本节
  流程评审降级，数据只作证据之一）。

### 1.2 与业务对象边界（CONTEXT.md）的一致性

- 作业中心 = 运营每日动线的作业集合：人工复核、渠道消息、履约任务、采购协同、文件作业、
  发货记录（6 个高频入口，PRD §22 + business-object-navigation 01 设计口径）。
- 采购比价是**采购工单上下文内的专项比价工具**（Agent 只读查询，输入为 procurement_ticket_id /
  sku_id），不是每日动线；出库信息对账是**出库单据核对工具**（按出库单号/京东单号/订单号对照
  系统内部事实与京东侧事实），属异常排查/专项核对，不是每日动线。
- 降级不改变业务对象归属：采购比价仍属采购协同上下文，出库信息对账仍属发货记录上下文；
  术语与 CONTEXT.md（采购工单/采购回执、发货 Shipment、履约导出）一致，不引入新边界。

## 2. 当前收敛清单（Issue #98 一次收敛）

| 入口 | 路径 | 处置 | 原因（代码/动线证据） |
|---|---|---|---|
| MCP 开放面 | `/system/mcp-exposure` | **票 05 新增，直接降级为上下文二级入口**（`hideInMenu: true`，路由与 routeElements 保留；发现路径放在 Agent 列表页 PageShell actions，`href` 指向原路径） | 判据按 1.1 逐条对齐：**(a) 低频专用查询**——它回答的是「此刻开放了哪些 MCP 模块与工具」，只在改完 `MCP_MODULES` 并重启后核对一次，不是每日动线（开放面在启动期一次性生效，运行期不会变，天天看没有信息量）；**(b) 有真实高频父页面承载触发路径**——`McpToolRegistry` 是对外 MCP 面与内部 Agent 平台**共用的唯一工具源**（`AgentToolInvoker` 从 `find(name)` 取工具），Agent 列表页的「工具」列已经在渲染白名单及其**「未注册」**标注（`ToolItem.registered=false`），而「为什么这个工具未注册 / 机器人为什么哑了」的答案恰好就是开放面，入口放在那里是业务上下文自然触发，不是硬塞；**(c) 不突破上限、也不动别人**——「系统与接入」可见叶子已满 6（渠道接入/履约方配置/运营人员/机器人管理/京东工具/操作审计），本票既不加第 7 个可见叶子，也不为腾位置降级任何既有入口（那需要单独评审）。与「京东工具」先例的差别：那次是把 6 个既有低频页**收敛**成 1 个可见入口，本票只新增 1 个低频页、没有可合并的同类页，因此取准入规则给的另一条处置（降级 + 上下文入口）。业务对象归属：属系统与接入的系统级配置核对，与 `MCP_MODULES`（`app.mcp.modules`，ADR 0015）同一件事，**不是**业务模块开放清单（票 03/04 的 `requiresModule`）——两者不共用配置、不互相推导，因此本节点不声明 `requiresModule`。 |
| 运营提醒 | `/workbench/alerts` | 降级为上下文二级入口（`hideInMenu: true`，路由与 routeElements 保留） | Issue #64 把运营提醒拆成独立路由页：提醒只记录知晓、不推进业务状态，属低频待办视图而非每日动线；复核页 / 提醒页互为上下文切换入口（PageShell actions Link，href 指向原路径），不占作业中心可见菜单位。 |
| 采购比价 | `/procurement/price-compare` | 降级为上下文二级入口（`hideInMenu: true`，路由与 routeElements 保留） | 低频专用查询：仅缺货补货时按采购工单或 SKU 发起比价的 Agent 工具（POST `/api/v1/procurement-price-agent/compare`），不是每日动线；其查询输入来自采购协同工单上下文 → 上下文入口放在采购协同页（FilterBar actions，href 指向原路径）。 |
| 出库信息对账 | `/workbench/recon`（旧 `/fulfillment/outbound-recon` 保留直达） | 同上 | 低频专用查询：按出库单号/京东单号/订单号核对单笔出库的系统内部事实与京东侧事实，属专项核对工具，不是每日动线；查询主键（系统出库单号）由发货记录的行/抽屉承载 → 上下文入口放在发货记录页（页头 actions，与既有刷新按钮同排，href 指向 `/workbench/recon`）。Issue #111 新增作业中心隐藏入口 `/workbench/recon`（复用出库对账展示并注入「金额对账未纳入本期」口径横幅），旧 `/fulfillment/outbound-recon` 继续可用、不注入金额口径横幅。文件作业（销售出库）页的导出明细虽含出库单号，但属履约指令文件侧，不重复放置，避免同一入口散落多处。 |
| 今日发货工作台 | `/workbench/shipping` | 降级为上下文二级入口（`hideInMenu: true`，路由与 routeElements 保留） | Issue #107：发货员从一次订单同步开始今天的工作，如实呈现三平台订单刷新结果（OK/FAILED/SKIPPED、聚福宝「仅报告未入库」）。属每日动线起点，但按 Issue #103 分期交付——本步先隐藏注册并诚实呈现渠道结果，后续 01 再露出为可见入口；生产入口放在文件作业（销售出库）页 PageShell actions（href 指向原路径），不占作业中心可见菜单位。 |
| 待处理 / 异常订单 / 订单追踪 | `/orders/pending` `/orders/exceptions` `/orders/tracking` | UIUX-02：预设视图并入「全部订单」页内 Segmented 切换（`hideInMenu: true`；三个页面文件删除，routeElements 全部映射到 `<OrdersPage />`，由 `orderPresetFromPathname` 按 pathname 解析预设） | 三个入口本就是同一列表组件的不同默认筛选（defaultFilters），合并后仍各自保持原筛选口径与提示；旧直达 URL 原样可达、书签不失效，页内 Segmented 即上下文切换入口（比独立 Link/Button 更强的动线承载）。订单中心可见叶子 4 → 1，不新增业务对象、不改写任何 URL。 |
| 原料库存 | `/inventory/raw-materials` | **票 06 新增可见叶子，受运行期模块清单控制**（`requiresModule: 'raw-material-inventory'`；路由与 routeElements 保留，未接通时只是不显示） | 判据按 1.1 逐条对齐：**(a) 归属**——原料结存查询**不是每日动线的必经环节**：CONTEXT.md 的每日闭环（来源导入/复核 → 履约 → 发货 → 回传）里没有任何一步要读原料结存，它是「原料主数据与结存事实的维护/查询侧」，与同板块的商品档案 / SKU 映射 / 静态礼包 / 总库存同类，因此进「商品与主数据」而**不进「我的工作台」**（spec `unified-business-frontend` D4；「我的工作台」接通态已有 5 个可见叶子，逼近上限，把低频查询塞进去会挤掉每日动线的位置）；**(b) 不突破上限**——商品与主数据可见叶子 4 → 5 ≤ 6（按「全部模块开放」口径，见第 3 节）；**(c) 与「总库存」是两个业务对象、不是重复入口**——总库存是履约方侧的 SKU 成品结存（`/inventory/overview`），原料库存是上游 yuanliaokc 的原料与批次结存；两者之间**没有任何连接键**（`app.skus` 上无 BOM/原料字段，商品档案上只有自由文本的「商品原料 ProductIngredient」，静态礼包明确不单独计库存），因此不会互相顶替，也不得由其中一个推导另一个（spec D6）；**(d) 受控的判据同源**——`requiresModule` 取后端 `BusinessModuleAvailabilityService` 的同一份清单：本仓今天**不存在**原料库存的只读网关（上游只有 stdio MCP 面，spec D7），因此该模块恒为未开放，菜单里不会出现；票 08 落下网关时判据换成那个网关的 ready 判定，不在别处另立开关。未接通时 URL 仍可直达，页面按外壳读到的同一份清单给出「读不到原料」而**不是** 0 或空表——运营不能把故障读成没有库存。 |
| 客户跟进 | `/workbench/business-followups` | **票 04：受运行期模块清单控制**（`requiresModule: 'customer-center'`；路由与 routeElements 保留，未接通时只是不显示） | 它是每日动线页面（渠道消息证据 → 建档 → +1 发起整理 → 确认），**接通时按 1.1 高频口径正常可见，位置/label/path 全不变**；但整理与客户归属全程要读客户中心（kehuzx）的客户档案——未接通时点进去必然拿到 `KEHUZX_NOT_CONFIGURED`，菜单等于在承诺系统给不出的能力。判据取后端 `BusinessModuleAvailabilityService`（客户中心 = `KehuzxMcpProperties.isReady()`，正是 `KehuzxMcpReadClient` 抛 `KEHUZX_NOT_CONFIGURED` 的同一个开关），不在前端另立标准，也与 `MCP_MODULES` 无关。未接通时 URL 仍可直达，页面如实说明「只能查看已有档案、发起整理会以 `KEHUZX_NOT_CONFIGURED` 失败」，判据同样取那份清单——菜单与页面不会各说各话。 |

**不动的一级入口及其符合准入的依据**：作业中心 6 个高频入口（人工复核/渠道消息/履约任务/
采购协同/文件作业/发货记录）均为每日动线主线页面；订单中心（UIUX-02 合并后 1 个可见叶子）
、系统管理（含京东工具分组）等板块均 ≤ 6 且为主数据/配置/查询主线，符合 1.1。

**Issue #89 新增可见叶子 `/system/operators`（运营人员）的依据**：属于系统管理板块的
主数据/配置主线（与渠道接入/操作审计/履约方配置同类），是「运营人员 ↔ 企微 userid ↔
责任团队」映射的登记入口——系统第一次有「人」的概念，后续复核责任归属与个人推送都依赖
该登记，属组织配置的日常动线；直接可见子项 5 ≤ 6，不超上限。

证据（git 历史）：`7005fd5` 时点的 `navigation.ts` 作业中心即为 6 个高频入口（
business-object-navigation 01 验收口径）；`00e1e6c` 加入出库信息对账 → 7；
`29ed999` 加入采购比价 → 8。Issue #98 现状即 8 个可见叶子，超过设计口径 6。
Issue #64 的 `/workbench/alerts` 是隐藏叶子，不改变可见计数。
Issue #111 的 `/workbench/recon` 是隐藏叶子，不改变可见计数（作业中心仍为 6 个可见叶子）。

## 3. 前后计数（按当前代码实时重算）

计数规则：一级板块 = `appNavigation` 顶级项数（含 `/demo/order` 演示页与 `/bi` 外链）；
可见入口 = `flattenNavigationLeaves(visibleNavigationTree(appNavigation))`（含外链）；
可路由叶子 = `routableNavigationLeaves(appNavigation)`（不含 external）。
**受控入口（票 04 起）按「全部模块开放」口径计入，关闭态的数字写在同一格里**——降级 ≠ 删除，
可路由叶子在两态下完全相同。

| 指标 | 收敛前 | #98 收敛后 | **Issue #104 换壳后** | **UIUX-02 合并后** | **UIUX-10 收敛后** | **票 04 后** | **票 05 后** | **票 06 后（当前实测）** |
|---|---|---|---|---|---|---|---|---|
| 一级板块（顶级菜单项） | 10 | 10 | **11**（新增「我的工作台」排最前，spec #103 D6） | **11**（不变） | **11**（不变） | **10** | **10**（不变） | **10**（不变） |
| 可见入口（可见叶子，含外链） | 31 | 30 | **32**（shipping/recon 升为可见 +2；`/demo/order` 隐藏 −1；#110 新增 `/workbench/procurement` +1） | **28**（订单中心三预设并入「全部订单」−3；采购协同仍可见） | **23**（UIUX-10：采购去重 −1、Agent 中心 −3、京东工具 6→1 −5） | **26 / 25** | **26 / 25**（不变：票 05 只加隐藏叶子） | **27（全部模块开放）/ 25（两个受控模块都未接通）**（+`/inventory/raw-materials` 可见受控叶子） |
| 可路由叶子（不含外链，全部生产路由） | 38 | 42 | **43**（#110 新增采购工作台路由；既有 URL 零删改） | **46**（自 #104 后新增 agents/上传等路由） | **47**（+`/system/jd-tools` 新入口；既有 URL 零删改） | **50** | **51**（+`/system/mcp-exposure` 隐藏叶子） | **52**（+`/inventory/raw-materials`；既有 URL 零删改） |
| 作业中心可见叶子 | 8 | 6 | **5**（复核收件箱移入我的工作台） | **5**（不变） | **4**（采购协同降级，采购唯一入口在我的工作台） | **2** | **2**（渠道与文件：渠道消息 / 文件作业） | **2**（不变） |
| 我的工作台可见叶子 | — | — | **4**（今日发货/复核收件箱/采购工作台/对账工作台） | **4**（不变） | **4**（采购工作台更名「采购」） | **5 / 4** | **5（客户中心已接通）/ 4（未接通）** | **5 / 4**（不变） |
| 商品与主数据可见叶子 | — | — | — | — | — | **4** | **4** | **5（原料库存已接通）/ 4（未接通）** |

各一级板块可见子项（票 06 后实测，按「全部模块开放」口径判上限，均 ≤ 6）：我的工作台 5/4
（客户跟进受控）、订单与发货 3、渠道与文件 2、Agent 中心 3、商品与主数据 5/4（原料库存受控）、
系统与接入 6（**已满，不能再加可见叶子**——票 05 的 MCP 开放面因此走降级，见第 2 节收敛清单）。

> 计数勘误（票 04 一并修正）：UIUX-10 那一列的「11 / 23 / 47 / 4」是记录时点口径，此后
> 「客户跟进」「会话管理」「机器人管理」「履约任务」等入口陆续加入而没有回来改表，与代码
> 早已不一致。本列按下方复核命令实时重算，一律以实测为准。

**UIUX-02 变更记录（2026-08-25）**：订单中心四个菜单入口合并为「全部订单」一个，待处理 /
异常订单 / 订单追踪降级为页内 Segmented 预设（`hideInMenu: true`，路径与 routeElements 保留并
映射到 `<OrdersPage />`，由 pathname 解析预设）；三个独立页面文件删除，旧直达 URL 原样可达。

**UIUX-10 变更记录（2026-08-25，#144 评审批准草稿）**：京东工具 6 个查询页收敛为单入口
`/system/jd-tools`（页内 Tab，旧 6 条 URL 降级隐藏直达并定位对应 Tab）；Agent 中心可见入口
收敛为 Agent 列表 / 运行记录（消耗看板、履约单据助手、创建 Agent 降级隐藏，创建入口在列表页
按钮）；采购入口去重——唯一采购入口为我的工作台「采购」，作业中心采购协同降级隐藏。
可见叶子 28 → 23（草稿枚举结构即 23 个叶子；票面「约 15 / ≤15」与批准草稿的枚举不一致，
以草稿为准，计数见上表）。侧栏导航区 `overflow-y: auto` 内部滚动、footer 固定不溢出；
同时压缩了条目密度（32px → 28px）缓解 768px 视口下的滚动量。

**Issue #104 换壳变更记录（2026-08-23）**：
- 新增一级板块「我的工作台」（`/workbench` 分组）：今日发货工作台、复核收件箱（原「人工复核」
  随移动更名）、对账工作台（原 `/workbench/recon` 隐藏入口升为可见，label 从「出库信息对账」
  改为「对账工作台」；旧 `/fulfillment/outbound-recon` 仍为作业中心隐藏入口，不变）；
  运营提醒（隐藏）随复核收件箱移入。
- 原「作业中心」分组键改为 `/operations`（纯分组键，非路由），其余成员不变。
- `/dashboard` label 更名「调度台」（与 spec #103 词汇一致）。
- `/demo/order` 降级为隐藏入口（Demo 不出现在日常菜单，URL 保留直达）。
- 删除 `NAVIGATION_GROUP_SUFFIX` 分组 hack 与 `navigationOpenKeys`：外壳导航改为
  「分组标题 + 平铺链接」（原型形态，无折叠层级）。ADR 0004 后分组由 `shellRail.ts`
  展示层承载（分组 key 即板块路径，与叶子渲染互不冲突），不再经 antd Menu。
- 岗位选择器只改默认落地页与（#106）复核默认团队，**全站菜单不按岗位隐藏或加锁**（D1）。
- **ADR 0004（同日追加）**：外壳导航改为原型壳层 CSS 的 1:1 移植（`shell.css` + `shellRail.ts`
  展示层）；岗位切换**只重排分组顺序、绝不隐藏**；调度台在轨道展示上归入「我的工作台」组
  （数据树不变，本文件计数不受影响）；复核收件箱徽标只显示真实 OPEN 计数（取不到不显示）；
  全局搜索以「诚实入口」形态露出（说明未接入跨对象搜索，回车直通订单查询）。

**票 03 运行期模块可见性（2026-08-30）**：菜单可见性增加第二层过滤
（`moduleVisibleNavigationTree`，按 `GET /api/v1/business-modules` 下发的已开放模块清单）。
本表计数**不变**——本票只建机制，生产导航树尚无节点声明 `requiresModule`，全部模块开放时
可见结果与引入前逐叶相同（`businessObjectNavigation.test.ts` 有回归断言）。

**票 04 客户跟进入口受控（2026-08-30）**：`/workbench/business-followups` 声明
`requiresModule: 'customer-center'`，成为生产树里第一个（也是目前唯一一个）受控入口。
可见入口与「我的工作台」可见叶子因此变成条件计数（见上表两态口径）；一级板块数、可路由
叶子数两态相同，既有 URL 零删改。未接通时该 URL 仍可直达，页面按外壳读到的同一份清单
给出「只能查看已有档案 / 发起整理会以 `KEHUZX_NOT_CONFIGURED` 失败」的提示——菜单与页面
同源，不会一个说有一个说没有。

**票 05 MCP 开放面只读核对（2026-08-30）**：新增 `/system/mcp-exposure`（只读呈现当前已注册的
MCP 工具与已知但未开放的模块）。「系统与接入」可见叶子已满 6，本页按 1.1 直接降级为上下文
二级入口——**没有突破上限，也没有为腾位置降级任何既有入口**；发现路径放在 Agent 列表页
（`McpToolRegistry` 是 MCP 面与 Agent 平台共用的唯一工具源，那页的工具白名单「未注册」标注
正是本页要回答的问题）。计数只动可路由叶子 50 → 51，可见入口两态都不变。注意它与票 03/04 的
运行期模块清单是两条不相干的线：本节点**不**声明 `requiresModule`，页面读的是
`GET /api/v1/mcp-exposure`（`MCP_MODULES` 的注册结果），与 `GET /api/v1/business-modules`
不共用配置、不互相推导。

**票 06 原料库存入口与诚实空态（2026-08-30）**：新增可见受控叶子 `/inventory/raw-materials`
（「商品与主数据 → 原料库存」，`requiresModule: 'raw-material-inventory'`）。归属证据见第 2 节
收敛清单首行：原料结存查询不是每日动线的必经环节，属主数据维护侧，因此进「商品与主数据」
（可见叶子 4 → 5 ≤ 6）而不进「我的工作台」（spec D4）。计数变化：可见入口 26 → 27（全部模块
开放口径）、可路由叶子 51 → 52；未接通态的可见入口仍是 25——两个受控入口都不显示。
后端 `BusinessModule` 同步新增 `RAW_MATERIAL_INVENTORY`，但**恒为未开放**：本仓还不存在原料
库存的只读网关（上游 yuanliaokc 只有 stdio MCP 面，spec D7），没有开关可取；票 08 落下网关时
把判据换成那个网关的 ready 判定。本票**不接真实数据**——页面只呈现「读不到原料」（区别于
「没有原料」），一个结存数字都不显示，也不出现任何「这单原料够不够」之类的推断（spec D6：
SKU 与原料之间没有连接键）。

> Issue 记录的「9 个一级板块 / 34 可见入口」是记录时点口径，与当前代码不一致（此后新增了
> 静态礼包等入口），一律以本表实时重算为准。复核命令（frontend 目录下）：
> `node --experimental-strip-types -e "import { appNavigation, visibleNavigationTree, flattenNavigationLeaves, routableNavigationLeaves } from './src/navigation.ts'; const v = flattenNavigationLeaves(visibleNavigationTree(appNavigation)); console.log('一级板块', appNavigation.length, '可见入口', v.length, '可路由叶子', routableNavigationLeaves(appNavigation).length);"`
> 该命令按「全部模块开放」口径重算。受控入口关闭态的计数（票 04 起需要）另跑：
> `node --experimental-strip-types -e "import { appNavigation, visibleNavigationTree, flattenNavigationLeaves, moduleVisibleNavigationTree } from './src/navigation.ts'; import { NO_OPEN_BUSINESS_MODULES } from './src/businessModules.ts'; console.log('可见入口(无模块开放)', flattenNavigationLeaves(visibleNavigationTree(moduleVisibleNavigationTree(appNavigation, NO_OPEN_BUSINESS_MODULES))).length);"`

## 4. 后续新增菜单的检查步骤（Checklist）

新增或调整菜单时，逐项勾选：

1. [ ] 判定 primary vs contextual secondary（1.1）：页面属于哪条业务动线？每日高频还是低频专用？
2. [ ] 检查所属一级板块**直接可见子项**（可见叶子或分组节点各计 1 项）≤ 6；作业中心 = 6 个
      可见叶子；超限必须降级或分组。
3. [ ] 降级入口三件套：`navigation.ts` 加 `hideInMenu: true`；`routes.tsx` 的 routeElements
      保留；高频父页面加上下文 Link/Button（`href` 指向原路径）。
4. [ ] 引用 CONTEXT.md 术语说明业务对象归属，不引入新边界、不与既有业务对象冲突。
5. [ ] 若入口依赖某个外部业务能力：在节点上声明 `requiresModule`，并确认后端
      `BusinessModuleAvailabilityService` 里该模块的判据就是「点进去能不能用」的同一个开关
      （不得新造一份可能与真实链路不同步的开关）。
5b.[ ] 受控入口还要管**直达态**：未接通时 URL 仍必须可达且页面正常渲染，并给出与状态相符
      的提示；提示的判据取外壳下发的同一份清单（`useBusinessModuleStatus`），不在页面里
      另立判据，也不要在清单未落定时就断言「未接通」。
5c.[ ] 若该能力在本仓**还不存在接入实现**（上游未就绪，如票 06 的原料库存）：模块照样在后端
      枚举里声明、入口照样声明 `requiresModule`，但**不得**为它编一个恒真/恒假的标志位充当
      开关——没有网关就是没有开关，清单里不列它即可；用测试钉住「今天恒为未开放」，
      让接入落地时必须显式改那条断言，而不是让「好像开了」悄悄发生。
6. [ ] 更新本文件第 2 节收敛清单与第 3 节计数（按「全部模块开放」口径，受控入口把关闭态
      的数字写在同一格）。
7. [ ] 测试门禁全绿（第 5 节）。

## 5. 测试门禁（导航相关）

- `frontend/test/businessObjectNavigation.test.ts`：
  - 作业中心高频可见叶子固定为 6 个设计名单并断言 ≤ 6 上限；
  - 被降级入口 `hideInMenu === true`、不出现在可见菜单、**仍在 routable 叶子中**（旧路径不 404）、
    `navigationContext` 仍解析出作业中心归属（含 Issue #64 的 `/workbench/alerts`）；
  - 无重复叶子路径；
  - **运行期模块过滤的零行为变化回归（票 03）**：全部模块开放时可见叶子集合与过滤前逐项相同；
    未开放任何模块时消失的恰好是声明了 `requiresModule` 的叶子；过滤不改写 `appNavigation`
    本身（可路由叶子一个不少）。
  - **客户跟进受控（票 04）**：`/workbench/business-followups` 声明
    `requiresModule: 'customer-center'` 且它是生产树里唯一的受控叶子；接通时出现在原有位置
    （复核收件箱之后、采购之前）且 label 不变，未接通时菜单里少的恰好只有它（我的工作台
    可见叶子 5 → 4，仍 ≤ 6）；两态下路由注册与「我的工作台 / 客户跟进」归属都不变。
  - **原料库存受控（票 06）**：`/inventory/raw-materials` 声明
    `requiresModule: 'raw-material-inventory'`、是可见叶子（不是降级入口）、归属解析为
    「商品与主数据 / 原料库存」；接通时排在总库存之后且该板块可见叶子 5 ≤ 6，未接通时菜单里
    少的恰好只有它；两态下路由注册不变。同时钉住它与「总库存」是两个业务对象——
    业务级成品库存总览仍然只有一个。
  - **系统与接入可见叶子逐项钉死（票 05）**：可见叶子必须恰好是渠道接入 / 履约方配置 /
    运营人员 / 机器人管理 / 京东工具 / 操作审计这 6 个——同时挡住「再加第 7 个可见叶子」和
    「为腾位置把既有入口降级」两种越界；`/system/mcp-exposure` 在降级入口名单里
    （隐藏、可路由、归属仍解析为「系统与接入 / MCP 开放面」）。
- `frontend/test/businessFollowUpModuleGate.test.ts`（票 04 接线门禁）：外壳按清单渲染侧栏
  （未接通没有该链接 / 接通回到原位置且 label 不变）；两态下 `/workbench/business-followups`
  直达都正常渲染出列表；未接通与「清单读不到」两种情况下页面给出同一句状态提示，
  与菜单口径一致。
- `frontend/test/rawMaterialInventoryRoute.test.ts`（票 06 接线门禁）：外壳按清单渲染侧栏
  （停在 `/inventory/overview` 比对两态，避免默认折叠掩盖差异——未接通没有该链接 / 接通后
  排在总库存之后且 label 不变）；两态下 `/inventory/raw-materials` 直达都正常渲染；未接通与
  「清单读不到」给出同一句状态提示，与菜单口径一致；页面在任何状态下都不显示结存数字、
  不出现空态词、不出现原料够不够的推断。
- `frontend/test/rawMaterialInventoryView.test.ts`（票 06 措辞门禁）：四类失败原因
  （未配置 / 不可用 / 鉴权失败 / 契约漂移）标题与说明互不相同；每一种拿不到数的情形都必须
  带上「读不到原料 ≠ 没有原料」的收尾句；文案里不得出现任何数字或空态词。
- `frontend/test/runtimeModuleVisibility.test.ts`（票 03 机制门禁）：受控节点整支过滤、
  整组受控与「只有受控子项」的空组收敛、URL 直达与归属仍取完整导航树、侧栏轨道确实按清单过滤、
  清单载荷畸形或含未知模块标识时保守取空集。
- `frontend/test/appShellRoute.test.ts`：外壳启动读取 `GET /api/v1/business-modules`；
  该端点故障时外壳照常可用且不放出未接通的模块。
- `frontend/test/procurementTicketsRoute.test.ts`：采购协同页上下文入口存在、`href` 正确，
  点击可达采购比价页。
- `frontend/test/fulfillmentPagesRoute.test.ts`：发货记录页上下文入口存在、`href` 正确，
  点击可达出库信息对账页。
- `frontend/test/alertsQueueRoute.test.ts` + `manualReviewQueueRoute.test.ts`：运营提醒 /
  复核两路由互为上下文切换入口；旧 `?view=alerts` 链接重定向到 `/workbench/alerts`。
- `frontend/test/mcpExposureRoute.test.ts`（票 05）：Agent 列表页上下文入口存在、`href` 指向
  `/system/mcp-exposure`、点击可达；页面按模块分组呈现已注册工具与用途摘要、两类模块分得清、
  未开放任何模块时是空态而非错误态，且页面正文里没有任何可改开放面的控件（纯只读）。
- 全量门禁：`cd frontend && npm run typecheck && npm test && npm run build`，
  `git diff --check`。
