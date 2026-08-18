# 红队评审：三平台订单在线接入方案（保持 / 修改 / 推翻）

状态：红队评审稿（2026-08-18）
被挑战对象：`docs/research/platform-api-integration-design.md`（重点 §0/§1/§3/§4/§8）+ 最近一次修改决策（删 JSON→Excel 转换器、聚福宝直进 Phase 1、T05/T06 分票）
评审方法：只读核对 backend 代码、DDL、契约文档与脚本；全部结论带证据定位。
结论速览：**方向正确，但「零领域改动」「试点最轻」「幂等三层」三个核心论据均不成立；存在一个会同时打穿 Phase 0 与 Phase 1 的幂等洞；分票漏了关键路径票。按 §5 修改后分票重排再开工。**

---

## 0. 执行摘要（最尖锐的 5 条）

1. **「已存在则跳过该单」是文档愿望，代码事实是「重复订单 = 整批 409 回滚」**。`OrderCreateService.doCreate`（`order/OrderCreateService.java:319-322`）对 `(source_channel, source_ref)` 已存在抛 `DUPLICATE_ORDER`，`SourceImportService.upload` 不捕获 → 整个批次事务回滚。设计 §4.6.1 宣称的跳过语义在应用层**不存在**。后果：Phase 0 彩食鲜/飞象按「近 30 天窗口」每日全量重拉，**第 2 天起必然撞重复订单整批失败**；Phase 1「失败重拉从水位重来」= 窗口重叠 = 必撞。设计与其自身的重试逻辑自相矛盾。
2. **聚福宝试点的「结构化导入」入口不存在，试点比宣称重得多**。`SourceImportService` 是包私有类（`SourceImportService.java:52`）、`upload` 只收 `byte[]` + 指纹解析；`createImported` 虽 public 但要求**已存在**的同渠道 `SOURCE_ORDER` 批次（`V1__baseline.sql:996-1002` trigger 强制）；而 confirm/导出/回填管线硬依赖 `raw_import_rows` 血缘（`ProviderFileService.candidateRows` `FROM app.raw_import_rows rir ... WHERE rir.status='ACCEPTED'`）。JSON 直连若不走「新建批次 + raw 行 + 订单」的结构化用例，确认后一张履约导出都生成不了。「Layer 3 现有代码零领域改动」不成立，试点需新建 ≈ 半个 `upload` 的应用层代码。
3. **「改一行配置」不便宜，WECOM 铁律是双层代码事实**：`doCreate`（`:313-318`）硬校验 `!imported && channel!=WECOM` 拒绝 + DDL 双 CHECK（`V1__baseline.sql:297-298`）拒绝非 WECOM 无批次 + trigger 强制批次渠道一致。放开内部 API 需要「先建同渠道 SOURCE_ORDER 批次 + raw 行」——这正是 Connector 需要的同一件新代码；in-process Connector 直接复用 `IdempotencyService`/事务反而比内部 REST 更便宜。方向对，理由错。
4. **试点选型逻辑反了**：选了「链路最短」的聚福宝（JSON），而它恰恰是唯一**无法**复用 byte 文件管线的平台；彩食鲜/飞象的 pullOrders 拿到真实文件字节后，把 `upload` 从包私有改为 public 就能零改动复用（含 raw 行、confirm、导出）。若本周目标只是「先跑通一条全链路」，彩食鲜或飞象才是真正的零改动试点。
5. **被挑战对象自身不一致**：设计文档仍含 §3.4.2 聚福宝 JSON→Excel 转换器 + 任务 0d + M0 含聚福宝，与「最近修改」（删转换器、聚福宝跳过 Phase 0）矛盾；「已实测的 Python 拉取脚本」对飞象言过其实——飞象样例**仅表头、无数据行**（`scripts/feixiang_fetch_orders.py` 头部注释），21 列 v1 真实数据行的表头/取值从未被真实数据验证，而 parser 的 `feixiang()` 读取 `收货人姓名/收货人手机号/收货人地址/会员名称`，若真实 21 列名不同 → 全行 NEED_REVIEW。

---

## 1. 维度一：铁律是否真铁

**现状**：`/internal/v1/orders` 的 source 固定 WECOM 是**代码事实**，三层：
- 应用层：`OrderCreateService.doCreate:313-318`——`(!imported && channel != WECOM)` 抛 `SOURCE_CHANNEL_NOT_SUPPORTED`；反向 `createImported` 拒 WECOM。
- DDL：`V1__baseline.sql:297-298`——非 DEMO 非 WECOM 必须 `source_import_batch_id IS NOT NULL`，WECOM 必须为 NULL。
- Trigger：`V1__baseline.sql:996-1002`——批次必须 `batch_type='SOURCE_ORDER'` 且渠道与订单一致。
- 附带：`InternalOrderController` 本身不校验 source（javadoc 才写 WECOM）；api-contract §5.1 只是文档层。

**问题**：「三平台只能走 Connector/文件入口」这条铁律成立，但它的**真正代价**被低估：任何非 WECOM 订单都要求先存在一个同渠道 SOURCE_ORDER 批次 + raw 行血缘（confirm/导出管线依赖，见维度三）。放开内部 API = 在内部 REST 上重做一遍「建批次+raw 行+订单」——与 Connector 需要的新代码是同一件，且 REST 还要网关 Bearer 身份、OpenAPI、DTO。「改一行配置」不成立。

**建议**：保持铁律（不要放开 `/internal/v1/orders`）；把新增能力放在**应用层结构化导入用例**（供 in-process Connector 调用），不放内部 REST。要动的面：`SourceImportService` 可见性/新方法 + actorType（现硬编码 `AuditActorType.HUMAN`，`SourceImportService.java:207`）+ 新批次/raw 行写入——这是**对 P0 Approved 闭环组件的规范级改动**，需走评审，但规模可控。

---

## 2. 维度二：Phase 1 vs Phase 0 性价比

**现状**：删转换器后 Phase 0 只服务彩食鲜（export 任务链）与飞象（直下）——恰好是 1c/1d Java Connector 要重写的**同一段契约**（彩食鲜：登录→exportDeliverExcl→轮询 task/my→download→parser；飞象：cookie→deliveryExport→parser）。Phase 1 上线后 Python 变「兜底通道」= 同一契约双实现，每次平台改版要修两遍。

**问题**：
1. 「零 Java 改动当天可用」打了折扣：上传走 `/api/v1` 需网关 Basic Auth 且 `X-Operator` 与凭据主体一致（`RequestContextFilter.java:66-81`）→ 必须建服务主体 `svc-platform-pull`（D2，ops 工作）并把网关凭据放进脚本 env（安全面扩散）；且脚本得带 ≥8 字符 Idempotency-Key（`WriteCommands.java:28-29`）。
2. **Phase 0 没有增量口径**：拉取窗口「近 30 天→当日」（设计 §3.3），每日全量 → 第 2 天起撞执行摘要 #1 的重复订单 409。「当天可用」实际需要一个窗口/去重修复（本报告 §5 新增 T03）。
3. 若「回退 Python 脚本」是 Phase 1 的兜底 1（设计 §1.3/§7.3），则 Python 不能退役 → 双实现是长期负债；若退役，回退链断裂。**二选一矛盾未解**。

**建议（修改）**：Phase 0 保留但定位为**有 kill-date 的桥接（≤2 周）**；兜底链改写为「Java Connector → 人工导表（上传入口永在，这才是真正的兜底）」，从设计语言中删除「回退 Python 脚本」。若本周订单流动不是刚性需求，更激进选项：彩食鲜直接进 Phase 1（1c 估 2d，export 任务链 Java 重放契约完整），Phase 0 只留飞象。

---

## 3. 维度三：聚福宝试点是否真的「零 Excel」

**现状（代码核实）**：
- 文件入口唯一：`POST /api/v1/import-batches/source-orders`（`SourceImportController.java:28`），`SourceImportService` **包私有**、`upload` 只收 `byte[]`，`SourceFileParser.parse(byte[])` 按容器魔数+表头指纹识别（`SourceFileParser.java:51-63`）。
- `OrderCreateService.createImported` 是 public（`:127-141`），但要求已存在批次 id；无公开「结构化创建批次」用例。
- confirm 流程：`SourceImportService.confirm` 检查「批次内 raw 行 status<>ACCEPTED 为 blocker」（`:459-465`）；履约导出 `ProviderFileService.candidateRows` 硬 JOIN `raw_import_rows`（status='ACCEPTED'）；来源回填同样从 raw 行血缘走。**JSON 直连不写 raw 行 → 确认通过也生成不了导出/回填。**

**问题**：设计 §4.3/§4.6 说「transform → CanonicalOrderInput → ImportBatch 用例」，但该用例**不存在**；§4.6 自己承认「或等价的批次服务」——这张票没排进任何任务清单。聚福宝试点真实工作 = 认证客户端 + pullOrders/transform（设计有）+ **新建结构化导入用例**（建批次 + raw 行血缘 + 订单 + 跳过已存在 + actorType=SYSTEM，≈ upload 的 1/3–1/2 工作量，且动的是 Approved 组件）。「2-3 天试点」口径需上调，且依赖图要加这张票。

**建议（修改）**：
- 新增关键票 T07「结构化导入用例」（见 §5），并**先于或并行于 T05** 开工（T05 只影响 Connector 侧接口，T07 是落库侧，二者无依赖）。
- 若追求「最小改动试点」，改选彩食鲜或飞象：把 `SourceImportService.upload` 从包私有改为 public（1 行可见性），Connector 下载字节后直接复用——raw 行/confirm/导出全免费。聚福宝等 T07 就绪后再上。
- 不要在 Java 里合成 Excel bytes 喂 upload（= 刚砍掉的「JSON→Excel 绕路」在 Java 复活）。

---

## 4. 维度四：分票结构

**现状**：T05（接口演进，无 blocker）→ T06（聚福宝试点，blocked by T05）；T01/02/04（Phase 0）与 T05/T06 并行；T09（testConnection+监控）挂 T06 之后。

**问题**：
1. **T06 依赖表漏了关键边**：T06 还 blocked by (a) T07 结构化导入用例（§3）；(b) D4 聚福宝收货人补抓——缺收货人 → 每行 NEED_REVIEW（parser `:227-229` 必填校验）→ confirm 被 blocker 拒 → T06 的「REAL 端到端批次确认」验收**永远过不了**。D4 被当成「用户 10min」待办而非 blocker 票，错。
2. **并行真实性**：T05 与 Phase 0 代码面确实无共享；但共享 `.env` 命名空间（CSX_*/JFUBAO_*/FEIXIANG_*）与**双状态源**——Phase 0 脚本写 `ingest/status/*.json`，Phase 1 写 `connector_configs.last_pull_at`，互不读取 → 「拉取最小间隔 12h」只在 Phase 1 内部生效，脚本不认 → 双通道同日双拉没有真互斥。并行期同一渠道必须二选一，或 T07 实现「跳过已存在」。
3. T09 挂 T06 后：testConnection 真实探测逻辑上只需 T05+T06 的认证客户端，顺序合理；但**告警通道（D3）未建**，T09 的「失败告警」验收是空转——要么 T09 内含通道建设，要么验收降级为「日志+状态文件+操作员看板」。
4. **漏票**：D4 补抓、D8 取消态枚举补抓、D11 彩食鲜/飞象回传补抓、凭据轮换机制（D13「定期改密」无日程无责任人）、接口失效演练、契约 golden 样本、幂等并发窗口修复（见 §5）、ingest/ 入 `.gitignore` 与 `.env.example` 补齐、网关服务主体（D2）、双调度器互斥规则（D14）。

**建议**：按 §5 重排；把 D4/D8/D11 从「决策点清单」升格为有验收的补抓票；新增 T03/T07 两张关键票；T09 与 D3 绑定。

---

## 5. 风险盲区

### 5.1 无 SLA 接口失效
- **现状**：设计缓解 = 灰级告警（404/结构变化）+ 回退链「Phase1→Phase0→人工」。
- **问题**：(a) 回退链依赖 Python 脚本，而脚本按 §2 建议会退役——链断；(b) 结构变化识别在 Phase 1 只是 `PlatformErrorMapper` 的**纸面建议**（无 schema 校验、无测试），Phase 0 只有魔数/状态码校验；(c) **没有任何接口失效演练**，第一次失效必然发生在生产清晨。
- **建议**：每平台冻结一份真实响应 golden 样本 + 拉取时字段级校验；排一次失效演练（改 endpoint → 验证灰级告警 → 人工导表流程）；兜底只承诺「人工导表」。

### 5.2 幂等三层漏洞（最尖锐）
1. **重复订单 = 整批 409**（执行摘要 #1）：`doCreate:319-322` 抛 `DUPLICATE_ORDER`，`upload` 不捕获。任何「新批次含已存在订单」的场景（Phase 0 次日重拉、Phase 1 失败重拉窗口重叠、双通道并行）整批失败。**必须**在结构化导入用例（T07）实现「行级跳过已存在 + AuditLog(business_code=ORDER_ALREADY_EXISTS)」，否则 Phase 0/1 都站不住。
2. **并发内容哈希重放**：`uq_import_content_scope` 唯一索引存在（`V1__baseline.sql:2211`），但 `upload.existing()` 先 SELECT 后 INSERT，两个并发相同上传 → 一个撞唯一索引 → 未捕获 → 500（设计承诺「重传返回原批次」并发下失效）。修：捕获唯一冲突后重查返回既有批次。
3. **幂等键是装饰**：上传端点的 Idempotency-Key 只写进审计 payload（`SourceImportService.java:210-214`），去重全靠内容哈希；设计与「幂等键三层」的说法比实际强。文件路径的订单级幂等靠 createImported 的 `import-{batchId}-{sha}`，与上传键是两套。

### 5.3 调度/凭据安全
- Phase 0 脚本 env 持有**网关 Basic 凭据**（D1 方案 A 长期化）→ 凭据扩散到脚本层；建议 D1 直接选方案 B（`/internal/v1/import-batches/source-orders` 薄端点 + Bearer 服务身份，转发同一 `upload`）。
- `data-local/*-credentials.txt` 明文密码 + `*.har` 含明文密码与**有效会话**（gitignore 有覆盖，但无轮换日程/责任人；会话劫持面真实存在）。
- token 内存缓存单实例 OK，多实例需 Redis 锁——设计已承认（D14），低风险。
- 双调度器（cron/launchd + `@Scheduled`）同时开 → 双拉；两状态源不互认（§4.2）。

---

## 6. 最终分票建议（含 blocking 边）

**保持**：
- T05 接口演进（default 方法 + PullCursor/PullResult/SourceOrderEnvelope + PlatformSessionManager/ErrorMapper/限流重试）——无 blocker，独立，与 Phase 0 真并行。
- Phase 0 收缩为只服务彩食鲜/飞象（删聚福宝转换器）——方向对，但加 kill-date 与增量口径。

**修改**：
- 新增 **T07 结构化导入用例**（应用层：建批次 + raw_import_rows 血缘 + 订单同事务；行级跳过已存在；actorType=SYSTEM；捕获唯一冲突重查；复用/收口 confirm 管线）。无依赖，**优先排期**；因动 P0 Approved 组件，前置规范评审。
- T03 Phase 0 增量口径（彩食鲜/飞象窗口缩至当日/昨日，或上传前按批次去重）——依赖 T01/T02；无它 Phase 0 第 2 天即死。
- T06 聚福宝试点——blocked by T05 **+ T07 + D4（收货人补抓，升格为 blocker 票）**；验收加「重复订单跳过」用例。
- T09 健康检查+监控——blocked by T06/T08/T09 任一实现 + **D3 告警通道决策**（先建通道或降级验收）。
- 彩食鲜 Connector（1c）/飞象 Connector（1d）——blocked by T05；可先做（文件化 pullOrders 仅需 `upload` 可见性改动）。
- T11 Phase 2 回传——blocked by D11 补抓 + T06。

**推翻**：
- 推翻「回退 Python 脚本」兜底承诺 → 兜底 = 人工导表（上传入口永在）；Python 在 Phase 1 上线后按 kill-date 退役，不维护双实现。
- 推翻「聚福宝为最小试点」选型 → 若追求最快全链路，先以彩食鲜或飞象文件化 pullOrders 试点；聚福宝等 T07。
- 推翻「幂等键三层」叙事 → 按 §5.2 三处修复后如实重写 §4.6。

**横切新增**：凭据轮换机制（责任人+日程）、接口失效演练、三平台契约 golden 样本、`.env.example`/`ingest/.gitignore` 补齐、网关服务主体（D2，或 D1 选 B）。

**推荐依赖图**：`T01→T02→T03→T04`（Phase 0 链）；`T05` 独立；`T07` 独立（关键路径，最先）；`T06 = f(T05, T07, D4)`；`T08/T09 = f(T05)`；`T10(健康检查+监控) = f(T06|T08|T09, D3)`；`T11 = f(D11, T06)`。

---

## 7. 一句话结论

**保持**：Phase 1 in-process Connector + 批次闭环的架构方向、WECOM/批次铁律、Phase 0 收缩的直觉；**修改**：先补「结构化导入用例」与「增量/去重」两张关键票再排试点，修复重复订单=整批 409 与并发重放两个幂等洞，兜底只承诺人工导表；**推翻**：聚福宝为最小试点、Python 兜底通道、幂等三层叙事——按 §6 重排后开工，否则 Phase 0 第 2 天与 T06 验收会同时失败。
