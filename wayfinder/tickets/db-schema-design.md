---
label: wayfinder:grilling
title: 数据库 Schema 设计
status: closed
claimed_by: zed-main
blocked_by: []
parent: wayfinder:map
---

# 数据库 Schema 设计

## Question

基于 PRD §8 字段契约 + §23 ER + §17 状态机，定出 PostgreSQL **全部表/列/类型/枚举/索引/约束**，产出可执行的 DDL。这是所有构建票的地基。

## 决策点

- 表清单：order / order_line / order_version / order_event / customer / product / category / specification / internal_sku / alias / channel_sku / jd_sku / fulfillment / shipment / tracking / shipment_sync / procurement_ticket / audit_log + analytics 视图/物化视图（§20/§21 指标）；
- 状态维度：OrderStatus / FulfillmentStatus / ShipmentStatus / SyncStatus / ProcurementStatus 用枚举还是查找表（倾向枚举 + PG enum 或 CHECK）；
- 幂等键：放哪张表（idempotency_key 唯一约束）；
- order_version 快照方案（§5 模块里有 order-version——每次更新存快照？）；
- 金额/数量类型（decimal 精度）、unit 字段（§8 新增建议）；
- 时间戳与时区（timestamptz）；
- analytics 视图/MV 清单（渠道×商品×日期 等）；
- Flyway 迁移 vs JPA ddl-auto（默认 Flyway，见地图 Notes）。

## 产出

- `docs/schema.md`（表设计说明 + ER）
- `docs/schema.sql`（可执行 DDL）

## Blocked by

无（前沿票）。

## Resolution

### 决策日志（grilling 已完成，2026-08-10 至 2026-08-11，zed-main）

**Q1 表清单（已定，后续决策增补）**：当前 38 张业务表 + 3 个分析视图 + 1 个操作视图；关闭本票前再做最终计数核对。

- 订单域：`orders` / `order_lines` / `order_line_components` / `order_versions` / `order_event_types` / `order_events`
- 客户域：`customers` / `customer_source_refs`（内部统一客户档案 + 各来源渠道身份映射；Receiver 姓名/电话/地址快照在订单上，电话不唯一；`orders.customer_id` 仅在 RECEIVED/NEED_REVIEW 阶段可空，进入 VALIDATED 前必须绑定）
- 商品域：`products` / `categories`（`products` 只表达商品族；**specifications 不建表**，`specification` / `unit` / `barcode` 归 `skus`；OrderLine 保存下单时的商品名、SKU 编号、规格、单位快照）
- SKU/履约方域：`skus` / `sku_aliases` / `source_channel_skus` / `fulfillment_providers` / `provider_skus` / `provider_stock_snapshots`。`skus.fulfillment_provider_id` 指向唯一履约方；`provider_skus` 统一承载履约方侧外部商品编码（京东的 jd_goods_no / erp_goods_no 也是其中一种）；`provider_stock_snapshots` 只承载我方负责管理的库存（当前主要是存放于京东云仓的我方库存）的 warehouse_code / stock_num / usable_num / synced_at，不采集或管理第三方自有库存，也不建设替代仓库系统的内部库存账。
- 履约域：`fulfillments`；发货域：`shipments` / `shipment_items` / `trackings` / `shipment_syncs`；采购域：`procurement_tickets` / `procurement_ticket_items` / `procurement_receipts` / `procurement_receipt_items`；审计域：`audit_logs`
- 连接器域：**`connector_configs`（新增）**——§22「系统→Connector」页数据源：按来源渠道一行，enabled / mode(mock\|real) / last_pull_at / last_error
- 履约导出域：`fulfillment_exports` / `fulfillment_export_items`（导出批次、模板版本、文件引用/哈希、履约方、逐订单行快照与审计信息）
- Excel 接入/回填域：`import_batches` / `raw_import_rows` / `source_return_exports` / `source_return_export_items`（原始文件与模板指纹、Sheet/原始行号/原始单元格、CanonicalOrder/OrderLine 映射、按来源格式生成的版本化回填文件，以及每版逐原始行的回填快照与审计）
- 复核域：`review_cases`（客户匹配、SKU 映射、数据质量、COD/精度和人工介入等统一复核事项；可关联 order 与可选 order_line，保存责任部门、稳定原因码、详情、处理结果与版本）
- 运营提醒域：`operational_alerts`（不阻断流程但要求人工知晓/确认的提醒；关联 order/order_line/fulfillment，保存类型、黄色/红色级别、消息、OPEN/ACKNOWLEDGED/RESOLVED、确认人与时间）
- 演示隔离域：`demo_runs`（独立 Mock 演示运行与其唯一 demo order 的关联、场景代码、运行状态、开始/结束时间；不进入业务闭环）
- 通用域：`idempotency_registry`
- channel 不建表：四渠道 PRD 定死的常量，`orders.source_channel` 枚举列，视图 GROUP BY 不吃亏
- 分析视图：`v_analytics_channel_daily`（渠道×日期：订单数/行数/实发量/shipment 数/异常/缺货/回传失败）、`v_analytics_product_daily`（渠道×商品×日期：实发量/订单数）、`v_analytics_fulfillment_daily`（履约状态计数：京东履约量/缺货/采购/待出库/已出库/待运单/回传失败）
- 操作视图：`v_order_progress_summary` 从订单行/履约单元的权威状态派生订单级最差进度、四色健康度、完成数/总数和关注原因；该视图只用于查询展示，不作为状态机写入目标
- customer 接收语义：§7 payload 的 customer 先通过 `customer_source_refs` 匹配内部统一档案；禁止按 Receiver phone 自动 upsert。匹配失败进入 NEED_REVIEW，由客户部门关联已有档案或新建档案；receiver 三字段继续内嵌 orders 作为不可变收货快照
- 待 B3 回看：`provider_skus` 中京东映射的 jd_goods_no / erp_goods_no 哪列是主路径（京东认我方编码 vs 我方存京东编码），真实封装时定

**Q2 有限值存储形态（已定，2026-08-11 重新裁决）**：V1 不使用 PostgreSQL native enum。仍属封闭值集的列统一用 `VARCHAR + CHECK`，包括 order_status、order_line_type（SINGLE / CUSTOM_BUNDLE）、shipping_progress、processing_stage、shipment_status、sync_status、procurement_status、idempotency_status、source_channel、settlement_method 等；Java 侧使用 `@Enumerated(STRING)` / Java enum 提供编译期类型安全。可扩展的 `order_event.type` 改用 `order_event_types` 目录表，FulfillmentProvider 等业务目录继续使用实体表。状态排序必须在 analytics 视图用显式 CASE/权重表达，不依赖数据库类型的内在排序。裁决依据见 `docs/research/postgres-enum-vs-check.md`。原先的 `fulfillment.type = JD_WAREHOUSE / PROCUREMENT` 已被 Q6 撤销；原单一 `fulfillment_status` 已被 Q7/Q25 两轴模型取代。

**Q3 幂等机制（已定，Q22/Q55 补全）**：统一幂等注册表，UNIQUE(scope, idempotency_key)；scope 是服务端按写用例映射的稳定小写代码，满足 `^[a-z][a-z0-9_.-]{0,63}$`，不由客户端传入，也不作为每加一个端点就迁移 DDL 的封闭枚举。首批包括 order_create / fulfillment_outbound / shipment_create / shipment_sync / procurement_create / procurement_receipt，API 其余写用例使用同一命名约定；同 key 不同 payload_hash → 409，业务表不重复保存 idempotency_key。并发抢占、租约、外部副作用与崩溃恢复语义以 Q22 为准。

**Q4 版本快照职责与恢复边界（已定）**：`order_versions` 是只追加的审计历史，不作为数据库回滚或通用业务恢复机制，Demo 不提供恢复/回滚接口。未来若增加恢复能力，只能把旧快照作为一次新的受状态约束的变更输入，生成更高版本并记录来源版本、OrderEvent 与 Audit Log；京东出库、发货、渠道回传等外部副作用发生后禁止覆盖回旧状态，只能走取消/冲正/重试等补偿流程；数据库灾难恢复由 PostgreSQL 备份/PITR 承担。

**Q5 Version Validation 语义（已定）**：PRD §7 接入链路中的 Version Validation 专指**上游订单修订版本校验**，不与审计快照或数据库并发控制混用。三平台 Connector 归一化后的 Canonical Order 可携带 `source_version`；同一 `source_channel + source_ref` 只接受更新版本、拒绝旧版本。WECOM/Internal API 首次创建没有上游版本字段，只执行重复与幂等校验。`order_versions.version_no` 仅作审计历史序号；数据库并发控制另用 `lock_version`。

**Q6 履约方与采购边界（已定，Q14 统一命名）**：京东云仓只是履约方之一，系统还需容纳第三方履约方；采购不是 Fulfillment 类型或履约方，而是未发货过程中的补货分支。一个 Fulfillment 选定履约方后，缺货会自动创建采购工单；采购成功后回到原履约方继续履约，履约方不变。领域中严格区分 `SourceChannel`（订单从哪里来）与 `FulfillmentProvider`（谁负责备货与发货）。

**Q7 履约状态表述（已定，Q25/Q27 修正）**：撤销把未发货、采购中、异常、已发货放在同一扁平 `FulfillmentStatus` 的设计，保留两轴：`fulfillments.shipping_progress` = NOT_SHIPPED / PARTIALLY_SHIPPED / SHIPPED，表达实际发货结果；原 `pending_stage` 按 P0 闭环扩展并统一改名为 `order_lines.processing_stage`，具体值见 Q25。整单状态由订单行汇总，不手工维护同义字段。

**Q8 订单行、履约与发货基数（已定，Q50 修正）**：一期不支持同一订单行跨履约方拆分；一条 OrderLine 固定一个 Fulfillment 和一个 FulfillmentProvider。缺货可产生多张 ProcurementTicket，但采购成功后仍回到原 FulfillmentProvider。数据库约束为 `order_lines 1—1 fulfillments`（`fulfillments.order_line_id` UNIQUE）；Fulfillment 与 Shipment 通过 `shipment_items` 形成多对多分配关系：一条 Fulfillment 可因部分发货进入多个 Shipment，一个 Shipment 也可合并同一订单、履约方、收货地址下的多条 Fulfillment。`shipment_items` 保存该批指令数量与实际发货数量并做数量守恒；整单因不同履约方必须拆成不同 Shipment/文件。

**Q9 Excel 输出与机器人边界（已定）**：本系统的责任止于生成真实、可下载的规范 Excel 文件；不接入企业微信机器人，也不负责把文件真实发送给第三方对接人。机器人发送属于地图既有 Out of scope。

**Q10 Excel 转换方向（已定）**：内部标准订单 `CanonicalOrder` 是唯一事实源，Excel 转换分为输入与输出两侧：外部来源表单 → CanonicalOrder；CanonicalOrder → 京东云仓模板；CanonicalOrder → 系统自定义的第三方模板。京东模板与第三方模板是并列的输出 Adapter，不各自复制业务规则或形成旁路订单模型。

**Q11 第三方模板业务含义（已定）**：自定义第三方模板是发货前的 `FulfillmentExport`（发货指令单），承载收货人、商品、规格、单位、请求发货数量、要求送达时间等指令，不预填实际发货数量。第三方完成后的实际发货数量、物流公司、运单号与异常原因属于独立的发货结果回传流程。导出文件需记录导出编号、模板版本、生成时间与审计信息，以支持重复下载和追溯。

**Q12 第三方模板数量（已定，Q14 统一命名）**：京东使用京东官方模板；所有非京东履约方一期共用一套系统定义的第三方发货指令模板，模板内包含履约方编码与名称。未来某家第三方强制要求专用格式时，为其增加独立输出 Adapter，不修改 CanonicalOrder 或通用模板。

**Q13 第三方导出分批规则（已定，Q50 补充分组）**：一个 `FulfillmentExport` 批次只属于一个 FulfillmentProvider，禁止同一文件混入其他第三方的数据；一个文件可包含该履约方的多个订单和收货人，每个 OrderLine 输出一行，同单多商品重复订单号/收货信息但商品字段不同。同一订单、履约方、收货地址和发货批次的多行共享同一 `outbound_order_no`；所有文件行另关联同一 `export_batch_no`。一次选择多个履约方时分别生成多个 Excel。

**Q14 履约方统一命名与 SKU 互斥归属（已定）**：统一使用 `FulfillmentProvider`（履约方）替代此前混用的“发货渠道/FulfillmentChannel”；`SourceChannel` 仅指订单来源。全部 SKU 按履约方互斥分区：大多数归京东云仓，少数特殊商品只归某个第三方，不存在跨履约方重叠；`skus.fulfillment_provider_id` 必填并指向唯一履约方。统一使用 `provider_skus` / `provider_stock_snapshots`，不再维护命名不对称的 `jd_skus` / `fulfillment_channel_skus`。

**Q15 SKU 编号与归属不可变规则（已定）**：SKU 编号统一为 `SKU-{provider_code}-{6位全局流水号}`，例如 `SKU-JD-000128`、`SKU-TP01-000129`。`provider_code` 仅用大写英文/数字并在产生 SKU 后不可修改；流水号全局唯一递增，不按品类重置；编号不编码商品名/规格/品类。`skus.fulfillment_provider_id` 外键才是归属事实，业务不得只解析编号判断。一期禁止 SKU 原地改换履约方，确需变更时新建 SKU、停用旧 SKU，保留历史订单语义。

**Q16 Product 与 SKU 字段归属（已定）**：Product 是商品族，只保存名称、品类、描述等族级信息；`specification` / `unit` / `barcode` 与履约方归属都跟 SKU 走。同一 Product 可有多个不同规格 SKU；OrderLine 在下单时快照商品名、SKU 编号、规格和单位，主数据后续变化不改写历史订单。不建立独立 `specifications` 表。

**Q17 定制礼包组件归属（已定）**：礼包搭配由其他部门随每次订单明确传入，不维护 SKU 主数据上的静态 BOM。`order_lines.line_type` = SINGLE / CUSTOM_BUNDLE；普通行直接引用 `sku_id`，礼包行保存当次礼包名称/份数并由 `order_line_components(order_line_id, sku_id, quantity_per_bundle, total_quantity, product_name_snapshot, specification_snapshot, unit_snapshot)` 保存不可变组件快照。后续搭配变化不改写历史订单；导出时按本单组件展开。常用模板若未来出现，也只能作为录入辅助，下单时必须复制成订单组件快照。

**Q18 定制礼包履约方约束（已定）**：一期禁止一个定制礼包混合不同 FulfillmentProvider 的 SKU；所有 `order_line_components.sku_id` 必须与该 OrderLine/Fulfillment 归属同一履约方。跨履约方组件清单进入 `MANUAL_INTERVENTION`，不得自动生成履约导出。该跨表一致性由领域校验并在数据库侧用约束触发器兜底；跨履约方调拨、合箱与多包裹规则另立 effort。

**Q19 客户档案与匹配失败规则（已定）**：Customer 是公司内部跨系统共同维护的统一主数据档案，以内部 `customer_code` 为身份；`customer_source_refs(customer_id, source_channel, source_customer_ref)` 映射各渠道身份，同一 Customer 可有多条来源映射。Receiver 姓名/电话/地址始终快照在订单上，电话不唯一且不得用于自动合并客户。新订单无法匹配客户档案时进入 NEED_REVIEW，由客户部门关联已有客户或创建档案，未确认前不得进入履约；`orders.customer_id` 仅在 RECEIVED/NEED_REVIEW 阶段可空，进入 VALIDATED 前必须绑定。人工确认产生 OrderEvent 与 Audit Log。

**Q20 客户档案 MCP 接入边界（已定，API 契约收束）**：为后续 Agent 接入预留 Customer 模块 MCP Adapter，与 REST/UI 共用同一应用层 Interface，禁止直接读写数据库或复制业务规则。计划工具：`search_customers`、`get_customer`、`list_customer_review_cases`、`suggest_customer_match`；不向 Agent 注册 `resolve_customer_review`。Agent 只能提交匹配/建档建议，客户部门人工确认后才能执行写入。写操作必须携带幂等键、`expected_version` 与 operator/agent 身份，并记录 Audit Log；完整 MCP tool schema 见 `docs/api-contract.md`。

**Q21 SKU 映射失败规则（已定）**：外部表单商品只有命中已维护的 `source_channel_skus` 显式映射才能自动进入履约；条码、外部商品编号、名称和规格只用于生成候选建议，不得直接触发自动映射或发货。缺失映射时订单进入 NEED_REVIEW，由商品/SKU 管理人员关联已有 SKU，或创建带唯一 FulfillmentProvider 归属的新 SKU；确认后保存来源映射供后续复用。未来 Agent 可提交建议，但仍需人工确认，禁止大模型模糊匹配后直接写入。

**Q22 幂等并发与崩溃恢复（已定）**：请求先原子抢占 `idempotency_registry` 并写 IN_PROGRESS，之后才执行业务；同 key+同 payload 的并发请求不重复执行并返回“处理中”，同 key+不同 payload 返回 409；成功写 SUCCEEDED + target_id + response_snapshot，后续重放返回原响应。记录 owner_token / lease_expires_at / effect_started_at / attempt_count / updated_at / completed_at / error_snapshot。尚未开始外部副作用的失败可在租约过期后重领；外部调用开始后失联则进入 RECONCILIATION_REQUIRED，必须查询履约方结果，禁止盲重试。Excel 生成是本地确定性操作，可安全重试；外部 Adapter 应尽可能复用同一外部幂等编号。

**Q23 OrderEvent 类型存储（已定）**：`order_event.type` 不使用 PG enum 或封闭 CHECK，改为 `order_event_types(code, display_name, active)` 目录表，`order_events.event_type_code` 外键引用。目录初始包含 PRD §18 事件，并可增补 PROCUREMENT_RECEIPT_RECORDED、CUSTOMER_MATCH_CONFIRMED、SKU_MATCH_CONFIRMED、MANUAL_INTERVENTION_REQUIRED、FULFILLMENT_EXPORT_GENERATED 等；具体结果放 payload。Timeline 按订单内序号/created_at 排序，不按事件类型排序。该裁决与 Q2 一并基于 `docs/research/postgres-enum-vs-check.md` 的事实核验。

**Q24 CanonicalOrder 持久化职责（已定）**：外部 Excel 解析后的 CanonicalOrder 是系统长期保存、可查询、可追踪的正式业务事实，不是转换过程中的临时对象。京东导单表、统一第三方发货指令表，以及取得快递公司/运单号后按三平台原格式生成的回填表，均从 CanonicalOrder、Fulfillment、Shipment/Tracking 与原始导入行映射派生。

**Q25 P0 Excel 闭环与中间状态（已定）**：P0 主链为：三平台不同格式 Excel → 保存 import batch/raw row 映射 → 解析并长期保存 CanonicalOrder → 客户/SKU 人工复核 → 按唯一 FulfillmentProvider 生成京东官方模板或统一第三方发货指令 Excel → 等待履约方返回快递公司/运单号（第三方可能更慢）→ 保存 Tracking → 依原始 Sheet/行号把快递方与运单号回填到三个平台各自原格式 → 生成固定格式来源回填 Excel。`processing_stage` 主路径为 NEED_REVIEW → READY_TO_EXPORT → WAITING_PROVIDER → TRACKING_RECEIVED → RETURN_FILE_READY → COMPLETED，任一步可进入 EXCEPTION；此前已定的特殊缺货分支保留 PROCUREMENT_IN_PROGRESS。必须保存原始文件引用、来源模板指纹、Sheet、原始行号、原始单元格与 CanonicalOrder/OrderLine 对应关系，确保回填可追溯。

**Q26 订单级最差进度与四色健康度（已定，黄色语义已修正）**：多行订单继续独立推进，订单列表/详情的 `OrderProgressSummary` 取所有未完成订单行/履约单元中的最慢阶段；EXCEPTION 直接覆盖普通进度并显示异常。进度摘要必须同时返回 `completed_count / total_count`，例如“等待履约方（2/3 已取得运单）”。另派生与进度正交的 `ProcessingHealth`：BLUE = 系统内部正常处理中、无需等待外部动作，YELLOW = 正在等待履约方或人工动作但尚未失败（明确包括 WAITING_PROVIDER，因此上述示例显示黄色；NEED_REVIEW、PROCUREMENT_IN_PROGRESS 也为黄色），RED = 明确异常，GREEN = 全部完成。颜色只是 VI/查询字段，不作为数据库业务状态、状态转移条件或人工可写字段；权威状态仍保存在所属业务实体上，订单级摘要由 `v_order_progress_summary` / API 聚合生成。

**Q27 ProcessingStage 权威归属与原子转移（已定）**：`order_lines.processing_stage` 是 P0 流程阶段的唯一可写权威值，每条订单行独立推进；`orders` 不保存第二份可写 `processing_stage`，只通过 Q26 的聚合视图/API 展示最差进度。每次转移必须在同一数据库事务中完成相应业务事实写入（例如 FulfillmentExport item、Shipment/Tracking 或 SourceReturnExport item）、订单行阶段更新、OrderEvent、OrderVersion 与 Audit Log；任一写入失败则整次转移回滚。`order_lines(processing_stage, order_id)` 建组合索引支撑工作队列与订单聚合。Fulfillment 另存正交的 `shipping_progress`，不得用它替代 P0 处理阶段。

**Q28 数量精度（已定）**：为防御未来称重商品、第三方小数数量和礼包倍率，所有业务数量统一使用 PostgreSQL `NUMERIC(18,3)`，包括订单行订购数量、礼包 `quantity_per_bundle` / `total_quantity`、履约请求量、采购请求/可用量、Shipment 实发量及履约方库存快照；Java 使用 `BigDecimal`，禁止 `float` / `double`。件、盒等离散单位仍按同一类型保存整数值（例如 `1.000`），不另建整数数量分支。业务流量字段必须大于 0，库存快照允许等于 0；单位仍跟 SKU 并快照到订单行/礼包组件。

**Q29 超精度数量输入（已定，低频防御）**：正常业务预计不会出现超过三位小数的数量，但禁止依赖 PostgreSQL `NUMERIC(18,3)` 的隐式舍入。输入 Adapter / 应用层在落库前校验 scale；超过三位小数时保留 `raw_import_rows` 原值，使用稳定错误码 `QUANTITY_SCALE_EXCEEDED` 将对应订单行置为 NEED_REVIEW，禁止自动截断或四舍五入后继续履约。人工修正必须生成 OrderEvent 与 Audit Log。

**Q30 金额字段与京东必填 `0`（已定）**：当前 PRD/三种来源输入只提供结账方式与结账时间，没有可作为业务事实的订单金额或商品价格，因此 P0 不在 `orders` / `order_lines` 虚构金额字段。京东官方导单模板的 `*商品金额` 是另一层的必填接口参数：`JdOutboundExcelWriter` 必须在每一条有效商品行实际写入数值 `0`，不得留空或省略，否则京东导入会报参数错误。生成后的逐行校验把该单元格非数值 `0` 视为导出失败；该值记录在导出行快照中，但不反写为 CanonicalOrder 金额。

**Q31 货到付款阻断（已定）**：P0 不支持货到付款（COD）导出。若来源数据明确表示货到付款，而 CanonicalOrder 没有可信的应收金额，则对应订单行进入 NEED_REVIEW，记录稳定错误码 `COD_AMOUNT_REQUIRED`，禁止生成或加入京东导单批次；绝不能用模板必填的数值 `0` 冒充 COD 金额。原始结账字段继续保存在 `raw_import_rows`，人工只能改为受支持的非 COD 结账方式或在未来扩展真实金额模型后处理，并留下 OrderEvent 与 Audit Log。

**Q32 时间与时区（工程默认，非 HITL）**：所有业务时间与审计时间使用 PostgreSQL `TIMESTAMPTZ`、Java `Instant`，数据库按 UTC 语义保存；来源 Excel 中无时区的日期时间按 `Asia/Shanghai` 解释，同时在 `raw_import_rows` 保留原始单元格。API 对外使用带偏移的 ISO 8601，UI 与 Excel 输出按上海时区格式化；分析视图以 `Asia/Shanghai` 自然日分桶。无法解析的业务时间保留原值并进入 NEED_REVIEW，不做静默猜测。该类稳健工程默认后续不再向用户逐项提问。

**Q33 增量来源回填文件（已定，Q41/Q42 补充全量取消）**：同一来源 Excel 内，京东或其他较快履约方的运单已返回而第三方仍等待时，允许立即生成阶段性回填文件，不阻塞已就绪行。每一版都复制原工作簿结构与原始行序，只在已有有效 Tracking 的原行写入快递公司/运单号，未就绪行保持原有空白，禁止填占位符；后续生成新版本，不得覆盖旧文件。`source_return_exports` 以 `(import_batch_id, version_no)` 唯一，保存 `is_final`、文件引用/哈希、生成时运单截止时间与审计字段；新增 `source_return_export_items`，逐行快照 raw_import_row/order_line/tracking 关联、FILLED/PENDING/CANCELLED/EXCEPTION 结果及实际写出的快递公司/运单号。CANCELLED 仅表达“该行零实发且已人工全量取消”的来源输出行，不创建或伪造 Shipment；部分实发后取消剩余量仍只用 FILLED 行并快照 PARTIALLY_FULFILLED/cancelled_quantity。所有应回填行进入明确终局且所有真实 Shipment 已回填后才能生成 `is_final=true` 最终版；同一输入快照重放由幂等机制返回已有版本。

**Q34 履约方文件隔离与模板归属（已定，Q48 澄清回传原子性）**：发给履约方的 FulfillmentExport 与履约方返回的 ProviderTrackingBatch 都必须且只能归属一个 FulfillmentProvider；京东一份，每个第三方各自一份，任何履约方文件都禁止混入其他履约方订单行。京东侧使用京东官方模板；第三方没有外部模板约束，统一使用系统自定义的简洁模板，覆盖批次/订单行定位以及实际发货数量、快递公司、运单号、发货时间、异常原因等主要快递信息，精确列契约由 P0 Excel 票定稿。`import_batches` 增加 `batch_type = SOURCE_ORDER / PROVIDER_TRACKING`：来源订单批次要求 `source_channel`，履约方回传批次要求 `fulfillment_provider_id`，以 CHECK 保证类型与归属字段一致；ProviderTrackingBatch 整批结构校验、整批单事务接收，结构或关联校验失败时整批不落业务事实，但合法的 PARTIAL/FAILED 业务结果不属于校验失败。某履约方在该来源导入批次中的应回传行全部齐全后，自动触发一版 Q33 SourceReturnExport；最终来源回填仍按彩食鲜/聚福宝/飞象各自原格式生成，可汇集不同履约方已完成的结果。

**Q35 来源订单逐行容错与统一复核队列（已定）**：`SOURCE_ORDER` 导入批次整文件与全部原始行先落 `import_batches` / `raw_import_rows` 留痕，但业务转换按订单行容错：有效行继续创建 CanonicalOrder/OrderLine 并进入后续履约，缺客户、缺 SKU 映射、字段非法等问题行进入 NEED_REVIEW，不因单行问题阻塞同文件其他有效行；同一多行订单也按已定行级独立推进。`import_batches.status` 使用 RECEIVED / PROCESSING / COMPLETED / COMPLETED_WITH_REVIEW / FAILED，`raw_import_rows.status` 使用 RECEIVED / ACCEPTED / NEED_REVIEW / REJECTED，并保存稳定错误码与详情。新增 `review_cases` 统一承载 CUSTOMER_MATCH / SKU_MATCH / IMPORT_VALIDATION / COD_AMOUNT / QUANTITY_SCALE / MANUAL_INTERVENTION 等复核事项，关联 `order_id` 与可选 `order_line_id`；同一主体+原因只允许一条 OPEN case，人工解决时与业务修正、OrderEvent、OrderVersion、Audit Log 单事务提交。该逐行容错只适用于来源订单导入；Q34 ProviderTrackingBatch 仍为整批全有或全无。

**Q36 缺少来源订单标识时的行分组（已定）**：来源模板提供主单号/子单号时，使用明确的 SourceOrderReference 识别 CanonicalOrder；该编号不与内部 `orders.id/order_no`、京东 `isv出库单号` 或 Tracking number 混用。来源编号为空或模板没有该列时，只在同一个 import batch 的同一个 Sheet 内，将**连续出现且 Receiver 姓名、电话、完整地址规范化后完全相同**的商品行归为一个 CanonicalOrder；相同收货信息在后续非连续位置再次出现时默认开启新订单，绝不跨 Sheet、跨文件自动合并。系统为该连续块生成 `source_ref_kind=SYNTHETIC` 的确定性来源标识，明确编号则为 `PROVIDED`；同一内部订单即使含多个 FulfillmentProvider，也只是在履约导出时按履约方拆文件，不拆回多个 CanonicalOrder。

**Q37 新批次与显式导入修订（已定）**：上传来源 Excel 时必须明确选择 NEW 或 REVISION；REVISION 必须指定 `parent_import_batch_id`，禁止依据文件名、收货信息或内容相似度猜测修订关系。`import_batches` 保存 `content_sha256`、`import_mode`、`parent_import_batch_id`、`revision_no` 与修订原因；REVISION 必须与父批次的 `batch_type`、SourceChannel/FulfillmentProvider 和模板族一致，形成不可覆盖、不可删除的版本链。完全相同的文件内容重传由 hash + scope 幂等返回已有批次及结果，不创建新订单；内容有变化但上传者选择 NEW 时按新业务批次处理，选择 REVISION 时逐行关联并追加 OrderVersion/OrderEvent/Audit Log，原批次与原始行始终保留。系统不提供自动覆盖旧订单的隐式路径。

**Q38 履约导出后的修订截止线（已定）**：某 OrderLine 首次被写入已生成的 `fulfillment_export_items` 时即形成履约承诺，并在同一事务设置 `order_lines.fulfillment_committed_at`；从该时点起禁止原地修改其 SKU、履约方、数量、规格、单位、礼包组件等履约字段。任一订单行已承诺后，订单级 Receiver 姓名/电话/地址与结账信息也不得原地覆盖。后续 ImportRevision 若触碰这些冻结字段，创建 `REVISION_AFTER_EXPORT` ReviewCase 并进入 NEED_REVIEW，只能通过人工取消/补发或创建带 `orders.correction_of_order_id` 的纠正单处理；原 FulfillmentExport、CanonicalOrder、OrderLine 与外部指令快照永久保留。未进入任何 FulfillmentExport 的订单行仍可按 Q37 在单事务内正常修订。应用层校验为主，数据库约束触发器兜底防止绕过。

**Q39 我方库存部分发货、缺口采购与人工提醒（已定）**：一个 Fulfillment 可产生多个 Shipment。若使用我方管理库存的 OrderLine 请求 100、当前可用 80，系统不等待补齐：先按 80 生成该行的履约指令并在履约方回传后形成第一批 Shipment/Tracking，同时为缺口 20 自动创建 ProcurementTicket；采购成功后仍由原 FulfillmentProvider 对剩余 20 生成第二批履约指令与 Shipment/Tracking，禁止改换履约方。该库存判断与自动采购不适用于第三方自有库存。`fulfillments.shipping_progress` 按累计实发量派生：0 = NOT_SHIPPED，0 < sum(shipped_quantity) < requested_quantity = PARTIALLY_SHIPPED，累计达到请求量 = SHIPPED；累计实发禁止超过请求量。存在缺口采购时 `order_lines.processing_stage=PROCUREMENT_IN_PROGRESS`，第一批已发事实仍由 ShippingProgress/Shipment 表达，避免组合状态爆炸。首次识别部分履约时新增 `operational_alerts(type=PARTIAL_FULFILLMENT, severity=YELLOW)`，不阻断发货与采购，但必须展示请求量、首批量、缺口量、履约方和采购工单，并支持人工 ACKNOWLEDGED；补货与剩余发货完成后标记 RESOLVED，提醒、确认人与时间永久保留。该业务变化及两批发货分别写 OrderEvent/OrderVersion/Audit Log。某履约方“返回齐”的判断以其应履约行已有合法终局结果，且所有真实 Shipment 均有有效 Tracking 为准，不能把首批 80 误判为全部完成。

**Q40 来源回填按 Shipment 展开（历史决定；自动展开已被 Q51 撤销）**：原决定要求一个原始商品行出现多个 Shipment 时按 Shipment 复制来源行。后续真实平台格式确认后，自动复制与全部回传部分由 Q51 取代；Shipment/Tracking 的内部完整血缘与确定性 `shipment_sequence` 仍保留。

**Q41 采购部分成功、失败与剩余量取消（已定，Q45 细化明细）**：采购工单使用 `procurement_tickets` + `procurement_ticket_items` 表达；普通 SKU 工单也必须有一条明细，定制礼包则按缺货组件分别建明细。回执使用只追加的 `procurement_receipts` + `procurement_receipt_items`：一张 ProcurementTicket 可按时间接收多个 SUCCESS/PARTIAL/FAILED 回执，回执头保存结果、`expected_ship_time`、来源引用与审计信息，回执明细逐工单明细保存本次 `available_quantity`。每条 `procurement_ticket_items.fulfilled_quantity` / `remaining_quantity` 从回执明细累计，并受 `0 <= fulfilled <= requested` 约束；工单总体结果由所有明细派生。PARTIAL 回执取得可履约数量后仍由原 FulfillmentProvider 继续生成履约指令和 Shipment；定制礼包只有形成新的完整礼包份数时才可继续发货，剩余缺口保持 `PROCUREMENT_IN_PROGRESS` 与黄色提醒并继续接收后续回执。FAILED 不回滚已有 Shipment，也不自动重试；对应订单行进入 EXCEPTION、ProcessingHealth=RED，创建 `PROCUREMENT_FAILED` ReviewCase 与红色 OperationalAlert，人工只能选择重新采购或取消剩余未发量。人工取消时写 `fulfillments.cancelled_quantity`，数量守恒为 `requested_quantity = cumulative_shipped_quantity + cancelled_quantity`，并将 `fulfillments.outcome` 置 PARTIALLY_FULFILLED（已有实发）或 CANCELLED（实发为 0）；只有达到该数量守恒且所有已有 Shipment 均有 Tracking 后，才允许生成最终 SourceReturnExport。重试、取消、回执和结果转移均单事务写 OrderEvent/OrderVersion/Audit Log。

**Q42 部分履约的来源最终回填（已定；多 Shipment 以 Q51 为准）**：仅当一个来源行最终只有一个真实 Shipment 时，本条允许在来源已有字段中明确写出未全部发完及剩余取消量，并生成可审计的最终 SourceReturnExport；禁止伪造无运单的“取消 Shipment”。一个来源行首批不能覆盖全部请求量或实际出现两个及以上 Shipment 时，一律以 Q51 为准：只自动回传最早关联 Shipment、不复制来源行、不生成首批-only `is_final=true` 文件，后续由 ReviewCase 人工跟进。若单 Shipment 来源格式仍无法表达少发/取消原因，则创建 `SOURCE_FORMAT_CANNOT_EXPRESS_PARTIAL` ReviewCase，禁止生成看似全部完成的文件；真实 Shipment、取消量和历史版本不受影响。

**Q43 履约方独立运单 SLA（已定）**：`fulfillment_providers` 保存可配置的 `tracking_sla_minutes > 0`；每次 FulfillmentExport 生成时按当时配置快照 `tracking_due_at`，后续修改履约方 SLA 不追溯改变既有导出。未返回齐且当前时间未超过 due_at 时 ProcessingHealth=YELLOW；超过后创建幂等的 `operational_alerts(type=TRACKING_OVERDUE, severity=RED)` 并把聚合健康度升为 RED，但不自动取消、重试、改换履约方或改变 ShippingProgress。全部应履约量及 Tracking 返回齐后自动 RESOLVED，保留超时和人工确认历史。由只负责 SLA 检测的定时任务或等价查询扫描 `tracking_due_at` 索引；它只生成提醒，不作为订单推进机制。

**Q44 Mock DemoScenario 与业务闭环强隔离（已定）**：Mock 演示单独建模为 DemoScenario，不能作为 CanonicalOrder 内部闭环或 P0 Excel 验收。`orders.data_scope` 使用 BUSINESS / DEMO；BUSINESS 订单必须有真实 SourceChannel/import lineage 并走文件/真实回执阶段，DEMO 订单只可由独立 `/demo/v1/scenarios` 入口和 `demo_runs` 创建，使用 Mock Adapter 同步生成 Timeline。默认订单查询、工作队列、ReviewCase、OperationalAlert、履约/来源 Excel、业务 analytics 与 Metabase 全部强制排除 DEMO；演示页只查 DEMO，并显示明确 Mock 标识。`POST /internal/v1/orders` 不再供演示页使用，只为未来真实内部/LangBot 接入保留。DemoScenario 可复用同一领域服务和状态机代码，但不得调用真实履约方、生成可交付业务文件、改写业务客户/SKU 映射或计入 P0 闭环测试；Audit Log 和 OrderEvent 必须带 data_scope。新增 `demo_runs` 显式记录 scenario_code、关联 demo order、运行结果和耗时。地图“创建即跑完全程”仅指此隔离路径，业务 Excel CanonicalOrder 按 Q25–Q43 异步推进。

**Q45 定制礼包按完整份数合箱履约（已定，术语已澄清）**：礼包不涉及加工或额外组装，只是把清单中的若干普通商品完整配齐并同盒发货。`CUSTOM_BUNDLE` 的 `order_lines.requested_quantity` 表示完整礼包份数，必须为正整数；组件自身数量仍使用 Q28 的 `NUMERIC(18,3)`。对我方管理库存，某时点可发礼包数按所有组件计算：`min(floor(组件可用量 / quantity_per_bundle))`；第三方自有库存不由本系统预判。首次和后续 Shipment 都只能记录完整礼包份数，散件、缺件或拆开独立发货的组件不得计入 `shipped_quantity`。Fulfillment 的请求量、累计实发量、取消量及 ShippingProgress 均以礼包份数计；生成 FulfillmentExport 时，再把本批礼包份数乘以各组件 `quantity_per_bundle`，展开为带同一礼包分组标识的组件行，指示履约方同盒发出；这不是一种需要额外能力判断的“组装服务”。我方库存缺货采购按“完成剩余礼包所缺组件”生成 `procurement_ticket_items`，采购回执按组件写 `procurement_receipt_items`；不同组件分批到货时，只有共同形成新的完整礼包份数后才可生成下一版履约指令。同一礼包中的组件不得作为互不关联的独立 Shipment/Tracking 回传；来源回填仍以礼包 Shipment 为单位展开，不把组件行伪装成来源订单行。礼包份数守恒、组件展开量、采购累计量、履约导出和阶段转移必须在事务中校验并写 OrderEvent/OrderVersion/Audit Log。

**Q46 P0 不做库存预占或锁单（已定，Q47 收窄库存范围）**：`provider_stock_snapshots` 只是我方管理库存的观测快照，P0 不建设库存预占、分配或锁单表，也不承诺已齐备但尚未发出的组件仍会保留。对我方库存，每次生成 FulfillmentExport 都是一笔独立原子操作：在同一数据库事务中锁定待推进的 Fulfillment/OrderLine，读取一组一致的最新库存快照，按尚未履约量重新计算本批可发数量（礼包按 Q45 重新计算完整份数），然后一次性写入导出头、导出明细、所采用的库存快照引用、阶段、事件、版本和审计；任一步失败则本批不成立。该事务只防止本系统重复生成同一批，不锁定仓库真实库存。后续批次不得沿用上次“剩余库存仍可用”的假设，必须重新判断全部我方 SKU/礼包组件；出现新缺口时追加或修订采购明细并保持黄色提醒。实际出库结果仍以履约方回传为准。

**Q47 第三方库存不归本系统管理（已定）**：系统维护第三方专属 SKU、履约方映射、发货指令和实际回传结果，但不采集、保存、预占或判断第三方自有库存数量。第三方 FulfillmentExport 按待履约请求生成，不要求存在 `provider_stock_snapshots`；第三方返回的实际发货数量、运单或异常是本系统唯一接收的履约事实。第三方短发时保存真实 Shipment/Tracking 与剩余量，FAILED 时保存失败结果；两者均创建 `THIRD_PARTY_FULFILLMENT_EXCEPTION` ReviewCase/OperationalAlert 交人工与第三方协调，不自动创建我方 ProcurementTicket，也不改写我方库存。

**Q48 履约方回传批次允许混合业务结果（已定）**：同一 ProviderTrackingBatch 可以同时包含 SHIPPED、PARTIAL、FAILED 行。原子接收的含义是：先校验整份文件的模板、履约方、批次关联、行定位、数量守恒及各结果的条件必填字段；任一结构或关联错误则整批回滚。若全部校验通过，则在一个事务内逐行落对应业务结果：SHIPPED 创建 Shipment/Tracking，PARTIAL 保存真实实发并处理剩余量，FAILED 创建异常/复核；合法的 PARTIAL/FAILED 不是整批导入失败。批次、所有业务事实、阶段、事件、版本和审计要么全部提交，要么全部不提交。

**Q49 P0 完成不等待签收或 SDK 回调（已定；Q51 增加人工完成例外）**：当前尚未接入京东 SDK 的物流回调，普通自动路径只闭环到“有效实发结果与运单已取得，并已生成最终来源回填 Excel”。满足该条件时 `order_lines.processing_stage=COMPLETED`，即使物流尚未签收；`shipments.status` 在 P0 最终可保持 SHIPPED。Q51 多 Shipment 场景不要求人工再上传一份最终文件，以 ReviewCase 的人工确认作为来源平台后续处理证据。DELIVERED/签收轨迹属于未来接入京东 SDK 或其他物流接口后的独立扩展，不得作为当前 P0 完成条件，也不得伪造回调或自动推进。

**Q50 同地址默认合并为一个出库单与运单（已定）**：在同一 CanonicalOrder、同一 FulfillmentProvider、同一 Receiver 地址快照和同一发货批次内，普通商品行与礼包组件默认合并为一个 Shipment，生成一个 `outbound_order_no`，并默认对应一个 Tracking；不同 FulfillmentProvider 即使地址相同也必须拆开。`shipments` 保存 provider/outbound_order_no/receiver snapshot/status，`shipment_items` 逐 Fulfillment 保存 instructed_quantity 与实际 `shipped_quantity`，`trackings.shipment_id` 在 P0 自动路径唯一。FulfillmentExport 中属于该 Shipment 的多行共享出库单号，履约方回传一个快递公司和运单号后覆盖该 Shipment 内所有来源订单行；SourceReturnExport 对这些原始行分别回填同一运单号。若缺货产生后续发货批次，必须新建 Shipment、新出库单号和新运单，不复用首批编号。P0 若收到同一出库单号对应多个互相冲突的运单，禁止猜测或覆盖，创建 `MULTIPLE_TRACKINGS_FOR_OUTBOUND` ReviewCase；未来确有稳定的一单多包裹格式时再扩展自动解析。

**Q51 多 Shipment 来源回填与人工跟进（已定；取代 Q40 自动展开）**：彩食鲜、聚福宝、飞象统一处理。一个原始商品行的首个 Shipment 已知不能覆盖全部请求量，或后来实际出现第二个 Shipment 时，自动 SourceReturnExport 只关联并写出该 OrderLine/Fulfillment 所有关联 Shipment 中 `shipment_sequence` 最小的首批快递公司与运单号；禁止硬编码全局 sequence=1、复制来源行、拼接多个运单或用后续运单覆盖首批。系统创建 `review_cases(reason_code=MULTI_SHIPMENT_SOURCE_FOLLOWUP)`；采购或后续履约仍进行时保留 `PROCUREMENT_IN_PROGRESS`/`WAITING_PROVIDER`，由开放 ReviewCase 表达人工责任，不能覆盖掉真实处理阶段。所有真实 Shipment 均有 Tracking 且 Fulfillment 达到终局后，OrderLine 转 NEED_REVIEW 等待人工来源平台跟进。管理后台必须展示完整 Shipment 列表。人工完成后续处理后执行“已完成后续回传”并填写备注；只有 Fulfillment 已终局且所有真实 Shipment 均有 Tracking 时才允许同一事务写 `review_cases.resolution/resolved_by/resolved_at`、关闭 ReviewCase、推进至 COMPLETED，并写 `MANUAL_SOURCE_FOLLOWUP_COMPLETED` OrderEvent/OrderVersion/Audit Log。无需再次上传文件或伪造包含后续运单的 SourceReturnExport；该人工路径不得生成首批-only `is_final=true` 文件。现有表可承载，本决定不新增表。

**Q52 来源平台快递公司映射（已定）**：Tracking 保存内部标准 `logistics_company_code/name`，来源回填前必须命中对应 SourceChannel 的显式承运商映射。P0 不新增目录表，映射保存在现有 `connector_configs.config.carrier_mappings` 并通过应用层接口维护、写 Audit Log；首批内置京东物流映射：CAISHIXIAN=`JD`、JUFUBAO=`京东物流`、FEIXIANG=`京东物流`。未维护承运商不得按名称相似度或运单号前缀猜测，创建 `CARRIER_MAPPING` ReviewCase 并把关联 OrderLine 置 NEED_REVIEW；人工确认后写入渠道配置，后续复用。

**Q53 来源 SKU 数量乘数与换算快照（已定）**：`source_channel_skus.quantity_multiplier NUMERIC(18,3)` 保存来源件到 Canonical 履约数量的正数乘数；允许为空以表达映射尚未补全，但空、0 或非法时只能进入 `MAPPING_MULTIPLIER / NEED_REVIEW`，不得默认1。普通 OrderLine 保存 `source_quantity_snapshot` 与 `mapping_multiplier_snapshot`，两者同时为空或同时存在；存在时数据库约束 `requested_quantity = source_quantity_snapshot × mapping_multiplier_snapshot`，保证映射后修改不会改写历史换算。CustomBundle 仍按当单组件快照计算，不使用静态 SKU 乘数冒充礼包 BOM。

**Q54 API 契约派生的回传血缘与系统出库单号（已定）**：ProviderTrackingBatch 必须通过 `source_fulfillment_export_id` 显式关联原 FulfillmentExport，provider 必须一致；Revision 继续指向同一原导出，文件 hash 幂等范围也包含该导出，供下载状态从“等待回传”可靠转为“已回传”。`outbound_order_no`/京东 `isv出库单号`由系统在 Shipment/FulfillmentExport 创建时生成，使用上海业务日 `yyyyMMdd` 加四位当日原子流水；同一 Shipment 共用，幂等重放/重新下载不变，后续分批或纠正单取新号。数据库用 `outbound_number_counters` + `next_outbound_order_no()` 原子分配，禁止 `MAX + 1`；当日 9999 个耗尽时显式失败。

**Q55 API 并发版本与 Connector 双轴（已定）**：API 中修改既有事实的命令携带 `expected_version`，因此 Orders/Fulfillments 之外，Customer、Category、Product、SKU、来源/履约方映射、FulfillmentProvider、ProcurementTicket、OperationalAlert 和 ConnectorConfig 也保存 `lock_version`，应用用条件 UPDATE 并递增；ReviewCase 继续使用已有 `resolution_version`。Connector 的 `mode=MOCK/REAL` 只表达在线接口使用真实或模拟 Client，新增 `transport_mode=EXCEL/API` 表达文件或在线接口，两者禁止混为一个枚举；当前三平台是 EXCEL，`mode` 不参与文件处理并默认 MOCK，真实 API 等凭据/文档后才启用 REAL+API。幂等 scope 按 Q3 改为可扩展的服务端用例码，以覆盖本 API 契约全部写操作。

## Assets

- `docs/schema.md`：38 张业务表、核心 ER、状态维度、事务边界、不变量和视图口径说明。
- `docs/schema.sql`：面向 PostgreSQL 16/Flyway V1 的可执行 DDL；包含 38 表、3 个 analytics 视图、1 个操作视图及数据库防御触发器。
- `docs/schema-smoke.sql`：关键约束和分析口径的可重复冒烟测试。
- `docs/research/postgres-enum-vs-check.md`：native enum 与 VARCHAR+CHECK 裁决的事实核验。
- `docs/state-machine.md`：已同步后续 Schema 决策的当前状态机说明。

## Validation

- PostgreSQL 16 Alpine 空库执行：`psql -v ON_ERROR_STOP=1 -f docs/schema.sql` 成功提交。
- 实际对象计数：38 张 `app` 基础表、4 个 `app/analytics` 视图、67 个非内部触发器；所有外键列均有可用前导索引。
- `docs/schema-smoke.sql` 成功执行并回滚；覆盖第三方库存/采购拒绝、错误 Revision/导入血缘拒绝、采购 SKU/组件血缘与只经回执累计、跨 provider/非整份礼包拒绝、重复待出库批次拒绝、错误 Receiver、跨订单履约导出/来源回填拒绝、Demo 业务隔离、京东金额必填 0、超发拒绝、Tracking 冲突、最终回填终局/等待项/全量取消、履约承诺后字段冻结、聚合数量与三类视图排除 Demo。
- `git diff --check` 通过。

## Review

- 审查固定点：`HEAD` = `a056878663ecad66e92edb049d4154fba39b0619`；范围使用 `git diff HEAD`（本阶段为未提交工作区变更，排除既有 `wayfinder/tickets/api-contract-design.md` 与 `prototype/`）。
- Standards 复审：PASS。会导致错单、串单、重复出库、错误采购或错误回填的硬问题均已修复并由 PG16 反例覆盖。
- Spec 复审：PASS。Q1–Q55 与最终 DDL/说明/冒烟测试一致。
- 非阻断 P3：大量 CHECK/UNIQUE 未显式命名；按“减少非必要过度设计”原则不在 V1 批量改名，后续实际迁移需要定点修改约束时再命名。
