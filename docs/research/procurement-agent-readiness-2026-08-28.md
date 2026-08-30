# 采购 Agent 运行环境就绪度评估

- 日期：2026-08-28（核对时点 23:45 CST / 15:45 UTC）
- 代码线：`/Users/jerry/zimu-work/integration`，分支 `jry/integration-20260828`，HEAD = `07b8a548`
- 生产：镜像 `zimu-fulfillment-backend:real-07b8a548`，容器创建于 **2026-08-28 23:34:06 CST**；迁移已执行到 **V83**（V83 于 23:34:11 随本次启动应用）
- 性质：**只读评估**。本文件是本次调查唯一新增产物——未改代码、未提交、未部署、未对生产做任何写操作、未开票、未读取或打印任何凭据值
- 触发问题：
  1. 「采购的 Agent 现在还没有启用」——运行环境到底可不可用？
  2. 「采购不只是在没货的时候才触发采购，有货的时候也可以每天搜一遍报价看看」——两种触发模式的现状？

> **时效警告**：生产容器在本报告写完前约 10 分钟（23:34）刚刚重建，且正好落在本次调查过程中。本报告中所有生产事实均为 23:34 之后重新核对的值。本次调查早期（23:34 之前）得到的运行时结论——特别是「生产在跑 V77 / 血统落后于 HEAD」——**已经过期，不要再引用**：生产此刻与分支 HEAD 完全同版本。

---

## 1. 一句话结论

**不能启用。**

「启用」有两个不同含义，必须分开回答：

| 你想要的 | 能不能今天拿到 | 最短路径 |
|---|---|---|
| A. 采购工作台的「比价」按钮不再 500，能出一份只读比价建议 | **能**，但拿到的东西价值接近 0 | 生产 override 里把 `MCP_MODULES` 从 `masterdata,inventory,orders-read` 改成 `masterdata,inventory,orders-read,procurement`，重启后端。一个环境变量，一次重启 |
| B. 用户真正要的：缺货驱动 + 每日询价两种自动触发的采购 Agent | **不能**，今天做不到 | 不是配置问题。缺 3 段没写的代码 + 2 张没建的表 + 1 个没有答案的业务问题（报价从哪来） |

为什么 A 拿到的东西价值接近 0：生产 `procurement_tickets` 0 行（工作台是空的），**0 个 SKU 关联超过 1 个履约方**（比价的候选集最多 1 项，`price_outlier` 中位数规则永远不可能触发），而它能读的「价」只有我们自己库里的 `skus.purchase_price` 和履约方映射。按钮通了，它只能对着唯一一个候选说「就这一家」。

**最要命的一条**：把 `MCP_MODULES` 改好，得到的只是那个手工按钮。用户问的两种触发模式，代码里**一条都不存在**——`ProcurementPriceAgent` 在 `backend/src/main` 里唯一的调用方就是那个 REST controller（`AgentRegistryConfiguration` 里的另一处出现经复核只是一句 javadoc）。所以真正最硬的闸门不是 `MCP_MODULES`，是**没有触发入口**。

---

## 2. 就绪度分项

| 层 | 状态 | 一句话 |
|---|---|---|
| Agent 注册表 / 模型凭据 | ✅ 开着 | `procurement-price-agent` v2 = `status=active` / `enabled=true`；模型 provider/model/base-url/api-key 均已注入 |
| 配置 | ⚠️ 一道硬闸门 | `MCP_MODULES` 不含 `procurement`，Agent 白名单里 3 个工具取不到，严格绑定路径直接抛异常 |
| 代码 | ❌ 缺触发入口与执行面 | 两种触发模式都没有接线；采购域无任何写工具 |
| 数据 | ❌ 模型都没建 | 无供应商实体、无报价历史、4 张采购表 0 行、无可比候选 |

所以「采购 Agent 没启用」这句话在**注册表层面并不准确**——它注册了、是 active 的，只是**从投产至今一次都没跑过**（`agent_runs` 里 `agent_slug LIKE '%procurement%'` 为 0 行）。

### 2.1 代码：能力面是有的，接线是断的

**「采购」在代码里是两个互不调用的包。**

- `backend/src/main/java/cn/zimu/fulfillment/procurement/`：7 个文件、约 448 行，纯工单 CRUD（列表 / 详情 / retry / cancel-remaining / receipt）。没有 Agent、没有 Scheduler、没有模型调用；审计 `actorType` 硬编码 `HUMAN`。
- `backend/src/main/java/cn/zimu/fulfillment/agent/procurement/`：7 个文件、约 750 行，比价 Agent 本体。

两个包之间**没有任何 import 或依赖**。

**缺货信号链完全不认识采购。** 生产上真实产生 `JD_STOCK_INSUFFICIENT` 的是 `ShipmentJdStockCheckService`（`backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdStockCheckService.java`）——对该文件 grep `procurement` **零匹配**（我已复核）。缺货时它只往 blockers 里加一条阻断文案就返回 BLOCKED，不建工单、不触发 Agent、不发采购事件。**这直接解释了彩食鲜欧希那那单为什么系统里没有任何采购动作：不是漏配置，是这条链路压根没接。**

**旧的建单入口是孤儿，而且对京东已显式停用。** 全仓唯一的生产侧 `INSERT INTO app.procurement_tickets` 在 `FulfillmentStockDecisionService.createTicket()`。复核结果：`grep -rn "FulfillmentStockDecisionService" backend/src/main` 只返回该文件自身的 5 处（类声明 / 构造器 / 3 处审计字符串），**没有任何 Controller、Worker、Scheduler 注入它**；引用它的只有 2 个测试类。而且它的 `apply()` 对 `JD_WAREHOUSE` 直接抛 `JD_STOCK_DECISION_RETIRED`（类注释写明旧 JD 分支已被 Shipment 级实时库存检查取代）。**即使有人把它调起来，京东单也会被拒。**

**采购域没有任何写能力。** MCP 侧 `procurement` 模块只有 3 个工具，全只读：`list_procurement_tickets` / `get_procurement_ticket` / `list_procurement_receipts`（`backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java:92,98,104`，我已复核三处模块标签）。京东 SDK 的 `orderPurchaseCreate` / `orderPurchaseQuery` / `orderPurchaseClose` 确实和其余 54 个接口一样是**包了没用**：`procurement` 与 `agent.procurement` 两个包对 `JdWriteOps*` / `JdOrderClient` 零引用，这三个接口只挂在原始透传运维口 `JdWriteOpsController` 上。

### 2.2 配置：一道硬闸门，但它不是采购专属的

**生效值（我在 23:34 新容器上直接读取）：**

| 变量 | 生产值 | 影响 |
|---|---|---|
| `MCP_MODULES` | `masterdata,inventory,orders-read` | **闸门**：`procurement` 模块被排除 |
| `JD_GENERIC_HTTP_WRITE_MODE` | `OFF` | 京东通用写口（含 `orderPurchaseCreate`）锁死 |
| `MCP_HTTP_ENABLED` | `true` | 对外 `/mcp` HTTP/SSE 面开着（nginx 已代理 `/mcp`、`/mcp/sse`、`/mcp/messages`） |
| `SCHEDULED_TASKS_ENABLED` | 未设置 | 走 yml 默认 `true`（且 `matchIfMissing=true`，双重默认开）——但它管不到采购，`procurement` 包内零个 `@Scheduled` |
| `*PROCURE*` | 118 个环境变量里**一个都没有** | 采购侧在运行环境层没有任何显式开关 |

`application.yml` 全文对 `procure` 零命中；唯一的 procurement 配置前缀 `app.agent.procurement-price.outlier-multiple` 连 yml 里都没写，只有代码默认值。

**闸门机制（我已逐环复核代码）：**

`ProcurementPriceAgent.compare` → `facade.invoke(...)` → `invokeResolved(..., allowedReadOnlyModules=null)` → 因为是 `null`，走 `AgentRuntimeFacade.java:186` 的**严格分支** `toolBindingFactory.bind(...)`；`bind()` 内对白名单里 registry 查不到的工具 `orElseThrow(new IllegalArgumentException("Agent 工具白名单引用未知 MCP 工具: ..."))`（`AgentToolBindingFactory.java:93`）。而 `McpToolRegistry` 的 `byName` 索引只装启用模块的工具 → `list_procurement_tickets` 查不到 → 抛异常 → facade 记一条 `AGENT_RUNTIME_EXCEPTION` 后原样上抛 → `GlobalExceptionHandler` 无 `IllegalArgumentException` 专门处理器，落到 `Exception.class` → **HTTP 500 `INTERNAL_ERROR`**。

**关于这道闸门，有三条必须写清楚的更正（否则会诱导出错误的修法）：**

1. **它不是采购专属的，是平台级一刀切。** 同一份 `MCP_MODULES` 还排除了 `orders` / `messages` / `control` / `followup` / `default` 等模块。走严格 `bind()` 的受害者不止采购一个：`customer-followup-agent`（工具全在 `followup`）、`fulfillment-file-agent`（`orders`）、`meta-agent`（`McpWriteTools` 无 module 参数 = `default`）、以及 `data-query-agent` 的 REST 路径。**只加一个 `procurement` 是局部修法，会留下同类隐患。**
2. **它只闸住手工按钮。** 用户问的两种自动触发模式在代码里根本不存在，所以这道闸门对用户的问题而言是**次要的**。
3. **它至今一次都没真的响过。** 当前容器 23:34 创建，而 `agent_runs` 最新一行是 16:53:23+08（= 08:53 UTC），全部早于当前容器——当前配置下没有任何一次 Agent 运行。（前一个容器是否已开启模块过滤无法直读，见 §6。）

**被推翻的说法（不要再引用）：「新增定时任务前必须先扩调度池」。** 池大小约束的是**并发执行数**，不是注册的 `@Scheduled` 触发流数，不存在「一条流一根 lane」。HEAD 上 28 处 `@Scheduled` 里有相当一部分默认开关为 `false`（JD 运单回填、三个 followup worker、quality-eval、source-sync auto、整个 wecom 家族），进方法即 return。一条 daily cron 每天贡献 1 次执行，相对现有 500ms 轮询 worker 的日均 17 万次可忽略。仓库里 `backend/src/main/java/cn/zimu/fulfillment/connector/schedule/ScheduledPlatformPullTrigger.java`（2026-08-28）正是在明知「13 线程 / 20 多条流」的前提下加了两条 cron 而**没有动池**，并在注释里给出了正确做法：`@Scheduled` 方法只把任务丢进本特性自己的单线程 executor 就返回，不占调度线程。若要遵守团队 +1 惯例，那是新增 `@Scheduled` **同一个 commit 里改一行 int**，不是前置工序。

### 2.3 数据：缺的不是「数据没灌」，是「模型没建」

我复核的生产事实：

| 事实 | 值 |
|---|---|
| `procurement_tickets` / `procurement_ticket_items` / `procurement_receipts` | **0 / 0 / 0 行**（自增序列 `is_called=never`——从投产至今一条都没产生过） |
| `information_schema` 里名字含 supplier / vendor / quote / quotation / rfq 的表 | **0 张** |
| 最接近「供应商」的实体 `fulfillment_providers` | **2 行**，且都是履约通道（JD 京东云仓 / TP 第三方履约），不是可比价的供货商 |
| `provider_skus` 列 | `id, fulfillment_provider_id, sku_id, provider_sku_code, merchant_sku_code, external_codes, active, lock_version, created_at, updated_at` ——**没有价格列** |
| 关联超过 1 个履约方的 SKU | **0 个** |
| `order_events` 里 `procurement_ticket_id IS NOT NULL` | **0 行** |
| `agent_runs` 里 procurement 相关 | **0 行**；`agent_eval_results` 全表 **0 行** |
| `JD_STOCK_CHECKED` 事件 | 34 次：PASSED 27 / `JD_SKU_MAPPING_GATE_BLOCKED` 4 / **`JD_STOCK_INSUFFICIENT` 3** |

**三条结构性结论：**

1. **信号到动作是断的。** 触发条件在生产命中过 **3 次**，却产生了 **0 张**采购工单。
2. **schema 锁死了「只支持缺货驱动」。** `procurement_tickets.fulfillment_id` 是 `NOT NULL`——每张工单必须挂在一个已存在的履约任务上。**用户要的「有货时每天搜一遍报价」没有履约任务，在当前表结构里无处可写。**
3. **比价的枚举有一半是空的。** 输出 schema 的 `price_basis` 只有 `sku_commercial_price` 与 `provider_sku` 两个取值，而 `provider_skus` 表根本没有价格列。

**关于价格刷新，一条被推翻的说法要更正。** 之前有结论说「价格是导入时写死的静态值，从未刷新」——**这是错的**。对照生产上的改前备份表 `app.zz_price_conv_skus_20260828` 与当前 `app.skus`（我已复核）：**46 个 SKU 由 NULL 变成有值，26 个 SKU 的既有非空进货价被改写成了另一个非空值**。价格确实在 2026-08-28 下午被批量重写过（对应成本表 AI/AJ 口径的重新取价）。

但**真正的问题比原说法更严重**：这次重写是**完全离线的手工 SQL**（审计表无对应记录，`zz_` 前缀备份表是手工操作的痕迹）。仓库里唯一能批量写 `purchase_price` 的代码 `backend/src/main/java/cn/zimu/fulfillment/catalog/AuthoritativeSkuCatalogImportService.java` 在生产**从未运行过**，而且**按设计就不能刷新价格**——它要求 `sku.getPurchasePrice() == null` 才写，源价格变了会被判为 drift 并**拒绝应用**。也就是说：**今天没有任何自动的价格刷新通路；就算做出了「每日询价」，现有导入代码也会把每一次价格变化当成漂移丢掉。** 这是一段要写的代码，不只是缺一个 cron。

---

## 3. 两种触发模式的现状

| | 缺货驱动（被动） | 每日询价（主动巡检） |
|---|---|---|
| 实现程度 | **约 30%**：表结构齐、建单方法存在 | **0%**：概念在代码和数据模型里都不存在 |
| 触发源 | **无**。`ShipmentJdStockCheckService` 对 procurement 零引用 | **无**。`procurement` / `agent.procurement` 包内零个 `@Scheduled`；`AgentDefinitionWorker` 只领 5 类「Agent 定义管理」任务，不跑业务 Agent |
| 建单入口 | 有，但是孤儿 bean，且对 `JD_WAREHOUSE` 抛 `JD_STOCK_DECISION_RETIRED` | 无。且 `procurement_tickets.fulfillment_id NOT NULL`，主动询价的结果**无处落库** |
| 数据 | 信号有（3 次 `JD_STOCK_INSUFFICIENT`），工单 0 张 | 供应商实体、候选 SKU 清单、报价历史**三样都不存在** |
| 入参形态 | 单工单，够用 | **不够用**：`ProcurementPriceInput.parse` 强制 `procurement_ticket_id` 或 `sku_id` 至少其一（`ProcurementPriceInput.java:39-41`），一次只能比一个目标，没有批量/全量扫描形态 |
| 价格来源 | 内部主数据 | **内部主数据**——`price_basis` 是封闭枚举，拿不到任何外部行情 |

### 缺货驱动的代价：接一根线 + 一个落点决策

这条是「差一根线」，数据模型本身够用。但**不是打开开关，是新写代码**：

- 旧入口已对京东停用，不能复用。要接的是 `ShipmentJdStockCheckService` 产出 `JD_STOCK_INSUFFICIENT` 之后的那一跳。
- 需要决定落点：是在阻断时同步建工单，还是发一个领域事件由 worker 异步建单（后者更符合仓库既有形态）。
- 需要决定工单与 shipment 的关系（现在 `fulfillment_id NOT NULL`，京东实时库存检查是 Shipment 级的，两者粒度对不上——这是必须先想清楚的建模问题）。

### 每日询价的代价：先回答一个业务问题，否则做出来也是空转

**这条不是「加个定时器」的量级。** 缺四个前提，且都在定时器之前：

1. **报价从哪来？**（最关键，且这是业务问题不是技术问题）当前实现的 `price_basis` 只有两个内部口径，全仓无任何外部询价 API 配置。**外部价格源不接，「每天搜一遍报价」在当前实现下就等于每天重读一遍自己库里的存量价格，没有任何新信息。**
2. **向谁询价？** 全库无 suppliers / vendors 表，「供应商」这个实体在数据模型里不存在。
3. **询哪些 SKU？** 没有任何表定义「哪些 SKU 需要每日问价」这个候选集。
4. **询回来写哪？** 无报价历史结构（价格只有 `skus.purchase_price` 一个当前标量，无供应商维度、无时间序列、无快照），且现有工单表挂履约任务，装不下主动询价。**没有「上次报价」就没有「比」，只有「读」。**

再叠加 §2.3 那条：即使新报价拿回来了，现有导入代码会把价格变化判为 drift 拒绝写入。

**建议**：这条先做产品决策，不要先排期写 cron。定时器是这条链上最便宜的一环。

---

## 4. 启用清单（按顺序）

### 档 A — 今天能做完：让手工比价按钮不再 500

| # | 动作 | 类型 | 验收 |
|---|---|---|---|
| A1 | 决定 `MCP_MODULES` 的加法：只加 `procurement`，还是顺带把 `orders` / `followup` / `default` 一起补齐（见 A2 的副作用评估） | 决策 | —— |
| A2 | **先评估对外暴露面**：`MCP_HTTP_ENABLED=true` 且 token 已配、nginx 已代理 `/mcp`、`/mcp/sse`、`/mcp/messages`。`MCP_MODULES` 是这个**对外** HTTP/SSE 面的唯一模块围栏——加 `procurement` 等于同时把采购工单缺口、回执、关联订单行开给任何持 token 的外部 MCP 客户端，不只是给内部 Agent 开权限 | 安全评估 | 明确「谁持有这个 token」后再动 |
| A3 | 生产 override 改 `MCP_MODULES`，重启后端 | 配置（需人工执行，本次未做） | 容器 `printenv MCP_MODULES` 含 `procurement` |
| A4 | 调 `POST /api/v1/procurement-price-agent/compare`，传一个真实 `sku_id`（不能传 ticket_id——生产 0 张工单） | 验证 | 不再 500；`agent_runs` 出现 `procurement-price-agent` 记录 |
| A5 | 看 `agent_runs.error_type`。**注意这里有第二道未验证的坎**：现有 21 次运行里 4 次 FAILED，其中 3 次是 `AGENT_MODEL_CALL_FAILED`（`customer-followup-agent` 1 次、`meta-agent` 2 次）、1 次 `AGENT_OUTPUT_INVALID`。模型调用链对「用工具的 Agent」是否稳定**尚未被证明** | 验证 | 拿到 SUCCESS 才算 A 档完成 |

**A 档做完你得到的是**：一个人点一次、比一个 SKU、只读自家主数据、候选最多 1 项、只出建议不落业务表的比价按钮。**不要把它当成「采购 Agent 启用了」。**

### 档 B — 缺货驱动（需要写代码，不是配置）

| # | 动作 | 类型 |
|---|---|---|
| B1 | 决定工单粒度：跟 shipment 走还是跟 fulfillment 走；`procurement_tickets.fulfillment_id NOT NULL` 是否要松开 | 建模决策 |
| B2 | 在 `ShipmentJdStockCheckService` 产出 `JD_STOCK_INSUFFICIENT` 之后接建单（同步或事件驱动，建议后者） | 新代码 |
| B3 | 决定旧 `FulfillmentStockDecisionService` 的去留（复活 / 抽取 createTicket / 整体删除）——它现在是孤儿且对 JD 停用 | 重构决策 |
| B4 | 建单后是否自动调比价 Agent，还是等人在工作台点 | 产品决策（见 §5） |
| B5 | 回填验证：拿历史那 3 次 `JD_STOCK_INSUFFICIENT` 做用例 | 测试 |

### 档 C — 每日询价（先决策，后编码）

| # | 动作 | 类型 |
|---|---|---|
| C1 | **回答「报价从哪来」**：外部供应商 API / 人工录入 / 平台抓取？没有答案就不要往下走 | 业务决策（阻塞全档） |
| C2 | 建供应商实体（suppliers）与报价历史表（带供应商维度 + 时间序列） | 新表 |
| C3 | 定义「每日询价候选 SKU」清单的来源 | 建模 |
| C4 | 扩 `ProcurementPriceInput` 支持批量/扫描形态（现在强制单目标） | 新代码 |
| C5 | 扩 `price_basis` 枚举 + 改 `AuthoritativeSkuCatalogImportService` 的 fill-once/drift 语义，否则新价格写不进去 | 新代码 |
| C6 | 加 `@Scheduled` daily 巡检——**照 `ScheduledPlatformPullTrigger` 的模式写**：调度线程只做 submit 后立即返回；顺带在同一 commit 里把 `ScheduledTaskPoolConfiguration` 的池默认值和两处「13 条」过期注释对齐 | 新代码（**不是前置**，是最后一步） |

---

## 5. 风险：采购会不会产生真实的对外动作？

### 现状：不会。今天有四道锁，全都是关着的

| 锁 | 状态 |
|---|---|
| `agent_definitions.allow_write` | `false`（生产实测） |
| 采购域 MCP 写工具 | **不存在**（`McpWriteTools` 里没有任何采购工具） |
| 系统提示词 | v2 明写「不发起采购、不下单、不修改任何工单；建议不落业务表」 |
| 京东通用写口 `JD_GENERIC_HTTP_WRITE_MODE` | `OFF`（生产实测），`orderPurchaseCreate` 锁死 |

**所以档 A（只改 `MCP_MODULES`）不会产生任何对外动作。这是安全的。** 它只让 Agent 多读到 3 个只读工具。

### 必须标出来的风险点（按危险度排序）

**R1 — 京东 `orderPurchaseCreate` 是对京东下真单，不是内部记账。** 一旦有人把它从 `JdWriteOpsController` 的透传口接进采购流程，Agent 的「建议」就变成了真实采购订单。**这条必须是显式人工确认，不能自动。** 建议的门闩形态：Agent 只能产出 `PROPOSED` 状态的采购建议 → 人在工作台看到完整快照（SKU、数量、单价、供应商、总金额）→ 点确认 → 由**服务层**（不是 Agent）执行下单，审计 `actorType=HUMAN` 记录确认人。**不要给 Agent 任何能直接触达 `orderPurchaseCreate` 的写工具。**

**R2 — 「给供应商发消息」目前没有通路，但企微通道是现成的。** 系统里没有供应商实体、没有对外供应商通道，所以今天不可能发。但企微出站是成熟基建——一旦有人把「每日询价」实现成「把询价单推到供应商群」，那一刻起这就是**代表公司对外发消息**。**这必须是显式人工确认，不能由 cron 自动发。** 而且要按既有的敏感度约束走（询价单含价格与用量，属于不能群发的内容类型）。

**R3 — 自动触发 × 写权限 = 危险组合，且这正是用户要的方向。** 用户明确要「每天自动搜一遍」。**定时触发本身没问题（读是安全的），但一旦哪天给采购 Agent 开了写工具，同一个 cron 就变成了无人值守的对外动作发生器。** 建议现在就把边界写死成一条不变量：**凡是 Agent 自动触发的路径，产物只能是 `PROPOSED` / 建议，永远不能是已执行的对外动作。** 执行必须由人在 UI 上确认，且确认动作要落审计。

**R4 — 档 A 的 `MCP_MODULES` 修改有对外暴露副作用（不是采购动作，是数据暴露）。** 见 A2：`/mcp` HTTP/SSE 面对外开着，`MCP_MODULES` 是它唯一的模块围栏。加 `procurement` 会把采购工单缺口/回执一并开给持 token 的外部客户端。**改之前先确认这个 token 发给了谁。**

**R5 — 价格写入路径目前是「手工 SQL」。** 今天的价格重写（46 条填充 + 26 条改写）走的是审计外的手工操作。如果未来询价结果要落进 `skus.purchase_price`，必须先把这条路径纳入有审计的代码通路，否则「价格被谁改成什么」在系统里查不到——而采购决策直接依赖这个数。

---

## 6. 未验证项（如实列出）

1. **前一个容器的 `MCP_MODULES` 无法读取**——容器已在 23:34 被替换销毁。「这道闸门是最近才生效的」是从 `customer-followup-agent` 在 10:26 报 `AGENT_MODEL_CALL_FAILED`（该错误码产地在 `LangChain4jRuntimeAdapter`，位于 `runtime.run()` 内部、严格晚于 bind）反推的**强推断**，不是直读。
2. **「现在点比价会 500」是代码路径推断，无运行实例佐证。** 当前容器下 `agent_runs` 没有任何记录，我也**没有实际发起调用**（那会在生产产生一次真实的模型调用与运行记录，超出只读范围）。
3. **模型调用链对「用工具的 Agent」是否可用，未单独验证。** 只知道 21 次运行里 4 次 FAILED、3 次是 `AGENT_MODEL_CALL_FAILED`。修好 `MCP_MODULES` **不等于**按钮就能跑通。
4. **今天那次价格批量重写的执行者与执行方式没有直接证据**——只有备份表 `zz_price_conv_skus_20260828` 与审计缺口两条间接证据。「手工 SQL」是推断。
5. **未逐个确认 HEAD 上 28 处 `@Scheduled` 在生产条件装配下都被注册**（大量带 `enabled:false` 门闩）。
6. **未核实前端在 500 下的表现**——采购工作台点比价失败时用户看到什么，没有验证。
7. **未验证生产 `MCP_HTTP_TOKEN` 发放给了谁**（只确认变量存在，未读取值）。这是 A2 决策的关键输入，需要人来回答。
8. **本报告的生产事实只代表 23:45 CST 这一时刻。** 生产容器在 23:34 刚被重建过一次，环境随时可能再变。

---

## 附：本次已直接复核的证据索引（绝对路径）

代码（`/Users/jerry/zimu-work/integration`，HEAD `07b8a548`）：

- `backend/src/main/java/cn/zimu/fulfillment/agent/procurement/ProcurementPriceAgent.java` — Agent 本体，`AGENT_SLUG="procurement-price-agent"`
- `backend/src/main/java/cn/zimu/fulfillment/agent/procurement/ProcurementPriceAgentController.java` — 唯一触发入口 `POST /api/v1/procurement-price-agent/compare`
- `backend/src/main/java/cn/zimu/fulfillment/agent/procurement/ProcurementPriceInput.java:39-41` — 强制单目标入参
- `backend/src/main/java/cn/zimu/fulfillment/agent/AgentRuntimeFacade.java:186-192` — `allowedReadOnlyModules == null` 走严格 `bind()`
- `backend/src/main/java/cn/zimu/fulfillment/agent/AgentToolBindingFactory.java:93-99` — `orElseThrow("Agent 工具白名单引用未知 MCP 工具")`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java:92,98,104` — 3 个工具的 `"procurement"` 模块标签
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdStockCheckService.java` — 对 procurement 零引用
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/FulfillmentStockDecisionService.java` — 孤儿建单入口 + `JD_STOCK_DECISION_RETIRED`
- `backend/src/main/java/cn/zimu/fulfillment/catalog/AuthoritativeSkuCatalogImportService.java` — fill-once / drift 拒绝改写
- `backend/src/main/java/cn/zimu/fulfillment/connector/schedule/ScheduledPlatformPullTrigger.java` — 新增 `@Scheduled` 的正确模式
- `backend/src/main/resources/db/migration/V33__agent_platform_definitions.sql`、`V45__procurement_price_excluded_candidates.sql` — Agent 定义与 `price_basis` 枚举

生产（只读）：

- `printenv MCP_MODULES` → `masterdata,inventory,orders-read`
- `printenv JD_GENERIC_HTTP_WRITE_MODE` → `OFF`；`MCP_HTTP_ENABLED` → `true`；`SCHEDULED_TASKS_ENABLED` → 未设置
- `app.agent_definitions` → v1 retired / **v2 active, enabled=true, allow_write=false**
- `app.agent_runs` → 21 行，procurement 相关 **0 行**；4 次 FAILED（3× `AGENT_MODEL_CALL_FAILED`、1× `AGENT_OUTPUT_INVALID`）
- `app.procurement_tickets` / `procurement_ticket_items` / `procurement_receipts` → **0 / 0 / 0**
- `app.order_events` `JD_STOCK_CHECKED` → 34 次（`JD_STOCK_INSUFFICIENT` **3 次**）；`procurement_ticket_id IS NOT NULL` → 0
- `provider_skus` 列清单（无价格列）；关联多履约方的 SKU → **0**
- supplier/vendor/quote/rfq 类表 → **0 张**
- `app.skus` vs `app.zz_price_conv_skus_20260828` → NULL→有值 **46**，非空→改写 **26**；92 个 SKU 中 77 个有进货价
- `public.flyway_schema_history` → 最高 **V83**，2026-08-28 23:34:11 应用
