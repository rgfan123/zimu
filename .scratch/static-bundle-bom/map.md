---
label: wayfinder:map
title: 静态礼包 BOM 主数据：实现方案（调研+决策定稿）
tracker: local-markdown
---

# 静态礼包 BOM 主数据：实现方案（调研+决策定稿）

## Destination

为订单履约中台新增**静态礼包 BOM 主数据**能力并产出可执行的实现方案：礼包（一个礼包对应多个 SKU）作为主数据实体入库（礼包 = Product + BOM 组件清单，组件引用现有 internal_skus），三平台 Excel 与企业微信订单按名称命中礼包后展开组件履约，PMS 上架支持礼包形态。**推翻**旧决策「礼包=当单定制、SKU 主数据不做静态 BOM」（见 `wayfinder/tickets/product-bundle-and-pack-mapping.md` 与 `docs/excel-closed-loop-spec.md §6.2`）——静态礼包主数据成为新基线，当单定制礼包保留但不在本次改造范围。

本 effort 产出的是**实现方案**（schema 变更、识别/展开改造、导入链路、PMS 上架方案，全部决策定稿），不是代码；地图完成即交后续实现票执行。

## Notes

**领域**：B 端生鲜食材订单履约与仓储物流；渠道：彩食鲜 / 聚福宝 / 飞象 / 企业微信 / 中汇 PMS 上架。
**固定输入**：
- `大者国风上架品（内容详情）-202605更新(1).xlsx`（36 个礼包：商品条码、名称、税率 9%、大者结算成本；165 个组件行「内配名称 + 礼包数量 + 京东商品编号 EMG…」，其中仅 70 个组件带京东编号）；
- `京东商品编号.xlsx` Sheet2（旧礼包 BOM，23 个礼包，格式错乱——单行内嵌两个礼包，解析需谨慎）；
- 现有领域词汇 `CONTEXT.md`、现有 schema `docs/schema.md` / `docs/schema.sql`、API 契约 `docs/api-contract.md`、PMS 上架文档 `pms_openapi.md`。
**技能**：research（AFK 调研票）、grilling（HITL 决策票）、domain-modeling（词汇变化时更新 `CONTEXT.md`）。
**HITL 原则**：时间类型、索引等常规工程默认由 Agent 直接按稳健方案裁决并记录；只把改变业务行为、人工流程或交付范围的问题交给用户。

**建图期已定决策**（grilling 结论，不再重开）：
- 静态礼包 = **Product + BOM**：新建礼包主数据（商品族 + 组件清单），组件引用现有 `internal_skus` 并带数量；**礼包本身不创建 internal_sku、不单独计库存**；
- 订单行仍用 `CUSTOM_BUNDLE` 类型但**引用主数据礼包**；识别命中礼包时**下单快照 BOM** 到 `order_line_components`，主数据后续修改不影响历史订单；
- 数据来源：**新旧两份文件合并**（大者国风上架品 36 + 京东商品编号 Sheet2 23），合并规则待 research 票定稿；
- 订单入口：**全渠道识别**——三平台 Excel 与企微消息均按名称/别名命中静态礼包，命中后展开组件履约；
- 当单定制礼包：**保留不动**，本次只做静态的（并存）；
- 本次终点：**实现方案**（调研+决策定稿），不写实现代码。

## Decisions so far

<!-- 每张已关闭票一行：标题（链接）+ 一句话结论。 -->

- [01 — 新旧礼包源文件合并规则](issues/01-bundle-source-merge.md) — 两份源合并为 **49 个礼包**（11 重叠 + 25 仅新 + 11 仅旧 + 2 右块命名礼包）、336 组件行、319 带 EMG；条码为主键、新文件优先、旧文件按名称回填 EMG、仅旧礼包生成 `BUNDLE-xx` 标识、16 个缺 EMG 导入待补不阻断；权威清单与可执行合并规则见 research/01。
- [02 — 静态礼包主数据 schema 设计](issues/02-bundle-schema.md) — 新建 `product_bundles` + `bundle_items` + `bundle_aliases` 三表，`order_lines` 加 `bundle_id` 列，不引入 bundle_versions（下单快照隔离），组件缺 EMG 用 NULL；净增量 +3 表/+1 列/+3~4 触发器/+4 索引，Flyway V36 落地；完整 DDL 草案见 research/02。
- [03 — 渠道订单中礼包的形态与识别](issues/03-channel-recognition.md) — 真实样本无礼包行（前瞻能力）；识别顺序**先礼包后 SKU**：新建 `source_channel_bundles` 显式映射 + `bundle_aliases` 名称/别名规范化精确命中（关键词闸门），零/多命中进 NEED_REVIEW；礼包乘数一期恒 1；`createBundleLine` 组件展开全部复用；新增 `BUNDLE_MATCH` 原因码与 `resolve-bundle` 命令。
- [05 — 礼包 PMS 上架方案](issues/05-pms-bundle-upload.md) — PMS 无结构化组合品（`AttrAndStock`=多规格变体，非组件清单）；礼包按**独立 PMS 商品**上架（goodsItem=goodsBar=礼包条码、supplyPrice=大者结算成本、售价人工必填、组件清单进 details HTML），新增 `/bundle-uploads` 入口，先按条码查重再创建；9 项待向 PMS 人工确认（含非 GS1 条码校验风险）。

- [04 — 静态礼包命中后的履约展开边界](issues/04-bundle-expansion-boundary.md) — 五决策定稿：`order_lines.bundle_id` 区分静态/定制（line_type 不加值）；**订单确认时快照 BOM**（识别只记引用）；履约导出/份数校验/同盒/采购/回填**全部复用零新分支**；缺 EMG 组件行级 NEED_REVIEW（不阻断同单）；分析最小方案（`v_product_daily` 加 bundle_id/bundle_name 两列，暂不建 v_bundle_daily）。

## Not yet specified

<!-- 雾区：方向内但还无法精确成票的问题；前沿推进后逐块毕业为票。 -->

- 企微消息中礼包名称的识别细节（自由文本 vs 结构化）与草稿候选展示——已由 03 给出方案框架，实现细节随后续构建票定；
- 礼包主数据导入链路（把 49 礼包清单落库的迁移/脚本形态）——04 已定稿、实现前成票；
- 前端商品档案/礼包管理页面形态——实现阶段定。

## Out of scope

<!-- 已明确排除在本 effort 之外；永不毕业。 -->

- **当单定制礼包（CustomBundle）改造**——保留现状，本次只加静态礼包，并存；
- 京东真实登录与凭据接入、三平台真实 API 对接（沿用现有 Connector/mock 边界）；
- 礼包在京东/PMS 侧的销售运营（定价、库存预占、促销）——只做到上架方案层面；
- 重量模型与净重口径（沿用旧决策：不增加）；
- 生产部署与运维。
