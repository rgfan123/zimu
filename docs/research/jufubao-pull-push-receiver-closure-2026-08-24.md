# 聚福宝拉单、收货人、接单与发货闭环核查

日期：2026-08-24  
范围：只读研究；没有登录真实账号，没有调用接单或发货接口，没有读取或复制本地 HAR 中的敏感载荷。

证据基线：pull 固定快照 `74e4a31891f3f284ca4121193e375fcec9706639`；push 固定提交 `c529fe49fee1f5eae83961951ff168c886989cd0`。核查时当前 root 的 `JufubaoConnector`、`JufubaoPullClient`、`JufubaoOrderTransform`、pull 测试及拉取脚本 blob 均与 `74e4a318` 完全一致；下文涉及 pull 行为时仍以该不可变提交为引用。

## 结论

1. **在线拉单 WIP 与 `c529fe49` 单订单发货实现都不能删除，但也不能整文件二选一。** 两边都定义了同一路径、同一类名 `backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoConnector.java`：pull 快照声明 `onlinePull=true, onlinePush=false`，`c529fe49` 则声明 `onlinePull=false, onlinePush=true`。直接覆盖任一版本都会回归另一半能力。`74e4a318:backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoConnector.java:56-60`；`c529fe49:backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoConnector.java:42-44`。
2. **`orders/query` 已证实能提供订单、状态和商品，但没有证实能提供可用收货人。** 仓库脱敏 golden 只保留订单/商品/状态结构，没有姓名、电话、地址；pull transform 因而制造空 `Receiver` 并写 `receiver_missing=true`。这只能进入“缺字段待复核”，不能作为可履约收货人事实。[golden](golden/jufubao-order-golden.json)；`74e4a318:backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoOrderTransform.java:89-106,210-223`。
3. **`sub-order-info` 的当前可审计证据只来自实现和受控 HTTP 测试，测试契约覆盖发货商品与 `allow_send_num`；`multi-send-form` 仅发现了路由，尚无可信响应结构。** 旧研究记录称 `multi-send` 请求出现过收货字段，但本轮未读取原始 HAR，因此只能视为待复核的历史捕获声明，不能证明这些值来自哪个权威读取接口。当前没有已证实的 receiver 读取路径，不能宣称“收货人已补全”。`c529fe49:JufubaoHttpShipmentGateway.java:121-137`、`c529fe49:JufubaoConnectorHttpContractTest.java:173-177`；[供应商前端一方 bundle](https://g.jufubao.cn/1787537534020/static/js/app.js?versions=1787537534020)。
4. **`NO_DELIVERY → sub-order-send → 写后重查` 有受控代码实现；仓库研究记录称其来自一方抓包，但本轮没有重新读取原始 HAR。** HTTP 200/受理不是最终成功；代码只有在写后查询确认目标单离开 `no_delivery` 时才返回成功。`NO_RECEIPT` 接单、地址确认及完整内部业务门禁仍未实现。`c529fe49:JufubaoConnector.java:144-212`、`c529fe49:JufubaoHttpShipmentGateway.java:90-196`。
5. **交付状态仍未闭合。** 截至本次核查，GitHub [#99](https://github.com/rgfan123/zimu/issues/99)、[#100](https://github.com/rgfan123/zimu/issues/100)、[#101](https://github.com/rgfan123/zimu/issues/101)、[#102](https://github.com/rgfan123/zimu/issues/102)、[#113](https://github.com/rgfan123/zimu/issues/113) 均为 OPEN；`c529fe49` 只在本地 `codex/jufubao-shipment-p0-99`，不在 `master`、`origin/master`、`origin/main` 或 `integrate-20260824`，GitHub Commit API 也查不到该 SHA。没有 PR、合入、部署或真实账号终态验收证据；#102 明确仍是外部门禁。

## 证据等级

| 标签 | 本报告含义 |
|---|---|
| `captured-and-verified` | 仓库保存了可直接审阅的脱敏一方响应结构（本报告只有 `orders/query` golden 达到该等级）；不是公开 OpenAPI。原始 HAR 因含凭据和 PII，本次没有打开。 |
| `discovered-but-unverified` | 一方前端或抓包记录发现了路由，但关键请求/响应字段或状态转换仍缺脱敏样例。 |
| `code-only` | 仓库有实现和受控 stub/单元测试，但没有本次真实平台验收。 |
| `absent` | 当前候选代码中没有该能力。 |
| `real-account-unverified` | 可能曾有人工操作记录，但没有可用于本次确认的脱敏真实终态证据，不能作为上线验收。 |

聚福宝没有可检索到的公开 supplier OpenAPI 文档。本报告把官方供应商前端及其静态 bundle 视为一方产品证据；仓库抓包研究记录只作为定位线索，除非同时有可直接审阅的脱敏结构，否则不标为 `captured-and-verified`。这些材料均不能包装成稳定、受支持的公开 API。

## 一方接口与状态语义

| 接口 | 方法与关键字段 | 证据等级 | 已能确认的语义 / 不得推断的部分 |
|---|---|---|---|
| `https://g.jufubao.cn/` | `GET` | code-only / 一方页面可达 | pull 实现把它用作供应商后台会话初始化，随后登录；本轮没有抓包级复验。`74e4a318:backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoPullClient.java:122-149` |
| `/idaas-auth/v1/login-by-username` | `POST application/x-www-form-urlencoded`；代码发送 `username`、`password`、`system=supplier`，并检查 `access_token_cookie_key`；业务调用构造会话/访问 Cookie、CSRF 头和 `X-Jfb-Project-Id: supplier`。 | code-only / captured record unverified this pass | Cookie 名和头结构是协议元数据，不是可复用凭据；任何 Cookie/Token 值均不得落日志或文档。`74e4a318:backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoPullClient.java:82-90,131-149,190-218` |
| `/order-supplier/v1/orders/query` | `POST JSON`；实现请求含 `tab`、`filter.created_time_range.start_time/end_time`、`page_token`、`page_size`、`system`；脱敏响应结构含订单、状态和商品，代码另处理 `list`、`next_page_token`。 | captured-and-verified（脱敏响应）/ code-only（请求实现） | `tab=no_delivery` 对应待发货；游标为空或列表为空结束分页。**未证实 receiver 字段。** `74e4a318:backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoPullClient.java:159-187`、[golden](golden/jufubao-order-golden.json) |
| `/order-supplier/v1/order/receive-order` | 一方 bundle 与二次抓包记录均发现 `POST` 路由；请求体、成功码、拒绝格式未形成脱敏契约。 | discovered-but-unverified | 计划语义是 `NO_RECEIPT → NO_DELIVERY`，但必须在补抓 payload/response 并写后重查后才能实现；不能仅凭路由名发请求。[官方 bundle](https://g.jufubao.cn/1787537534020/static/js/app.js?versions=1787537534020)、[#100](https://github.com/rgfan123/zimu/issues/100) |
| `/order-supplier/v1/sub-orders/{sub_order_id}/shipment-receipt-address-confirmation` | 一方 bundle 中存在单子单地址确认路由；研究记录标为 `GET`。 | discovered-but-unverified | 是否需要确认、地址变化和未知结果的字段/枚举均未固化；当前代码没有该门禁。[官方 bundle](https://g.jufubao.cn/1787537534020/static/js/app.js?versions=1787537534020)、[#100](https://github.com/rgfan123/zimu/issues/100) |
| `/order-supplier/v1/logistics/sub-order-info` | `GET`；查询含 `sub_order_id`、`system=supplier`；代码只消费 `product_list[]` 与每项 `allow_send_num`。 | code-only | 受控测试把它建模为发货商品/可发数量来源。当前证据不足以把它当 receiver 来源。`c529fe49:JufubaoHttpShipmentGateway.java:121-137`、`c529fe49:JufubaoConnectorHttpContractTest.java:173-177` |
| `/order-supplier/v1/logistics/multi-send-form` | 一方 bundle 和旧抓包记录发现 `GET` 路由。 | discovered-but-unverified | 没有脱敏响应样例；不得假设它一定返回完整 receiver，也不得据此实现自动履约。[官方 bundle](https://g.jufubao.cn/1787537534020/static/js/app.js?versions=1787537534020) |
| `/order-public/v1/logistics-company/options` | `GET`；受控测试响应建模为 `items[].label/value`。 | code-only | 发货实现用字典把内部承运商映射到 `company_id`，未命中失败关闭；不得硬编码一次测试的数值。`c529fe49:JufubaoHttpShipmentGateway.java:140-155`、`c529fe49:JufubaoConnectorHttpContractTest.java:179-181` |
| `/order-supplier/v1/logistics/sub-order-send` | `POST JSON`；实现发送 `sub_order_id`、字符串型 `product_list_json`、`is_need_logistics=Y`、`company_id`、`logistics_number`、`remarks`、`system=supplier`。 | code-only / captured record unverified this pass | `product_list_json` 是 JSON 序列化字符串；递归移除浏览器临时 `fd-*` 字段。明确拒绝、未知结果和受理必须分流。HTTP 200 不等于最终成功。`c529fe49:JufubaoHttpShipmentGateway.java:158-196`、`c529fe49:JufubaoConnector.java:180-207` |
| `/order-supplier/v1/logistics/multi-send` | `POST`，旧批量路径。 | discovered-but-unverified | 旧研究记录称请求出现过 receiver 字段，但本轮没有可审阅的脱敏请求证据；即使属实，它也是写入报文，不是 receiver 读取证据。 |

### Receiver 判定

- `orders/query`：**没有已证实 receiver**。
- `sub-order-info`：**当前代码与受控测试只建模商品和可发数量，没有可审阅的一方 receiver 证据**。
- `multi-send-form`：**只发现路由，响应契约缺失**。
- 历史 `multi-send`：旧研究记录声称浏览器提交 receiver 相关字段，但本轮没有可审阅的脱敏捕获；即使该记录属实，也**不能证明这些值来自何处或仍是当前权威地址**。
- 因此当前安全语义只能是：receiver 缺失或来源不明确时进入 `REVIEW_REQUIRED`/失败关闭；不得用空字符串继续自动确认、京东履约或来源平台回传。当前 transform 的空 Receiver 是需要修正的临时 WIP，而不是“功能补全”。

## Pull / Push 能力矩阵与冲突

| 能力 | 当前 root pull WIP | 本地 `c529fe49` push 分支 | 收敛后要求 |
|---|---|---|---|
| 文件导入/导出 | 保留 | 保留 | 保留为显式降级通道 |
| 在线拉单 | `code-only`；登录、`no_delivery` 分页、结构化导入 | 无；Connector 声明 `onlinePull=false` | 同一 Connector 声明并实现 `onlinePull=true` |
| Receiver | 空 Receiver + `receiver_missing=true` | 不读取 | 先取得可审阅的脱敏一方详情契约，再实现读取；缺失/不一致阻断并复核 |
| `NO_RECEIPT` 接单 | absent | absent | 人工授权后执行；写后必须重查到 `NO_DELIVERY` |
| 地址确认门禁 | absent | absent | 发货前读取最新状态；变化、缺失或未知均阻断 |
| `NO_DELIVERY` 单订单发货 | absent | `code-only` | 保留 `sub-order-info`、数量门禁、承运商实时映射和 `sub-order-send` |
| 写后验证 | absent | `code-only`；重新查 `no_delivery` | 只有终态证据才 `SYNCED`；受理仍是中间态 |
| 幂等/未知结果 | absent | `code-only`；共享表、effect-started、fencing、`RECONCILIATION_REQUIRED` | 保留并让在线/文件降级共享同一 Shipment 写门禁 |
| 真实账号验收 | real-account-unverified | real-account-unverified | 单独走 #102，不能由 mock 或 HTTP 200 代替 |

两个实现还重复了登录、CookieManager、CSRF 和业务请求构造：当前 pull 的 `JufubaoPullClient.Http` 与 push 的 `JufubaoHttpShipmentGateway` 各维护一套会话，并且 401 处理策略不同。合并时若只把两个类塞进同一 Connector，会产生双登录、状态漂移和刷新语义不一致，应先抽取共享会话适配器。

## 最小安全收敛方案

1. 从最终权威基线建立独立分支；**不要 cherry-pick 后直接接受 `JufubaoConnector.java` 的冲突一侧**。
2. 抽取一个共享 `JufubaoSessionAdapter`：负责 portal seed、登录、Cookie/CSRF、公共业务头、超时、一次 401 全量重登录；pull 与 push 只通过该适配器发请求，凭据仅从配置注入。
3. 将读取行为放入明确的外部 port：订单分页、按子单刷新状态、receiver/地址确认、发货详情、承运商字典。只有拿到脱敏真实响应后才固化 `NO_RECEIPT`、receiver 和地址确认 DTO。
4. 组合一个 Connector：文件能力保留，`onlinePull=true`、`onlinePush=true`；pull 与 push 是内部协作者，不再各自成为替代 Connector。
5. 拉单时缺 receiver 不创建“可自动履约”订单；保留原始行血缘并标记人工复核。不得把空 Receiver 当正常值，也不得从历史 `multi-send` 请求反推当前地址。
6. 执行入口必须以已确认的 Shipment 和正式运单为粒度，并再次读取平台最新事实。`NO_RECEIPT` 只有在人工授权、接单契约已抓实且接单后重查为 `NO_DELIVERY` 时才继续；其他状态失败关闭。
7. 保留 `c529fe49` 的可发数量一致、实时承运商映射、`fd-*` 清除、写前 `effect_started`、payload hash、fencing、成功/未知结果重放和写后查询。其幂等键当前为 `JUFUBAO + sub_order_id + tracking_no`，payload hash 还覆盖数量和承运商等外部效果字段。`c529fe49:JufubaoShipmentAttemptStore.java:16-41,81-105,131-156`。
8. `RECONCILIATION_REQUIRED` 必须单调锁住自动重试；平台明确拒绝或写前失败才允许在修正并重新确认后重试。文件降级与在线路径必须认领同一 Shipment 同步事实，避免双写。[#113](https://github.com/rgfan123/zimu/issues/113)

## GitHub 与交付边界（2026-08-24）

| 票 | 当前状态 | 结论 |
|---|---|---|
| [#99](https://github.com/rgfan123/zimu/issues/99) | OPEN | 本地有 `c529fe49` 的受控实现，但未推远端、未 PR、未合入。 |
| [#100](https://github.com/rgfan123/zimu/issues/100) | OPEN | `NO_RECEIPT` 接单与地址变化门禁未实现。 |
| [#101](https://github.com/rgfan123/zimu/issues/101) | OPEN | 人工确认、京东运单及统一内部 Tool/API 尚未接通。 |
| [#102](https://github.com/rgfan123/zimu/issues/102) | OPEN / external gate | 需要授权账号、可控订单、有效运单和人工批准窗口；本报告没有执行。 |
| [#113](https://github.com/rgfan123/zimu/issues/113) | OPEN | Shipment 级统一来源回传仍是待实现规格；明确要求 pull+push 语义合并。 |

“有本地代码”“受控测试通过”“远端 PR”“已合入”“已部署”“真实平台终态验收”是六个不同状态。本次只能确认前两类已有部分证据，不能宣称后三类完成。

## 清理边界

### 不应删除

- 当前 pull WIP（以下文件在核查时与 `74e4a318` 对应 blob 完全一致）：
  - `backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoConnector.java`
  - `backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoPullClient.java`
  - `backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoOrderTransform.java`
  - `backend/src/test/java/cn/zimu/fulfillment/connector/jufubao/JufubaoConnectorTest.java`
- `c529fe49` 中除冲突 Connector 外的 push seam、HTTP gateway、持久幂等 store 及其测试；冲突 Connector 也应作为行为来源保留到语义合并完成，不能直接覆盖当前文件。
- `scripts/jufubao_fetch_orders.py`：它是只读拉单/抓取辅助工具，不是生成缓存；在生产 Connector 和 receiver 契约验证完成前不要删除。`74e4a318:scripts/jufubao_fetch_orders.py:91-152`
- `docs/research/jufubao-supplier-export-api.md` 与 `docs/research/golden/jufubao-order-golden.json`：前者需后续去敏和纠错，不应因内容过时直接丢失一方契约历史。
- 本地受控抓包/订单证据不属于源码缓存；含凭据或 PII 的材料不得提交，也不得未经数据保留与凭据轮换确认就自动清理。

### 可再生生成物候选（不自动删除）

- `backend/target/`，包括其中可能残留、与当前源文件不一致的旧 Surefire 报告。
- `frontend/dist/`、`frontend/node_modules/`（需要时可由锁文件重建）。
- `__pycache__/`、`*.pyc`。

上述目录已经被 [`.gitignore`](../../.gitignore) 排除且技术上可重建，但删除前仍须确认没有活跃构建/测试进程，并按精确路径审批，不能据此自动清扫。`data-local/` 也被忽略，但其中可能是商业数据、真实抓包或本地凭据，**不能把整个目录当缓存批量删除**；应逐文件审批，先轮换可能暴露的凭据，再决定保留/安全销毁。

## 可执行验证清单（本阶段不做真实写）

- [ ] 在隔离分支完成行为式合并，确认唯一 `JufubaoConnector` 同时保留 file import/export、online pull、online push。
- [ ] 受控 HTTP 测试覆盖一次登录会话被 pull/push 共享、CSRF/header、401 只重登录一次、分页和敏感信息不进日志。
- [ ] 用新增的**脱敏只读抓包**锁定 `NO_RECEIPT` 查询方式、`receive-order` 请求/响应、地址确认响应以及 receiver 的权威读取路径；在此之前相关功能保持失败关闭。
- [ ] receiver 缺失、字段不全、地址变化、地址检查未知均进入 review；完整 receiver 才能形成可履约输入。
- [ ] `NO_RECEIPT → 接单 → 重查 NO_DELIVERY`、已是 `NO_DELIVERY`、其他状态、接单拒绝和转换后状态不符均有受控测试。
- [ ] `sub-order-info` 数量异常、承运商未映射、平台明确拒绝、超时/畸形响应、受理后仍在待发货均有测试。
- [ ] PostgreSQL/Testcontainers 覆盖并发 claim、进程重启重放、同 key 不同 payload 冲突、写前安全重试、写后未知永久锁定。
- [ ] 在线与文件降级路径共享同一 Shipment 幂等/同步状态；同一子单和运单不能并发双写。
- [ ] 对 PR SHA 运行专项测试、完整后端测试与构建；分别记录提交、PR、合入和部署状态。
- [ ] 最后才安排 #102：由用户明确批准时间窗和目标订单后做真实平台写；必须脱敏保存写前状态、提交引用和写后终态。未获批准时只允许登录/查询等另行授权的只读验证。

## 对旧研究文档的纠正

- `docs/research/jufubao-supplier-export-api.md` 中早期“`orders/query` 收货人可能在 `multi-send-form` / `sub-order-info`”只能视为待验证假设，不能继续写成映射建议。
- 旧 `multi-send` 是历史批量写路径，不是当前单订单成功判定；receiver 字段出现在写请求不等于存在可靠 receiver 读取接口。
- “每日 1–2 次”等文字是旧的运营建议，不是一方接口频控证据；本次未发现可据此实现固定频率限制的官方契约。
- 文档中的任何真实账号、姓名、电话、地址、Cookie 或 Token 示例都应在后续单独去敏提交中移除；本报告没有复制这些值。
