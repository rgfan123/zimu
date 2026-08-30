# 旧线（`b65ec8cd` / `jry/` 谱系）移植评估

- 日期：2026-08-28
- 范围：`claude/jufubao-pull-order-issue-641416` 与 `claude/feixiang-shipment-har-analysis-f870f7` 两个旧线工作区
- 目标线：`jry/integration-20260828`（生产线，代码在仓库根，迁移已执行到 V77）
- 性质：**只读评估**。本文件是本次调查唯一新增产物，未改动任何其它文件、未提交、未部署、未对生产做任何写操作。

---

## 1. 一句话结论

**两条旧线基本没有移植价值：45 条判定里初判「值得移植」17 条，经对抗复核后只有 1 条站得住——一个 10 行的聚福宝拉单脚本时间窗右端点修复；而且连这一条都不在任何分支上，它是一个未提交的脏工作区改动。**

分布：

| 类别 | 条数 |
|---|---|
| 复核后仍成立、建议移植 | **1** |
| 初判可移植但被复核推翻 | 16 |
| 今线已有更强实现（SUPERSEDED） | 8 |
| 明确丢弃（DISCARD） | 17 |
| 需要用户产品裁决，不属于移植 | 2 |
| 飞象写回传专项提问 | 1 |

推翻率 16/17。最常见的失效模式不是「判断偏差」，而是**主张描述的旧线改动在任何 commit 里都不存在**——至少 7 条属于这一类（详见 §6）。这说明第一轮分析把「读今线代码时想到的改进」误标成了「旧线的产物」。凡是后续还要引用本清单的人，请以复核结论为准，不要引用第一轮的 reason 字段。

---

## 2. 移植清单（唯一一项）

### P1 — `scripts/jufubao_fetch_orders.py`：时间窗右端点取当日 23:59:59

| 项 | 内容 |
|---|---|
| 移植什么 | `_to_epoch` 增加 `end_of_day` 关键字参数；`query_orders` 的右端点改为 `_to_epoch(cfg.end, end_of_day=True)` |
| 规模 | 2 个 hunk，10 insertions / 3 deletions，纯 stdlib（`datetime.replace(hour=23, minute=59, second=59)`） |
| 成本 | TRIVIAL |
| 风险 | 极低（脚本不在任何生产执行路径上，见下） |
| 建议落点 | 一张独立小票，或搭车任何一次 `scripts/` 维护。不需要单独排期 |

**为什么值得**

1. **bug 在今线原封不动还在。** `/Users/jerry/zimu-work/integration/scripts/jufubao_fetch_orders.py:122-123` 是 `start_ts = _to_epoch(cfg.begin)` / `end_ts = _to_epoch(cfg.end)`；`:190` 的 `def _to_epoch(day: str) -> int` 只做 `replace(tzinfo=+08:00)`，没有任何右端点处理；`:126` 直接把它塞进 `created_time_range`。而 `--end` 默认是今天，所以**默认跑法必然把「今天创建的待发货单」整体排除**——待发货单恰恰多是当天新建的。
2. **两条线在这个文件上还没分叉。** 旧线 HEAD 版与今线版 `diff` 为空（IDENTICAL BASE），移植是干净的 patch，不是重写。
3. **今线自己就有独立佐证，不依赖旧线的实测。** 今线 `docs/research/jufubao-supplier-export-api.md:52` 存着平台前端自己发的真实请求体：`start_time=1786418151, end_time=1787022951`，解出来是 **2026-08-11 11:15:51 → 2026-08-18 11:15:51 (CST)**，正好 7.0 天。平台 UI 自己发的右端点是**抓包那一刻的瞬时值，不是午夜对齐**——这直接证明 `end_time` 是裸时间戳比较且接受任意非午夜值，因而右端点取当日 00:00 会丢掉那一整天。
4. **脚本仍是活的工具，不是死代码。** `JufubaoSessionAdapter.java:47` 与 `:201` 两处注释把它称作「已验证可用的参考实现」（今天修聚福宝登录时正是照着它对齐 UA / Accept / `X-Jfb-Project-Id` / HTTP 1.1）；`docs/research/jufubao-mapping-archive-2026-08-27.md:122` 还靠它拉真实订单做「编号 ↔ 品名」对照；`jufubao-pull-push-receiver-closure-2026-08-24.md:105` 明写「在生产 Connector 和 receiver 契约验证完成前不要删除」。
5. **只读查询的过滤参数，不写平台任何状态。** 与 `ajaxSendOrderProduct` / `multi-send` 那类外部写接口完全不同类。

**Java 侧不需要同样的修。** 今线 `JufubaoConnector.epochRange()`（`:503-510`）用的是 `end.plusDays(1).atStartOfDay(SHANGHAI)`，已经覆盖 end 当天。**不要顺手去「对齐」它**——见下面第 3 条隐藏成本。

**移植前必须知道的三点**

1. **它不在任何分支上。** `git log --all -S"end_of_day"` 全仓返回空。改动只以未提交的工作区修改形式活在
   `/Users/jerry/Documents/子牧/.claude/worktrees/jufubao-pull-order-issue-641416`（分支 `claude/jufubao-pull-order-issue-641416` @ `b65ec8cd`，`git status` 显示 23 个 `M` + 12 个 `??`）。
   后果：**无法 cherry-pick，只能按绝对路径手工复制或重打**；一条 `git checkout .` 就永久没了。若要保留，先把这个 hunk 存成 patch 文件。
2. **不要顺手把今线文档的「时区口径」未决行划掉。** 今线 `docs/research/jufubao-supplier-export-api.md:144` 的
   `| 时区口径 | created_time_range 的 epoch 起点（当天 00:00 所属时区）待验证 |` 说的是**左端点属于哪个时区**，本次修的是**右端点**，两回事。要结案得单独交叉核对，且措辞必须写清结的是右端点。
3. **与 Java 侧留下 1 秒的语义差。** Java 是 `次日 00:00:00`（86400），脚本改后是 `当日 23:59:59`（86399）。两者都覆盖 end 当天，不构成 bug，但若以后拿脚本产出和 Connector 产出做逐单比对，这一秒是个已知解释项。**不要为了消差去改 Java** ——Java 那版有注释和测试守着，改它是净风险。

**配套的那份 unittest（`scripts/test_jufubao_fetch_orders.py`，4 例）判定为不随行移植**，理由见 §3.5。

---

## 3. 明确不移植的（按失效原因归类）

这一节和移植清单同等重要。**以后再有人问「旧线那个 XX 要不要捡回来」，先查这里。**

### 3.1 依赖未经平台验证的外部写接口 —— 飞象回传整簇（7 项）

涉及：`FeixiangHttpShipmentGateway.java`（334 行）、`FeixiangCredentialProvider.java`、`FeixiangConnector.pushShipmentResult`、`V34__feixiang_carrier_api_codes.sql`、`application.yml` 的 `app.feixiang` 块、`scripts/feixiang_push_shipment.py`（420 行）、5 个配套测试、`feixiang-push-integration-plan.md`、`wayfinder` 6 张票。

共同的否决理由（详见 §4 专项判定）：

- 分支自己的票否认了「已确认」：`feixiang-push-precheck-research.md`（status **open**）原文「核心 POST 的 `status:0` 失败文案库、以及预检 `data.code=1/2` 的真实响应，**都没有采样**」；`feixiang-push-smoke-enable.md`（status **open**）「本地绿灯不等于平台验收」，真实端到端 smoke **从未执行**。
- 所有测试都是本地 `com.sun.net.httpserver` 假服务器自答自问，断言的是「客户端发出的报文等于文档转录的那串」。**今天聚福宝 23 次登录全败的根因正是这一类失败对桩测试完全隐形**——Java 请求形状（缺 header、UA 机器人指纹、h2 vs 1.1）与能跑通的参考实现不一致。
- 那个 gateway 的三个请求**一个 `User-Agent` 都不设**（JDK 默认 `Java-http-client/21`），登录成功判据只有「最终路径不以 `/welcome/index/` 开头」。今线 `FeixiangPullClient` 是显式 `USER_AGENT` + 2xx + `LOGIN_SUCCESS_PATH` 精确相等 + `hasSessionCookie()` 三者同时成立。**这是今天刚修掉的那个坑的原样复刻。**
- 落点根本不通：今线 `SourceSyncFactsReader.java:56-59` 是硬渠道白名单，`header.channel() != JUFUBAO && != CAISHIXIAN` 直接 `SOURCE_SYNC_CHANNEL_UNSUPPORTED`；`FeixiangConnector.capabilities()` 是 `(true,true,true,false,false)`；`connector/feixiang/` 下**没有任何 shipment gateway**（只有 Connector / OrderDetail / OrderListParser / OrderTransform / PullClient 五个只读类）。gateway 搬过去是坐在几道关不上的门后面。
- 生产配置直接卡死：`app.connector_configs` 的 FEIXIANG 行 `carrier_mappings = {"JD": "京东物流"}`，`carrier_api_codes` 键**不存在**（今线全仓 grep 零命中）；而 gateway 构造期强校验 `expressCode.matches("[0-9A-Za-z]+")`，「京东物流」必被拒。

**唯一有条件保留的**：`docs/research/feixiang-supplier-api.md` §7–§8 的写接口契约与 45 项 `express_code` 码表（含 `jingdong` 这个值），可作为**线索存档**。但若存档，标题必须改成「**转录，原始 HAR 缺失，未独立复核**」并保留 §9.1 缺口表——因为分支引用的那份 `ziyousupplier.wowcarp.com_2026_08_28_16_32_42.har` 在磁盘上找不到，`data-local/` 里连 8/18 那份也不在。**建议做法：不存档，等真实 smoke 拿到证据后在今线重新写。** 一份「已确认」措辞加上无人能复核的转录，比没有更危险。

### 3.2 今线已有等价或更强实现（SUPERSEDED，8 项）

| 旧线产物 | 今线对应物 |
|---|---|
| `fulfillment/SourceShipmentSyncService.java`（796 行行级用例） | `connector/sync/` 整包 22 个文件：`SourceShipmentSyncService`(549) / `SourceSyncStore` / `SourceSyncPolicy` / `SourceSyncFactsReader` / `SourceSyncRecoveryWorker` / `SourceSyncAutoWorker` / `SourceShipmentSyncController`，check→execute→reconcile 全链路，两个渠道已跑通 |
| `V33__source_shipment_syncs.sql`（新建行级表） | `V54__shipment_source_sync.sql` 已把等价字段加到既有 `app.shipment_syncs`：`intent_key` / `platform_intent_key` / `check_hash` / `artifact_hash` / `effect_started_at` / `lock_version` + 五态 CHECK |
| `V33__platform_orders_without_customer.sql`（放宽客户 CHECK） | 今线用「恒定给平台订单一个客户」解决同一问题；生产上所有渠道所有订单 `customer_id` 全部非空，从未被这条 CHECK 卡住 |
| `JufubaoConnector` 8 行空壳 → 308 行在线拉单 | 今线 `JufubaoConnector` 已 600+ 行，`extends AbstractHttpPullConnector`，capabilities `(true,true,true,true,false)`（多一项写回），另有 `checkShipmentResult` / `sub-order-send` / 承运商字典实时映射 / effectHash / 对账语义 |
| `PlatformOrderRefreshService.refreshJufubao()` 渠道特判 | 今线 `CHANNEL_SCRIPTS` 里 JUFUBAO **早已摘掉**（实测只剩 CAISHIXIAN / FEIXIANG），改成通用 Connector 索引 + `connector_configs` 门禁 + `PlatformPullSingleFlight` 会话锁 |
| `JufubaoOrderSkippedException`（抛异常丢单） | 今线 `StructuredOrderRow.reviewRequired(...)` 把订单以 NEED_REVIEW 形态**留在批次里保住血缘**，三个跳过码：`JUFUBAO_RECEIVER_REQUIRED` / `_QUANTITY_INVALID` / `_CREATED_TIME_REQUIRED` |
| `ExcelClosedLoopApiTest` +3 断言 | 今线同文件已有更强版本；且旧线断言 `template_version=v1` 在今线是 `v2-gb18030-lf`，照搬会直接把测试搞红 |
| `types.ts` 加 `imported_count` / `skipped_count` | 今线契约是 `batch_no` / `batch_id` / `row_counts`，加两个平行字段只会造出前端读得到、后端从不返回的 `undefined` |

### 3.3 前提已被今线否决，方向相反（客户线整簇，7 项）

旧线做的是「平台订单不要客户实体」这条重构，根节点是 `CanonicalOrderInput.customer` 去掉 `@NotNull`，末端是删掉 `ImportedCustomerService`（111 行）和一个 `cleanup_source_order_import_customers.sh` 清库脚本。

**今线走的是完全相反的方向**：`ImportedCustomerService` 活着并在加固（`findBySourceRef` + `findByIdentity` 双路命中、重复身份抛错、`pg_advisory_xact_lock` 并发保护）；伴生的 `ImportedCustomerIdentity` 被三个在线 transform 引用（`CaishixianOrderTransform:105` / `FeixiangOrderTransform:130` / `JufubaoOrderTransform:103`），还长出了 `legacyFrom()` / `lookupCandidates()` 的升级期回查（处理 `+86` 旧身份别名）；`CaishixianOrderTransform.java:53` 的注释写着「改用收货人姓名+电话二元组（与聚福宝结构化拉取同规）」——今线是把彩食鲜也**迁进**这套模型。

**清库脚本尤其危险，不要碰**：生产上 `identity_source='SOURCE_ORDER_IMPORT'` 的客户 27 条、`CONTACT-%` 的 `customer_source_refs` 27 条，**正挂着 29 单订单**（FULFILLING 7 / SHIPPED 21 / SKU_MAPPED 1）。生产的 `orders_check1` 是 `CHECK (customer_id IS NOT NULL OR order_status = ANY('RECEIVED','NEED_REVIEW','CANCELLED'))`，脚本第一步 UPDATE 就会被 CHECK 拒掉；若先跑了 V33 放宽约束，结果就是把 28 单已发货/履约中订单的客户归属抹掉，且脚本自己用 ⚠⚠⚠ 标注的 `channel_identities.customer_id` 置空会让企微渠道身份不再带出客户候选——而企微正是这次裁决里唯一被刻意保留客户线的渠道。**数据不可逆。**

配套要丢的还有：`CONTEXT.md` 删客户词条（词条与实现一一对应，删词条不删实现会让统一语言脱节）、`frontend/orderCustomerVisibility.ts` 按渠道隐藏客户列（今线所有渠道 `customer_id` 非空，隐藏的是有内容的字段；旧线测试还把渠道全集硬编码成 4 个，今线 `SourceChannel` 有 8 个）、以及 `ExcelClosedLoopApiTest` 的客户契约整体反转。

**其中 2 项标为 NEEDS_USER_DECISION**（`CanonicalOrderInput` 去 `@NotNull`、`OrderCreateService` 客户解析 null-safe）：技术上都是小改动，但它们只服务于上面那条产品裁决，而**旧线里找不到那份裁决的出处**。这不是技术取舍，需要用户重新拍板，不属于移植范畴。

### 3.4 会打破今线现有行为（4 项）

- **`OrderCreateService` 行快照三级回退**（内部 SKU 主数据 → 来源输入 → 占位符）：今线 `baseLine()` 刻意直接用来源值，`:918-921` 把它原样投影成复核证据 `source_specification` / `source_unit`，注释「来源原始商品信息：行快照即来源文件/结构化载荷的规范化值」。让内部主数据优先会破坏血缘语义并污染纠正 diff。旧线想解决的聚福宝缺规格/单位，今线已在 transform 层诚实解决（`SPEC_MISSING="—"` / `UNIT_DEFAULT="件"`）。
- **聚福宝 `no_delivery` 拉取去掉 `created_time_range`**：方向反了——旧线自己 28/28 个分支都传这个参数，旧线设计文档还明文规定要传。今线 `ShippingWorkbenchPage.tsx:131-134` 把同步窗口硬收窄到「今天」，代码注释里有生产实测「30 天 >256s 网关超时，1 天 9s」，三渠道还在同一个同步请求里串行。去掉窗口后最坏是 100 次列表 + ~2000 次详情（`pullOrders` 对每单再调一次 `shipmentDetail`），且 `JufubaoHttpPullClient` 无任何节流。收益则零观测：生产 `connector_configs` 的 JUFUBAO `last_pull_at` 为空，JSON 拉单一次都没跑通过。
- **聚福宝收货人改用批量 `multi-send-form`**：今线让 `sub-order-info` 同时做拉单端和发货端的权威读取源，`JufubaoConnector:359/:391` 有两道 `JUFUBAO_RECEIVER_MISMATCH` 硬闸做精确串比。而 `multi-send-form` 的字段名不同（`receipt_username` / `address_detail` vs `receipt_user_name` / `location`），磁盘上**没有任何一处把两者的实际取值并排比对过**。只要有差异，每一单聚福宝都会在两道闸上硬失败、永不提交物流。而且旧线自己的测试桩就证伪了「传 N 个 id → 返回 N 条」：传 5 个 id 只返回 3 个 package，SUB-3 完全缺席。
- **`ReviewCaseResolutionService` 加 `SOURCE_SHIPMENT_SYNC_FAILED`**：今线 `SourceSyncStore` 三个写入点的 `reason_code` 一律是 `SOURCE_SYNC_BLOCKED`，这个常量永远匹配不到任何行。

### 3.5 源根本不存在 / 测试类同名冲突（7 项）

**这一类是本次评估最值得记住的教训**（详见 §6）。

- `OrderQueryService` 加 `o.receiver_name ILIKE ?`：全仓 `git log --all -S"receiver_name ILIKE"` 零命中，两线该行**逐字相同**。它只存在于那个脏工作区，且自带的注释写着「平台来源订单不再维护客户实体，customer_name 恒为空」——这个前提在今线不成立（生产 32 单里 `customer_id IS NULL` 为 0，`customer_name='待匹配客户'` 为 0，`customer_name` 不含 `receiver_name` 只有 3 单且全是 WECOM）。而且今线**已有这个能力**：`OrderSearchReadService.java:45` 就是 `AND (o.source_ref ILIKE ? OR o.receiver_name ILIKE ?)`，其 javadoc 把两个服务的检索面分工写成了契约。
- `WecomEndToEndAcceptanceTest` 改 `orderBySourceRef`：`orderBySourceRef` 在整个仓库 394 个 commit 里从不存在，两线该文件 `diff` 为 0。而且改了反而会**删掉今线唯一一条端到端覆盖「收货人 → 客户自动建档」的断言**。
- `SourceImportService` 结构化行明文投影：枚举该文件全历史 21 个不同 blob，`rowCells` / `projectionFor` **逐字相同**，`_parsed` / `canonicalProjection` 全仓零命中。这是净新设计，不是移植。（顺带：今线确有一个真实 UX 缺口——结构化行的 `parsed` 会是空 Map；但生产 `sheet_name='STRUCTURED'` 目前 0 行，值得单开票、等有真实样本再设计。）
- `StructuredImportApiTest` 的「谢先生 → ***、parsed 明文七字段」用例：`git log --all -S"谢先生"` 零命中，「谢先生」全仓唯一出处是一份研究文档里的样例串。今线该文件已有更强的同类断言（`raw_cells` 含 `测试收***` / `138***` 且 `doesNotContain` 明文）。
- `docs/research/golden/jufubao-logistics-company-options.json`（115 条字典快照）：全部 66 个 ref 零命中，是**今天 15:43 生成的未跟踪文件**，只是那个 worktree 恰好停在旧线 commit 上。今线文档 `:146` 明写「每次执行按 `logistics-company/options` 实时确定性映射，**禁止硬编码**」；配套的文档 diff 恰恰是把这条常开护栏用一份冻结快照划掉改成「✅ 已闭合」。
- `JufubaoConnectorTest.java` / `PlatformOrderRefreshServiceTest.java` + `RecordingSourceImportService.java`：与今线现有文件**同名**，覆盖会删掉今线现有测试。且 `RecordingSourceImportService` 硬编码 `super(null×8, "/tmp")` 共 9 参，今线 `SourceImportService` 包内构造器是 **12 参**，直接编译不过；旧 `StubClient` 还把商品字段放在子单顶层，而今线与文档都以 `product_list[]` 数组为准——那是把一个错误的响应形状固化成断言。
- `ConnectorApiTest` 期望条数 4 → 5：今线该测试早已改形，没有任何条数断言；今线 `SourceChannel` 是 8 个枚举值，数字 5 无论如何都不对。

### 3.6 `scripts/test_jufubao_fetch_orders.py`（4 例 unittest）——不随 P1 移植

理由三条，任一条都够：

1. **它在整个仓库里也从没被提交过**，同样只是脏工作区里的 `??` 文件。
2. **今线没有任何东西会跑 `scripts/test_*.py`。** `.github/workflows/ci.yml` 只有 backend `mvn test` 与 frontend `npm run typecheck/test/build` 两个 job，全文无 python 步骤；`scripts/acceptance.sh` 也没有 `unittest|pytest`。现存的两个 py 测试（`test_acceptance_compose.py` / `test_acceptance_credentials.py`）都是手工跑过一次留了记录。落地即死文件，「防止再犯」这个唯一卖点落空。
3. **口径冲突。** 它硬断言 `end_time - start_time == 86399`，而今线生产 Java 用的是 86400。移植进去等于在同一个仓库里立两套互相矛盾的窗口右端点约定，且被「测试认证」的那一套恰好**不是**生产在用的那套。将来有人照测试去对齐 Java，反而会把生产窗口收窄 1 秒并引入不一致。

如果确实想要回归保护，**该补的是 Java 侧**：今线 `JufubaoPullConnectorTest` 全部用 `pullOrders(anyLong(), anyLong())`，没有一处断言窗口宽度。那是另一张票。

---

## 4. 飞象发货回传专项判定：**不翻案**

**问题**：今天重写飞象拉取时刻意排除 `POST /order/ajaxSendOrderProduct`，理由是 HAR 分析自己声明「本次没有点确定，没有抓到成功写入和回查结果」。`feixiang-shipment-har-analysis` 分支里有一个 `FeixiangHttpShipmentGateway` 实现了它——那个实现是否包含当时缺失的验证证据？

**答案：不包含。原决策成立，维持排除。**

**依据（按强度排序）**

1. **分支自己的票就是否认。** `wayfinder/tickets/feixiang-push-precheck-research.md`（status **open**）第 17 行原文：「核心 POST 的 `status:0` 失败文案库、以及预检 `data.code=1/2` 的真实响应，**都没有采样**」。而文档 §7.1 恰恰把 `data.code=1`=未填默认退货地址 / `code=2`=存在维权订单 列成表格，挂在「## 7. 发货回写链路（2026-08-28 **抓包确认**）」标题下——同一分支内自相矛盾，那张表只能是读页面 JS 推的。
2. **一次真实平台写都没发生过。** `feixiang-push-gateway.md` 的 Resolution 明写三层闸门全关着：`ConnectorCapabilities.onlinePush` **未翻**、`app.feixiang.shipment-write-enabled` 默认 false、dry-run 早返回。`feixiang-push-smoke-enable.md` 至今 `status: open`，验收条自己写着「**HTTP `status:1` 本身不单独作为验收证据**」「真实 `--confirm` 属于对外不可逆动作，必须由用户明确点头，Agent 不得自行执行」。
3. **测试是自证循环。** `FeixiangHttpShipmentGatewayTest` 全部用本地 `com.sun.net.httpserver` 假服务器，`status:1` 是测试自己回的，断言的是「客户端发出的报文等于文档转录的那串」。**今天聚福宝四天 23 次登录全败，根因正是这类桩测试完全看不见的失败**——请求形状（UA 机器人指纹、缺 header、h2 vs 1.1）与能跑通的参考实现不一致。而这个 gateway 的三个请求一个 UA 都不设，登录判据比今线弱得多。
4. **文档自身的算术不支持它最关键的那句证据。** §7 说 HAR 79 条请求，§154 说「79 条里业务请求只有 3 条，其余 76 条是静态资源」，同一段却断言「页面 reload 后该行由待发货变为已发货」。但 §7 列了 4 个业务端点，再加一次 reload 的列表页至少要 5 条业务请求——3 条装不下。这句**不可能来自那份 HAR**。而那份 HAR 在磁盘上也找不到（`data-local/` 与 `~/Desktop` 均无命中）。
5. **同一份抓包文档已有一节被今天证伪。** `feixiang-supplier-api.md` 自认导出参数语义「未验证」，而今天的 JSON 直连重写已经证明那一节是错的（真参数名是 `start_create_time` / `end_create_time`）。同样成色的另一节不该写进生产。
6. **同类风险面全空白**：无平台幂等键、重复 POST 是否覆盖单号未验证、失败消息库未采样、一单多品数组提交从未真实执行、45 个承运商码只验过 `jingdong` 一个。

**唯一被这次复核修正的事实**：分支 §7 确实转录了一段**看起来像**成功写入的报文（`order_product_ids%5B%5D=43231540&sn=JDVA46783539436&express_code=jingdong&delivery_remark=`）。所以「线材契约完全没证据」这个说法不准确——**有一段转录**。但（a）原始 HAR 缺失，无人能复核；（b）即使为真，那也是平台自己的 Web UI 发的写，不是我们的 Java 客户端发的；（c）分支自己承认只抓到 happy path。按「不能凭一份没跑通的抓包就写进生产」这条线，**转录不等于验证，判定不变**。

**下一步真要做飞象回传，正确的起点是**：先拿到用户明确授权、做一次真实单条 smoke，再在今线的形态下重写——`PlatformConnector.pushShipmentResult(result, ExternalWritePermit)` + `checkShipmentResult` + `reconcileShipmentResult` + `releaseShipmentIntent` 四件套，参照 `JufubaoConnector:164-273`；同时要改 `SourceSyncFactsReader` 的渠道白名单、`SourceSyncPolicy.writableState()`、以及 `carrier_api_codes` 的真源问题。**不是移植，是新写。**

**另有一条翻 `onlinePush` 的具体后果，谁做这张票都必须先知道**：今线 `SourceReturnWecomScanner` 只对 `onlinePush=false` 的渠道投递来源回填企微卡（`SourceReturnWecomDeliveryService.java:31` 注释：「飞象、大者、中汇的 `onlinePush=false`，文件生成后无处可去」），而 `SourceSyncAutoWorker.runtimeCapability()`（`:181-190`）把 `onlinePush()` 当作**无人值守自动回传的唯一闸门**。所以在 API 打通之前先翻这一位，等于**既不发企微卡、也不发 API**，同时把飞象放进自动外呼的租约轮询——而飞象在生产 `import_batches` 里是最大渠道。**必须先打通并验证真实写路径，再翻能力位。**

---

## 5. 移植的机械障碍清单

即使某项将来被重新认定值得移植，下面这些障碍每一条都会绊人。

### 5.1 目录层级：`jry/` 前缀

旧线代码在 `jry/backend/src/main/java/...`，今线在 `backend/src/main/java/...`。所有 diff、所有票里的路径引用、所有 `git show <ref>:<path>` 都要去掉这一层。**副作用**：任何 `git apply` / `patch -p1` 都对不上，只能手工重打。

### 5.2 迁移号重排（实测数据，2026-08-28）

| 来源 | 最大号 |
|---|---|
| 生产 `public.flyway_schema_history`（只读查询实测） | **77** |
| `/Users/jerry/zimu-work/integration` 目录 | 77 |
| `/Users/jerry/zimu-work/main` 目录 | **80**（有 V78、V80；V79 缺号） |
| 全部 15 个今线 worktree 的并集 | **80** |

- **注意 schema**：`flyway_schema_history` 在 **`public`** 而不是 `app`。写 `app.flyway_schema_history` 会直接报 `relation does not exist`。
- **新号下限是 V81**，V81/V82 目前未被占用。约定的起点 **V83** 留了两号余量，是安全的——**但落笔前必须再核一次当时的最大号**，因为其它 worktree 随时会占号。
- 旧线的迁移号全部作废：`V33` / `V34` / `V35` 在今线分别被 `platform_orders_without_customer` 撞上 `agent_platform_definitions`、`feixiang_carrier_api_codes` 撞上 `source_return_push_status`、`zhonghui_pms_upload_batches`。**旧线票里所有带迁移号的指令都不能照抄。**

### 5.3 今线仍在用、而旧线删掉的文件

最主要的一个是 **`ImportedCustomerService.java`**（`backend/src/main/java/cn/zimu/fulfillment/customer/`，今线实测存在）。旧线把它整文件删除（`git status` 里是 `D`）。今线依赖点远多于旧线 diff 能覆盖的范围：

- `SourceImportService.java:435`（结构化路径）与 `:796`（Excel 路径）两处 `importedCustomers.resolve`
- 伴生类 `ImportedCustomerIdentity` 被 `CaishixianOrderTransform:105` / `FeixiangOrderTransform:130` / `JufubaoOrderTransform:103` 三个在线 transform 引用
- 测试：`ExcelClosedLoopApiTest:616`、`StructuredImportApiTest:266-268`、`CaishixianOrderTransformTest:118`、`ImportedCustomerIdentityTest`

**任何触碰客户线的旧线 diff 都会连带要求删它，进而全线编译失败。**

### 5.4 同名 bean / 同名测试类冲突

- 旧线 `cn.zimu.fulfillment.fulfillment.SourceShipmentSyncService` 与今线 `cn.zimu.fulfillment.connector.sync.SourceShipmentSyncService` **同名不同包，都标 `@Service`**，默认 bean 名都是 `sourceShipmentSyncService` → 启动即 `ConflictingBeanDefinitionException`。而且两者写的是不同的表（`app.shipment_syncs` vs `app.source_shipment_syncs`），共存会出现两个用例对同一 Shipment 各自建意图、各自认为持有围栏——正是重复 POST 覆盖运单号的场景。
- 测试类 `JufubaoConnectorTest.java`、`PlatformOrderRefreshServiceTest.java` 与今线同名，直接覆盖会删掉今线现有测试。

### 5.5 基类与构造签名已变

- `FeixiangConnector`：旧线 `extends ExcelPlatformConnector`，今线 `extends AbstractHttpPullConnector`（8/28 JSON 直连重写后的形态）。不能直接贴代码。
- `SourceImportService` 包内构造器：旧线 9 参，今线 **12 参**（`parser, orderCreateService, jdbc, objectMapper, auditLogService, providerFileService, importedCustomers, shipmentJdOutboundService, jdCargoProjectionService, idempotency, confirmReadiness, fileRoot`）。
- `PlatformConnector.pushShipmentResult`：今线是双签名，主路径带 `ExternalWritePermit`（注释「Adapter 若包含多个不可逆写，必须在每一次前重新调用 permit」），旧线是 `Runnable externalEffectStarted`。

### 5.6 「旧线」大部分根本不是分支，是脏工作区

这是最容易被忽略、也最容易白干的一条：

- `claude/jufubao-pull-order-issue-641416` 相对 `b65ec8cd` 的**独有提交数 = 0**，它只是指向旧线快照的一个指针。全部 35 项改动（23 `M` + 12 `??`）都是**未提交的工作区状态**。
- `claude/feixiang-shipment-har-analysis-f870f7` 同理，HEAD 仍指向 `b65ec8cd`；`FeixiangHttpShipmentGateway.java`、`FeixiangCredentialProvider.java`、`V33/V34/V35`、6 张 wayfinder 票全是 `??` 未跟踪文件。

后果：

1. **无法 cherry-pick、无法按分支名找到**，只能按绝对路径手工复制。
2. 一条 `git checkout .` 或 `git clean -fd` 就永久没了。
3. 这些内容**从未进入任何提交，也就从未经过该分支自己的评审**。
4. 引用它们时不要说「旧分支里有」——后续执行者会去 `git log` 里找，找不到会以为主张造假。

### 5.7 承运商代码没有真源（若将来做飞象/任何 API 回传都会撞上）

生产 `app.connector_configs` 只读实测：FEIXIANG 的 `carrier_mappings = {"JD": "京东物流"}`，**`carrier_api_codes` 键不存在**；今线全仓 grep `carrier_api_codes` 零命中。而各渠道该键的语义本就不统一（CAISHIXIAN `{"JD":"JD"}` 是平台 option code，ZHONGHUI `{"JD":"京东快递"}`，其余「京东物流」）。今线判定「平台会不会认这个承运商」用的**不是静态映射表，而是活字典**（`CaishixianConnector:371-379` 拿 `shipmentGateway.carrierOptions()` 逐项比对，不中即 `SOURCE_PLATFORM_CARRIER_UNMAPPED`）。**新增一张静态 jsonb 表是与今线机制相悖的方向**，别照旧线的 `V34` 做。

---

## 6. 方法论观察：为什么推翻率是 16/17

值得单独记一笔，因为这不是随机误差。

**16 条被推翻的初判里，至少 7 条的失效原因是「主张描述的旧线改动在任何 commit 里都不存在」**：

| 主张 | 实际情况 |
|---|---|
| `OrderQueryService` 加 `receiver_name` 检索 | 全仓 `-S` 零命中，两线该行逐字相同 |
| `WecomEndToEndAcceptanceTest` 改 `orderBySourceRef` | 394 个 commit 零命中，两线该文件 diff = 0 |
| `SourceImportService` 明文投影 | 该文件全历史 21 个 blob 的相关方法逐字相同 |
| `StructuredImportApiTest` 打码/明文用例 | 「谢先生」全仓唯一出处是一份研究文档的样例串 |
| 聚福宝 `no_delivery` 去掉时间窗 | 旧线 28/28 分支都传该参数，旧线设计文档明文要求传——方向反了 |
| `jufubao-logistics-company-options.json` | 全部 66 ref 零命中，是**今天生成的未跟踪文件** |
| `multi-send-form` 批量实现 | `git log --all -S"multi-send-form" -- '*.java'` 返回空，从未提交 |

**共同成因**：第一轮分析在读今线代码时产生了合理的改进想法，然后把它归因给了旧线。这类主张读起来非常可信——它对今线的事实描述往往是**准确的**（`OrderQueryService:140` 确实不含 `receiver_name`、脚本确实有右端点 bug），错的只是「这是旧线的产物、所以是移植」这个归因。

**给后续的操作建议**：对任何「旧线有 X」的主张，第一步永远是
`git log --all -S"<关键字符串>" -- <路径>` 和 `git ls-tree` 逐 ref 枚举，
**而不是**读那个 worktree 的磁盘文件——因为磁盘上的脏工作区可能是今天刚写的，与它 checkout 的那个 commit 毫无关系。

---

## 7. 未验证项（如实列出）

1. **两份关键 HAR 文件都找不到。** 分支 §7 引用的 `ziyousupplier.wowcarp.com_2026_08_28_16_32_42.har`（自称 426 KB / 79 请求）与文档按 Desktop 路径引用的 8/18 那份，在 `data-local/`、`~/Desktop`、`~/Downloads` 均无命中，`mdfind` 无结果。因此**飞象写接口的报文转录无法独立复核**——我只能读到文档里的散文叙述。这也是 §4 判定不翻案的直接原因之一。
2. **未复跑任何测试或编译。** 本次调查全程只读源码。旧线那些测试是否能编译、能通过，我没有验证。特别是 `feixiang-shipment-har-analysis` 工作区曾一度处于「测试引用了源码里还不存在的构造」的不可编译中间态；票里记录的例数（4+4+2+8+1+15）与另一处说的「22 例通过」对不上，也未核实。
3. **`multi-send-form` 与 `sub-order-info` 的字段取值是否等价，无人验证过。** 前者是 `receipt_username` / `address_detail`，后者是 `receipt_user_name` / `location`。磁盘上没有任何一处把两者在同一单上的实际取值并排比对过，也没有任何真实响应样例（`docs/research/golden/` 下无该文件，无 HAR）。这是「聚福宝批量取收货人」那条被否的核心不确定性——**否得对，但否的依据是「未证明等价」而不是「已证明不等价」**。
4. **飞象 `ajaxCheckSend` 的 `data.code=1/2` 语义未采样。** 文档里那张表是从页面 JS 推的，不是抓包来的（分支自己的票承认）。
5. **`multi-send-form` 的 `sub_order_id_list_str` 是否有长度上限，未知。** 旧线只有一次 3 单的散文式实测记录，无产物。
6. **生产端本次只做了两条只读查询**（`flyway_schema_history` 最大版本 = 77；以及一次 `connector_configs` 结构确认）。报告中其余生产数据（订单计数、`import_batches` 分布、`connector_configs` 各渠道 `carrier_mappings` 取值、`shipment_syncs` 行数等）**引自本任务上游的复核结论，我未在本次会话中逐条重跑**。若要据此做不可逆决策，建议重查。
7. **未读取或打印任何凭据值。** 涉及凭据的判断（如「FEIXIANG 行已同时具备 `username` 与 `password_encrypted` 两个键」）来自上游复核的键名存在性检查，本次未复核。
8. **今线结构化行的 `parsed` 空投影缺口**：静态阅读认为 `ProviderFileService.sourceProjection` 对结构化行的嵌套 `raw_cells` 会抛 `MismatchedInputException` → `IllegalStateException("来源行快照无法解析")`。**未执行验证**——生产里 `sheet_name='STRUCTURED'` 目前 0 行，无法用真实数据复现。这是今线自己的待办，不是移植项。

---

## 8. 一句话给下一个人

除了那 10 行 Python，旧线没有值得从坟里挖出来的东西；如果有人拿着某条「旧线有个更好的 X」来找你，先用 `git log --all -S` 验证 X 是否真的存在于某次提交里——这次 16 条被推翻的主张里，有 7 条的 X 从来就不存在。
