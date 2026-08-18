# 真实京东 SDK 业务切换

状态：Ready for tickets
日期：2026-08-17

## 目标

把当前业务流程从「导单 Excel 人工中转」切换为「真实京东 SDK 直连」：

```
彩食鲜 Excel 导入 → 内部统一化 (CanonicalOrder)
  → 京东 SDK addSoOrder 建真实出库单
  → SDK 取回运单号 + 快递公司标识
  → 生成彩食鲜格式回填表（单号与快递公司已自动回填）
```

## 当前实况（2026-08-17 实测）

- 导入链路已通：`待发货订单 (74).xlsx` → import_batch `COMPLETED` → 3 订单 / 3 履约 / 3 发货批次，SKU 映射全部命中。
- 走的是 P0 文件流：`SourceImportService.confirm()` 直接调 `ProviderFileService.generateForSourceBatch()` 生成京东导单 Excel，**没有任何分支路由到 SDK**。
- 京东 SDK 建单从未发生：`shipment_jd_outbounds` = 0、`trackings` = 0、`source_return_exports` = 0。
- `fulfillment_providers.config` 为空对象 `{}`。全代码库唯一写入点是 `SeedDataInitializer`，写的就是空 map；`PATCH /api/v1/fulfillment-providers/{id}` 的 DTO 不含 `config` 字段。**即 SDK 建单所需配置没有任何受支持的写入通道，这条路径从未真正可运行过。**
- 只读链路已实测可用：`client_mode=REAL`、`live_ready=true`，`querySellers` / `queryOwners` / `queryWarehouses` / `queryShops` 均返回 `business_code=1000`。

## 已确认的真实标识

| 配置键 | 值 | 来源 |
|---|---|---|
| `ownerNo` | `EBU4418056064528` | queryOwners（京诚乾元（北京）供应链管理有限公司）|
| `shopNo` | `ESP0020008943717` | queryShops |
| `erpShopNo` | `4418056064528` | queryShops |
| `salesPlatformSource` | `6` | queryShops.salesPlatformSourceNo |
| `warehouseNo` | `118085840` | queryWarehouses（石家庄冷链C仓1号库-CHN；沧州两仓已弃用）|
| `pin` | 环境变量 `JD_LOP_PIN` | 部署环境 |
| `townRequired` | `false`（暂定，待真实 addSoOrder 裁决） | 见下「乡镇必填策略」|

## 乡镇必填策略（townRequired）

证据混杂，不由本方单边判定：

- 反对必填：石家庄仓（唯一在用仓）自身地址 `town` 为 `null`；彩食鲜来源表无乡镇列。
- 支持必填：`queryWarehouseCoverages` 明确拒绝空 town（`2000 镇不能为空`）；沧州两仓地址均带乡镇。
- 不成立的依据：建单预览里「京东未要求时乡镇可留空」是系统复述自身策略（因 `townRequired` 未配置），**不是**京东侧要求，不得作为外部证据。

处置：初始配置为 `false`，由首次真实 `addSoOrder` 的京东响应裁决；被拒即翻为 `true`。该验证列为 05 的验收项。

## 待外部提供

- `sourceNo`、`carrierNo` —— 来自**京东物流开放平台（JDL）**，非京东开放平台（JOS）。依据：网关为 `api.jdl.com`，所有操作为 `Integratedsupplychain*`（一体化供应链 ISC）。已验证的 37 个只读接口中没有任何一个能查承运商或来源编码，说明二者是开通时由 JDL 分配的配置值，需向 JDL 对接人索取。
  - `sourceNo` 候选值 `0e9805498d594d47a1429b84f55ca0c6` 与部署环境的 `JD_LOP_APP_KEY` **逐字节相同**，即 API 签名凭据本身。可能京东确以 appKey 作来源标识，也可能取错字段；填错将导致真实出库单来源错误，**必须由 JDL 书面确认后方可配置**，不得沿用推测。
  - `carrierNo` 无任何已授权只读接口可查证：`queryWarehouseCoverages` 对本账号所有地址（含仓库自身所在地 河北石家庄元氏县）均返回 `3000 查询无结果`，无法反推承运商。「JDL」「JD」一类字面量不符合京东承运商编码形态，不予采用。
- `customerCode` —— 京东侧 `queryCustomers` 返回 `totalNum: 0`，店铺 `customerCode` 为空串，即从未维护。决策：改为**客户级**字段，由本地客户档案维护并提供导入接入接口（见 02）。

## 干跑验证（2026-08-17，写门闩置 OFF）

将 9 项已知标识临时直写 `fulfillment_providers.config`（绕过审计的诊断手段，正式写入面见 01）后重跑预览，3 个 shipment 结果一致：

- 阻塞由 17 项降至 6 项，PASS 23 项。
- 清除的 9 项均为 config 类；`townRequired=false` 生效，乡镇转为 `OMITTED` 不再阻塞。
- 剩余 6 项与 ticket 划分严格对应：`customerInfo.customerCode`（02）、`receiverInfo.province/city/county/detailAddress`（04）、`cargoInfos[].planQuantity`（03）。
- 结论：01 的键集合正确，02/03/04 覆盖全部剩余阻塞，无第四类未知问题。

注意：`sourceNo` 与 `carrierNo` 当前仅通过「非空」校验，**预览通过不代表取值正确**；二者的真实裁决只能来自一次真实 `addSoOrder`。

## 建单前置阻塞（preview 实测，配置补齐前）

`GET /api/v1/shipments/{id}/jd-so-order-preview` 对 3 个 shipment 均返回 `submittable: false`，阻塞分三类：

1. 履约方配置缺 10 项京东标识 → 01、02
2. 收货地址四级结构化未人工确认（系统拒绝从自由文本猜测）→ 04
3. `planQuantity` 缺显式京东件数换算（单位非「件」时不默认为 1）→ 03

## 不变的边界

- Connector 禁止直接写业务表；文件 Adapter 与 SDK 必须调用同一应用层用例。
- CanonicalOrder 是长期事实源，导出文件只是版本化派生物。
- 真实京东写入受写门闩 + 授权操作人名单双重保护，不得为便利放宽。
- 结构化地址只接受人工确认，不得从自由文本自动采纳。

## 已知可复用

`ShipmentJdTrackingBackfillService` 已依赖 `ShipmentTrackingService`（写运单）、`CarrierPrefixMatcher`（识别快递公司标识）、`TrackingFileService`（生成来源回填文件）。SDK 取回运单后复用与文件流完全相同的用例，回填表产出无需另写。
