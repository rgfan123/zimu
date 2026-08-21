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
| 运营提醒 | `/workbench/alerts` | 降级为上下文二级入口（`hideInMenu: true`，路由与 routeElements 保留） | Issue #64 把运营提醒拆成独立路由页：提醒只记录知晓、不推进业务状态，属低频待办视图而非每日动线；复核页 / 提醒页互为上下文切换入口（PageShell actions Link，href 指向原路径），不占作业中心可见菜单位。 |
| 采购比价 | `/procurement/price-compare` | 降级为上下文二级入口（`hideInMenu: true`，路由与 routeElements 保留） | 低频专用查询：仅缺货补货时按采购工单或 SKU 发起比价的 Agent 工具（POST `/api/v1/procurement-price-agent/compare`），不是每日动线；其查询输入来自采购协同工单上下文 → 上下文入口放在采购协同页（FilterBar actions，href 指向原路径）。 |
| 出库信息对账 | `/fulfillment/outbound-recon` | 同上 | 低频专用查询：按出库单号/京东单号/订单号核对单笔出库的系统内部事实与京东侧事实，属专项核对工具，不是每日动线；查询主键（系统出库单号）由发货记录的行/抽屉承载 → 上下文入口放在发货记录页（页头 actions，与既有刷新按钮同排，href 指向原路径）。文件作业（销售出库）页的导出明细虽含出库单号，但属履约指令文件侧，不重复放置，避免同一入口散落多处。 |

**不动的一级入口及其符合准入的依据**：作业中心 6 个高频入口（人工复核/渠道消息/履约任务/
采购协同/文件作业/发货记录）均为每日动线主线页面；订单中心（4 个可见叶子）、系统管理（含
京东工具分组）等板块均 ≤ 6 且为主数据/配置/查询主线，符合 1.1。

**Issue #89 新增可见叶子 `/system/operators`（运营人员）的依据**：属于系统管理板块的
主数据/配置主线（与渠道接入/操作审计/履约方配置同类），是「运营人员 ↔ 企微 userid ↔
责任团队」映射的登记入口——系统第一次有「人」的概念，后续复核责任归属与个人推送都依赖
该登记，属组织配置的日常动线；直接可见子项 5 ≤ 6，不超上限。

证据（git 历史）：`7005fd5` 时点的 `navigation.ts` 作业中心即为 6 个高频入口（
business-object-navigation 01 验收口径）；`00e1e6c` 加入出库信息对账 → 7；
`29ed999` 加入采购比价 → 8。Issue #98 现状即 8 个可见叶子，超过设计口径 6。
Issue #64 的 `/workbench/alerts` 是隐藏叶子，不改变可见计数。

## 3. 前后计数（按当前代码实时重算）

计数规则：一级板块 = `appNavigation` 顶级项数（含 `/demo/order` 演示页与 `/bi` 外链）；
可见入口 = `flattenNavigationLeaves(visibleNavigationTree(appNavigation))`（含外链）；
可路由叶子 = `routableNavigationLeaves(appNavigation)`（不含 external）。

| 指标 | 收敛前 | 收敛后 |
|---|---|---|
| 一级板块（顶级菜单项） | 10 | 10 |
| 可见入口（可见叶子，含外链） | 31 | **30**（Issue #89 新增 `/system/operators` 运营人员可见叶子） |
| 可路由叶子（不含外链，全部生产路由） | 38 | **40**（Issue #64 新增 `/workbench/alerts` 隐藏叶子；Issue #89 新增 `/system/operators`） |
| 作业中心可见叶子 | 8 | **6** |

> Issue 记录的「9 个一级板块 / 34 可见入口」是记录时点口径，与当前代码不一致（此后新增了
> 静态礼包等入口），一律以本表实时重算为准。复核命令（frontend 目录下）：
> `node --experimental-strip-types -e "import { appNavigation, visibleNavigationTree, flattenNavigationLeaves, routableNavigationLeaves } from './src/navigation.ts'; const v = flattenNavigationLeaves(visibleNavigationTree(appNavigation)); console.log('一级板块', appNavigation.length, '可见入口', v.length, '可路由叶子', routableNavigationLeaves(appNavigation).length);"`

## 4. 后续新增菜单的检查步骤（Checklist）

新增或调整菜单时，逐项勾选：

1. [ ] 判定 primary vs contextual secondary（1.1）：页面属于哪条业务动线？每日高频还是低频专用？
2. [ ] 检查所属一级板块**直接可见子项**（可见叶子或分组节点各计 1 项）≤ 6；作业中心 = 6 个
      可见叶子；超限必须降级或分组。
3. [ ] 降级入口三件套：`navigation.ts` 加 `hideInMenu: true`；`routes.tsx` 的 routeElements
      保留；高频父页面加上下文 Link/Button（`href` 指向原路径）。
4. [ ] 引用 CONTEXT.md 术语说明业务对象归属，不引入新边界、不与既有业务对象冲突。
5. [ ] 更新本文件第 2 节收敛清单与第 3 节计数。
6. [ ] 测试门禁全绿（第 5 节）。

## 5. 测试门禁（导航相关）

- `frontend/test/businessObjectNavigation.test.ts`：
  - 作业中心高频可见叶子固定为 6 个设计名单并断言 ≤ 6 上限；
  - 被降级入口 `hideInMenu === true`、不出现在可见菜单、**仍在 routable 叶子中**（旧路径不 404）、
    `navigationContext` 仍解析出作业中心归属（含 Issue #64 的 `/workbench/alerts`）；
  - 无重复叶子路径。
- `frontend/test/procurementTicketsRoute.test.ts`：采购协同页上下文入口存在、`href` 正确，
  点击可达采购比价页。
- `frontend/test/fulfillmentPagesRoute.test.ts`：发货记录页上下文入口存在、`href` 正确，
  点击可达出库信息对账页。
- `frontend/test/alertsQueueRoute.test.ts` + `manualReviewQueueRoute.test.ts`：运营提醒 /
  复核两路由互为上下文切换入口；旧 `?view=alerts` 链接重定向到 `/workbench/alerts`。
- 全量门禁：`cd frontend && npm run typecheck && npm test && npm run build`，
  `git diff --check`。
