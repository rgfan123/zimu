# 03 — 渠道订单中礼包的形态与识别

Type: research
Status: closed
Blocked by: None — can start immediately

Label: wayfinder:research

## Question

三平台（彩食鲜/聚福宝/飞象）Excel 订单与企业微信消息里，礼包长什么样、现有识别链路怎么扩展才能命中静态礼包主数据？

已定方向（grilling）：**全渠道识别**——Excel 与企微都按名称/别名命中静态礼包；未命中进 NEED_REVIEW。

需要调研：
- 现有样本文件（`待发货订单-测试/` 下的彩食鲜/聚福宝/飞象/中汇待发货订单）中商品行的实际形态：有没有礼包行？商品名称列长什么样（含不含「礼包」字样、带不带规格）；订单行有没有条码列；
- 现有 Excel 导入链路（`docs/excel-closed-loop-spec.md`、`docs/api-contract.md`）中商品行 → SKU 映射的机制（`source_channel_skus` 映射表、名称/编码匹配），静态礼包命中是插在映射之前还是作为映射的一种；
- 企微链路（`CONTEXT.md` 的 OrderDraft/MessageIntent、`docs/` 中消息解释相关）中商品名称如何映射 SKU 候选——礼包名称如何进候选（名称精确/包含匹配、别名）；
- 渠道侧礼包的「来源包装乘数」概念是否适用（礼包在渠道卖一份=一份礼包，乘数=1？还是渠道也有组合销售）；
- 输出：渠道识别改造方案（映射表扩展、匹配规则、NEED_REVIEW 分支），并指出哪些现成机制可直接复用。

## Assets

（research 子代理产出：`.scratch/static-bundle-bom/research/03-channel-recognition.md`）

## Resolution

1. **样本事实**：四份真实样本（彩食鲜/聚福宝/飞象/中汇待发货订单）里**没有任何礼包订单行**（唯一「礼包」是聚福宝表头「礼包名称」列名，数据为空）——礼包识别是前瞻能力，输入差异全部来自礼包主数据文件（（BJ）后缀、全半角括号、空白、重量规格、组合/套餐/礼盒关键词）；
2. **映射键**：彩食鲜/中汇用「商品编号」、聚福宝用「商品ID」（另有真实 EAN-13 条码可直连礼包主数据条码）、飞象用「商品ID」；名称/别名匹配是 ref 之外兜底，企微以名称为主路径；
3. **识别顺序：先礼包、后 SKU**——渠道显式礼包映射（ref/条码精确，新建 `source_channel_bundles` 映射表）→ 名称关键词闸门（礼包|礼盒|组合|套餐，配置化）+ 规范化精确命中（NFKC、去空白、剥（BJ）后缀、保留重量规格；唯一→礼包，零/多→NEED_REVIEW）→ 不含关键词走现状 SKU 映射（零回归）；
4. 别名挂礼包主数据（`bundle_aliases`，不建渠道维度别名表）；礼包乘数一期恒 1（保留列、正整数约束、缺失进复核）；
5. 全部 NEED_REVIEW 分支复用现有 ReviewCase 机制，新增 `BUNDLE_MATCH` 原因码 + `resolve-bundle` 命令（镜像 `resolve-sku`）；企微草稿扩展 `bundle_candidates`（缺 EMG 组件在复核 detail 展示，成单后行停 NEED_REVIEW 不阻塞同单其他行）；
6. 识别代码落点：新增共享接缝 `BundleRecognitionService`，Excel 在 `SourceImportService.canonical()` 前调、企微在 `WecomOrderDraftFactory.skuCandidates` 旁新增 `bundleCandidates`；`OrderCreateService.createBundleLine` 组件解析/快照/同 provider/缺映射复核**全部原样复用**（静态礼包只改变「组件从主数据来」这一件事）。
