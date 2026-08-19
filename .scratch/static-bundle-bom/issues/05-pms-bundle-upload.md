# 05 — 礼包 PMS 上架方案

Type: research
Status: closed
Blocked by: None — can start immediately

Label: wayfinder:research

## Question

静态礼包如何通过现有「商品档案 → 中汇 PMS 批量上传」链路（`ZhonghuiPmsBatchUploadService`，目前每个 SKU 对应一个 PMS 商品）上架到中汇 PMS？

需要调研：
- 中汇 PMS `CreateGoodsRequest`（`pms_openapi.md`）是否支持组合品/礼包：`AttrAndStock` 数组字段是什么、能否表达多 SKU 组件；`goodsItem`（商品编码）能否用礼包条码；内配清单在哪个字段表达（details HTML？desc？）；
- 现有批量上传服务如何扩展：礼包无 internal_sku，需要新的入口（按礼包 ID 上传）与字段映射（goodsName=礼包名、goodsBar=礼包条码、goodsTax=9、goodsPrice/supplyPrice 用什么——礼包有「大者结算成本」，售价呢）；
- 礼包上架时的图片/详情：复用 Product 主图？详情里是否要渲染组件清单；
- 输出：PMS 礼包上架方案（接口/字段映射/约束），并标注需要向 PMS 侧人工确认的未知项（如 AttrAndStock 的业务含义、组合品是否被 PMS 审核支持）。

## Assets

（research 子代理产出：`.scratch/static-bundle-bom/research/05-pms-upload.md`）

## Resolution

1. **PMS 不能结构化表达「一个商品=多个 SKU 组件」**：`CreateGoodsRequest` 是扁平商品模型（顶层单份价格/库存，无组合/BOM 字段）；`AttrAndStock`（默认 `[]`、元素结构未定义）解读为多规格属性+库存变体模型，不是跨 SKU 组件清单，礼包场景保持 `[]`；`goodsPurchaseMultiplier`/`jdSkuId` 均与组合无关；
2. **推荐方案：礼包按独立 PMS 商品上架**（组合品的替代表达）——`goodsName`=礼包名、`goodsItem`=goodsBar=礼包条码（36 个条码已验证全部唯一、与现有 SKU 的 goodsItem 命名空间不冲突）、`goodsTax`=9、`supplyPrice`=大者结算成本、`goodsPrice`=上传时人工必填（源文件无售价，禁止静默用成本）、`saleUnit`=件、组件清单渲染进 `details` HTML + `desc`；
3. **新入口**：`POST /api/v1/zhonghui-pms/bundle-uploads`（按 bundle_ids），复用登录/品牌/资质/图片上传/创建/列表校验链路；批次表 V36 扩 `source_type`+`bundle_id`；
4. **幂等**：PMS 创建无幂等 → 先按 `goodsItem`=条码 `queryGoods` 查重再创建；
5. **需人工确认 9 项**（默认假设见 research §5）：`AttrAndStock` 真实含义、PMS 组合品类目/审核要求、`goodsItem` 取值规则、**`goodsBar` 是否校验 EAN-13（31/36 个 9250/9260 内部条码不通过校验位，被拒则回退 `goodsBar=""`）**、礼包库存语义（`goodsNum` 名义可售量 vs 组件库存脱节）、售价来源、组合品资质、details 审核可见性、`thirdId`/`limitAreaTempId` 规则。
