# 彩食鲜供应商平台抓包记录与接口契约（scc.freshfood.cn）

状态：Captured / 认证已确认（2026-08-18 第二次抓包）
日期：2026-08-17 / 2026-08-18 更新
来源：`data-local/scc.freshfood.cn.har`（微信接收，2026-08-14 15:15，799 KB）+ `~/Desktop/wapi.freshfood.cn_2026_08_18_10_21_04.har`（2026-08-18 新抓，2.5 MB，256 请求 / 76 接口，含登录）
抓包环境：Windows + Edge 148（UA 见下），页面 `https://scc.freshfood.cn` → API 域名 `wapi.freshfood.cn`
用途：彩食鲜 Connector 从「人工导表」升级为「API 自动拉取」的依据；接口契约存档

> ⚠️ 安全提醒：2026-08-18 抓包 HAR 含**明文账号密码**与有效 JWT（`login-token`），
> 请勿外传，密码建议尽快修改；HAR 副本不要提交到 git。

## 1. 抓包概况

第一份（08-14）92 条请求、6 个唯一接口，覆盖「导出任务」闭环；
第二份（08-18）256 条请求、76 个唯一接口，覆盖**登录 → 首页 → 订单 → 导出 → 下载**全流程，并确认认证方式。

核心链路是一条「导出任务」闭环：

```
POST /ucenter/login/scc                      登录 → 响应头返回新 login-token（2026-08-18 确认）
POST /scc/bbc/order/exportDeliverExcl        发起导出任务 → data = 任务 ID
GET  /task/task/my?taskType=...              轮询任务状态（页面每 ~5-8s 轮询 4 种 taskType）
GET  /task/file/download?url=<COS URL>       任务完成后代理下载生成的 xlsx
```

真实观测：08-14 有 09:32 / 13:10 / 14:14 / 15:12 四次导出任务；
08-18 那次：02:20:05 POST → 02:20:09 首轮轮询完成 → 02:20:22 下载（~17 秒闭环）。

## 2. 认证方式（已确认）

- 业务请求头（自定义，无 Bearer 前缀）：
  - `login-token: <JWT>` —— 认证令牌；**登录接口的响应头返回新值**（`POST /ucenter/login/scc` 后自动续期）
  - `supplier-code: <供应商代码>` —— 供应商维度，业务接口必带
- 供应商映射（本账号两个供应商）：
  - `20075684` = **河北净菜（北京）物流有限公司**（主供应商，日常业务用这个）
  - `20070589` = 河北净菜（北京）物流有限公司（基地）
- 登录请求体：`{"username":"<手机号>","password":"<密码>","businessCode":"fe-web-scc"}`
- 登录响应 data 含 `sessionId` / `supplierList` / `defaultSupplier` / `tenants` 等用户上下文

## 2. 接口契约

### 2.1 POST `https://wapi.freshfood.cn/scc/bbc/order/exportDeliverExcl`

发起「企业购导出待发货订单」任务。

请求体（application/json）：

```json
{"payTimeBegin":"2026-07-14","payTimeEnd":"2026-08-14","pageNum":1,"pageSize":10,"orderStatus":"3"}
```

| 字段 | 语义（推断） |
|---|---|
| `payTimeBegin` / `payTimeEnd` | 支付时间区间，`yyyy-MM-dd` |
| `orderStatus` | `"3"` = 待发货（导出文件名为「待发货订单.xlsx」） |
| `pageNum` / `pageSize` | 页面筛选参数回显，实际导出全量（待验证） |

响应：`{"code":200000,"message":"success","data":8361885}` — `data` 为任务 ID。

### 2.2 GET `https://wapi.freshfood.cn/task/task/my`

查询任务列表，Query 参数：

- `sysCode=TASK-SCHEDULING`（固定）
- `taskType` ∈ 四种，页面并行轮询：
  - `csx-b2b-supplier-schedule` — 供应商排期（待发货导出，本 HAR 唯一有数据的）
  - `csx-b2b-settle-schedul` — 结算
  - `csx-b2b-tms-schedule` — TMS 物流
  - `csx-b2b-scm-web-schedul` — SCM Web

响应 `data[]` 任务对象关键字段：

| 字段 | 样例值 | 语义（推断） |
|---|---|---|
| `id` | `8361885` | 任务 ID，与 2.1 的 `data` 一致 |
| `taskCode` | `EXPORT_E0091000000606189202608141512` | `EXPORT_E009` + userId + `yyyyMMddHHmm` |
| `taskName` | `企业购导出待发货订单` | |
| `taskStatus` | `2` | 2 = 完成（页面以 `progress=100` 为完成信号） |
| `totalProgress` / `currProgress` | `100` / `100` | 百分比 |
| `resultCode` | `200000` | 业务码，与 2.1 响应同构 |
| `taskResult` | `2` | 结果态（2 = 成功，待验证） |
| `taskMessage` | `success` | |
| `taskParam` | JSON 字符串 | 见 2.3，含请求回显 + 用户上下文 |
| `taskAttach` | JSON 字符串 `"[{\"name\":\"待发货订单.xlsx\",\"url\":\"<COS URL>\"}]"` | **文件下载地址（2026-08-18 实测：字符串，需 json.loads 后取 `[0].url`）** |
| `taskData` | `{"name":"...","status":0}` | 任务摘要 |

轮询判定：以 `taskStatus==2 && currProgress==totalProgress && resultCode==200000` 为完成；
失败/超时依据 `taskResult`/`taskMessage`（枚举未穷尽，需补抓失败样例）。

### 2.3 `taskParam` 用户上下文（含鉴权线索）

```json
{
  "schedulType": "EXPORT_E009",
  "shipperCode": "YHCSX",
  "shipperName": "永辉彩食鲜",
  "taskParam": {
    "request": {"payTimeEnd":"2026-08-14","orderStatus":3,"pageSize":10,"payTimeBegin":"2026-07-14","pageNum":1},
    "user": {
      "tenants": [{"isDefault":1,"tenantName":"永辉彩食鲜","tenantCode":"YHCSX","userId":1000000606189}],
      "masterSupplier": true,
      "superSupplier": false,
      "roles": "USER",
      "currentSupplier": {"supplierName":"河北净菜（北京）物流有限公司","supplierCode":"20075684"},
      "loginToken": "eyJhbGci...（JWT）"
    }
  }
}
```

- 平台认证为 **JWT**（`loginToken` 回显在任务参数里）；实际请求头为自定义 `login-token`（2026-08-18 确认，见第 2 节）。
- 当前账号：userId `1000000606189`，主供应商「河北净菜（北京）物流有限公司」`20075684`。

### 2.4 GET `https://wapi.freshfood.cn/task/file/download`

Query：`name=<文件名>&url=<预签名 COS URL>`。服务端代理转发，响应即 xlsx 二进制（`PK` 魔数）。

COS URL 模式：`https://csx-prd-1259250653.cos.ap-shanghai.myqcloud.com/prd/supplierschedule/excels/{YYYYMMDD}/{32位HEX}.xlsx`
（预签名、有时效，任务完成后需及时下载。）

## 3. 通用请求头（均来自 HAR）

```
login-token: <JWT>                 # 认证（业务接口必带，见第 2 节）
supplier-code: 20075684            # 供应商维度（业务接口必带；devops/登录接口不带）
Origin: https://scc.freshfood.cn
Referer: https://scc.freshfood.cn/
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/148.0.0.0 Safari/537.36 Edg/148.0.0.0
accept: application/json, */*
content-type: application/json   (仅 POST)
```

## 4. 复用方案

### 4.1 短期（推荐先做）：自动拉表器，替换人工导表

现状：每天人工登录后台 → 导出待发货订单 → 微信/上传系统 → `CaishixianConnector`（纯 Excel 模式）解析。

已落地：`scripts/caishixian_fetch_orders.py` —— 自动登录（`login-token` 从登录响应头续期）→ `POST exportDeliverExcl` → 轮询 `task/task/my` → `GET task/file/download` → 落 `data-local/`。
后续接入系统 ingest 目录后，复用现有 EXCEL 闭环（表头指纹见 `docs/excel-closed-loop-spec.md`），Connector 零改动。

### 4.2 中期：契约存档（本文档）作为 API 模式 Connector 起点

- `docs/prd-v0.1.md` 已规划 `transport_mode=API`；本文档补齐登录 + 导出闭环契约。
- 2026-08-18 抓包发现的**订单 JSON 接口**（API 模式关键素材，待细化契约）：
  - `POST /scc/bbc/order/orderList` —— 订单分页列表（JSON）【已实测通过，见 4.4】
  - `POST /scc/bbc/order/orderStatistics` / `homeStatistics` —— 订单统计
  - `POST /scc/bbc/order/homeStockWarn` —— 库存预警
  - `POST /scc/bbc/basicData/getExpress` —— 快递公司字典（发货回传用）
  - `GET /scc/supplier/supplier/detail`、`POST /scc/supplier/deliveryTime/config/page` —— 供应商信息
- 后续补抓：发货回传接口（`SourceReturn` 回填目前靠 Excel）、失败/超时任务样例以补全枚举。

### 4.4 订单 JSON 直连 `POST /scc/bbc/order/orderList`（2026-08-18 实测通过）

请求体（与 exportDeliverExcl 同款筛选）：

```json
{"payTimeBegin":"2026-07-18","payTimeEnd":"2026-08-18","pageNum":1,"pageSize":10,"orderStatus":"3"}
```

响应 `data`：`{pageNum, pageSize, totalNum, data:[订单], number:{waitDepotNum, deliveryNum, receivedNum, canceledNum, all, beOverdueNum, isOverdueNum, waitVerificationNum, finishVerificationNum, waitConfirmNum, confirmedNum}}`

订单对象（实测样例）：`orderCode`（主订单编号）/`orderKey`/`orderStatus`(3=待发货)/`orderStatusEnumName`/`supplierCode`/`receiverName`/`receiverTelephone`/`payTime`/`orderTime`/`purchaseCode`/`vip`/`snCode`

### 4.5 订单详情 `GET /scc/bbc/order/detail?id=<orderList.id>`（2026-08-18 14:16 抓包 + 实测）

补齐 JSON 全量信息，响应 `data` 字段：

| 字段 | 语义 |
|---|---|
| `orderCode` / `orderKey` / `orderKind` | 主订单编号 / 订单键（SPLIT_ORDER 带子单后缀=子订单编号）/ MAIN_ORDER=主单、SPLIT_ORDER=拆单 |
| `receiverProvince/City/District/Address` | 收货地址（省/市/区/详细地址） |
| `expressRequirementCode/Name` | 物流要求 |
| `supplierOrderGoodsVo[]` | 商品明细：`goodsCode`（商品编号）/`goodsName`/`count`（数量）/`outCount`（已发数量）/`price`/`totalPrice`（进价）/`salePrice`/`totalSalePrice`（售价）/`spec`（规格）/`unit`（单位）/`productBarCode`/`deliveryTag`/`remark` |
| `goodsPackageList[]` | 已发货单运单：`packageCode`/`shipperCode`（如 JD）/`logisticCode`（运单号）/`goodsCode`/`deliverTime` |
| `purchaseCode` / `remark` / `isReplenish` | 采购单号 / 订单备注 / 补货标识 |

**JSON 全量覆盖度（orderList + orderDetail，2026-08-18 实测）**：Excel 22 列中 **19 列有对应物**；仅缺 `站点编码`、`错误原因`（回填列）与未发货单的物流信息（已发货单 `goodsPackageList` 有）。→ **JSON 直连已基本可替代 Excel 导出**；Excel 保留为官方导出兜底/核对源。

脚本：`scripts/caishixian_fetch_orders.py --mode json`（默认自动补 detail；`--no-detail` 关闭；`--force` 覆盖）。

> **落地状态（2026-08-28）**：`CaishixianConnector` 在线拉取已切换为本节 JSON 直连主链路
> （orderList 按 totalNum 真翻页 + 逐单 detail + `number.waitDepotNum` 拉取对账），
> `source_ordered_at` 从 `orderTime` 回填；旧「导出任务」链路（§2.1-2.4，pageSize:10 截断
> + 窗口不可观测）已从 Connector 移除，平台后台手工导出的 xlsx 仍走文件上传导入兜底。
> 结构化批次的单 Shipment 回填工作簿由 `CaishixianShipmentArtifactFactory` 结构化分支
> 从拉取快照重建（`站点编码` 落空串，平台是否接受待生产验证）。
> §5「orderStatus/pageSize 语义单次观测」的处置：请求契约与解析已被测试桩锁定；
> 平台侧语义靠生产对账日志（`CAISHIXIAN_PULL_RECONCILIATION_MISMATCH`）自动定案，
> 拉回行 `orderStatus != 3` 会在 raw 快照打 `order_status_unexpected` 标记。

### 4.3 可复用线索（顺手发现）

- 任务系统有 4 种 `taskType`：结算、TMS、SCM 都走同一任务链路——未来结算单拉取、TMS 回传可能复用同一套「发起→轮询→下载」模式。
- `task/file/download` 是统一文件下载代理，可复用为通用文件入口。
- `taskParam` 回显登录上下文，天然适合做审计快照。

## 5. 缺口与风险

| 项 | 说明 | 处置 |
|---|---|---|
| 凭据安全 | 新 HAR 含明文账号密码 + JWT；`taskParam` 也回显 JWT | HAR 勿外传/勿入库；密码建议修改；脚本凭据只走环境变量 |
| token 有效期 | JWT `exp` 未知 | 脚本已支持登录接口自动续期（响应头取新 token），无需人工干预 |
| `orderStatus`/`pageSize` 语义 | 基于单次观测推断 | 多导几次交叉验证导出行数 vs 页面订单数 |
| COS URL 时效 | 预签名有有效期 | 轮询到完成立即下载，失败重试从任务列表重取 |
| 供应商维度 | 登录后默认选中「基地」供应商，业务要切回主供应商 | 脚本显式带 `supplier-code: 20075684` |
| 敏感信息 | Excel 含客户信息 | 文件落库走既有审计路径 |
| 合规 | 属供应商后台官方导出功能的接口化 | 保持低频（每日 1-2 次），不绕过限流 |

## 6. 发货回传 `POST /scc/bbc/order/importDeliverExcl`（2026-08-18 14:55 抓包确认）

彩食鲜「发货结果回填」的提交入口，与 `excel-closed-loop-spec.md` 的 SourceReturnExport（来源回填文件）概念**完全对齐**：
系统把发货结果（实发数量/快递公司/运单号）填回导出模板 → 上传本接口。

**请求**：`multipart/form-data`，字段 `file`（xlsx 二进制）。认证头同业务接口（`login-token` + `supplier-code`，OPTIONS 预检已确认）。

**文件格式**（22 列 = 导出模板原样，样例存 `data-local/csx-return-upload-sample-20260818.xlsx`）：

| 列 | 填法 |
|---|---|
| 0-16（主订单编号…订单备注） | 原样保留导出内容 |
| 17 `发货数量` | 实际发货数量 |
| 18 `物流公司代码` | 见下方字典（如 `JD`） |
| 19 `物流单号` | 运单号 |
| 20 `vip订单标识` | 原样保留 |
| 21 `错误原因` | 平台校验失败时回填（本次失败样例未回填） |

**物流公司代码字典**（`POST /scc/bbc/basicData/getExpress`，27 个，2026-08-18 实测）：
`YTO` 圆通 / `JD` 京东 / `SF` 顺丰 / `HTKY` 百世 / `ZTO` 中通 / `STO` 申通 / `YD` 韵达 / `YZPY` 邮政 / `EMS` / `HHTT` 天天 / `UC` 优速 / `DBL` 德邦 / `ZJS` 宅急送 / `TNT` / `UPS` / `DHL` / `FEDEX` / `FEDEX_GJ` / `JTSD` 极兔 / `ZYE` 众邮 / `ANE` 安能 / `ANNTO` 安得 / `OTHER` 其他 / `FWX` 丰网 / `KYSY` 跨越 / `DNWL` 丹鸟 / `YMDD` 壹米滴答

**响应**：
- 失败（本次实测）：`{"code":110511000,"message":"导入数据存在异常，请修改后重试","data":null}` —— 回填列全空导致；平台不返回行级错误详情
- 成功形态未实测（需要填好回填列再上传一次确认）

**与项目映射**：`物流公司代码` ↔ `excel-closed-loop-spec.md` §3.4 承运商映射（彩食鲜 `JD` = 京东物流）；回填文件生成逻辑即现有 SourceReturnExport 用例的输出。

