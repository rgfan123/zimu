# 三平台外部接口总览（彩食鲜 / 聚福宝 / 飞象）

状态：2026-08-18 抓包确认 + 部分实测（彩食鲜登录/JSON/导出下载 ✅、聚福宝登录/JSON ✅、飞象仅本地解析）
详细契约见：`docs/research/caishixian-scc-wapi-export-api.md`、`docs/research/jufubao-supplier-export-api.md`、`docs/research/feixiang-supplier-export-api.md`
接入评估见：`docs/research/platform-api-integration-plan.md`
对应系统内接口：`docs/api-contract.md` §6.2 `PlatformConnector`

## 1. 一页总览

| 维度 | 彩食鲜 | 聚福宝 | 飞象 |
|---|---|---|---|
| 前端域名 | scc.freshfood.cn | g.jufubao.cn | ziyousupplier.wowcarp.com |
| API 域名 | wapi.freshfood.cn | supplier-apis.jufubao.cn | 同前端（ThinkPHP） |
| 技术栈 | Spring Boot 系 REST | Spring Boot 系 REST（JWT cookie） | ThinkPHP 服务端渲染 |
| 认证 | 自定义头 `login-token`（JWT）+ `supplier-code` | cookie `JFB_SESSION_CID` + `JFB-ADMIN-ACCESS-TOKEN` + CSRF 头 | cookie `fxqf_sess` |
| 登录接口 | `POST /ucenter/login/scc`（响应头返回新 token） | `POST /idaas-auth/v1/login-by-username`（Set-Cookie 3 个 JWT） | `POST /welcome/index/`（表单 + 302） |
| 订单获取 | **两种**：任务导出 Excel + `POST /scc/bbc/order/orderList` JSON | **JSON**：`POST /order-supplier/v1/orders/query`（含商品明细） | **Excel**：`GET /order/deliveryExport` 直下 |
| 导出机制 | 发起任务 → 轮询 `task/task/my` → `task/file/download` | 无导出（JSON 直连） | 直接 GET 返回文件（无任务） |
| 发货回传 | 未抓包（待补） | `POST /order-supplier/v1/logistics/multi-send`（契约已记录） | 未抓包（页面有 deliveryImport 线索） |
| 拉表脚本 | `scripts/caishixian_fetch_orders.py`（--mode export/json） | `scripts/jufubao_fetch_orders.py` | `scripts/feixiang_fetch_orders.py` |
| 实测状态 | 登录/JSON/历史文件下载 ✅ | 登录/JSON ✅ | 未实跑 |

## 2. 认证与登录（可程序化，均无验证码）

| 平台 | 登录请求 | 凭证 | token 有效期 | 说明 |
|---|---|---|---|---|
| 彩食鲜 | `POST /ucenter/login/scc` body `{username,password,businessCode:"fe-web-scc"}` | 账号密码 | access 未知（脚本每次登录续期） | 响应头 `login-token` 返回新 JWT；业务请求带 `login-token` + `supplier-code`（主供应商 `20075684` 河北净菜（北京）物流有限公司） |
| 聚福宝 | `POST /idaas-auth/v1/login-by-username` 表单 `{username,password,system:"supplier"}` | 账号密码 | access ~12.8h / refresh 15 天 | 前置 `GET g.jufubao.cn/` 种 `JFB_SESSION_CID`；Set-Cookie 下发 access/refresh/csrf 三个 JWT；业务请求需 `JFB-CSRF-TOKEN` 头 + `X-Jfb-Project-Id: supplier` |
| 飞象 | `POST /welcome/index/` 表单 `{username,password}` | 账号密码 | session 1 天（Max-Age=86400） | 先 GET 页面种 `fxqf_sess`；302 到 `/product_library/publish_list` 即成功 |

## 3. 订单获取契约（与 api-contract §6.2 pullOrders 的对应）

### 3.1 彩食鲜

- **Excel 导出（推荐用于完整商品明细）**：`POST /scc/bbc/order/exportDeliverExcl`
  body `{"payTimeBegin","payTimeEnd","pageNum":1,"pageSize":10,"orderStatus":"3"}` → data=任务ID → 轮询 `GET /task/task/my?sysCode=TASK-SCHEDULING&taskType=csx-b2b-supplier-schedule`（完成判定 taskStatus=2 && progress=100/100 && resultCode=200000）→ `GET /task/file/download?name&url`（url 取自 `taskAttach[0].url`，JSON 字符串）
  Excel 指纹命中规范 v1：`主订单编号/子订单编号/供应商编码/站点编码/商品编号/下单数量`（21 列）
- **JSON（仅主订单级）**：`POST /scc/bbc/order/orderList`，同款筛选参数；响应 `data.data[]` 字段 `orderCode/orderStatus(3=待发货)/supplierCode/receiverName/receiverTelephone/payTime/orderTime/purchaseCode`；`data.number` 状态计数（waitDepotNum/deliveryNum/canceledNum 等）。**无商品明细**——如需明细需补抓订单详情接口。

### 3.2 聚福宝

`POST /order-supplier/v1/orders/query`
body `{"tab":"no_delivery","filter":{"created_time_range":{"start_time":epoch,"end_time":epoch}},"page_token":"1","page_size":20,"system":"supplier"}`
响应 `{"list":[...],"next_page_token","total_size"}`；订单含 `sub_order_id/main_order_id/product_list[]（product_id/product_name/product_num/purchase_price/market_price）/order_status(NO_DELIVERY=待发货)/delivery_method/supplier_name/created_time/total_amount`——**含商品明细，JSON 最完整**。

### 3.3 飞象

`GET /order/deliveryExport?start_time=yyyy-MM-dd&end_time=yyyy-MM-dd` → 直接返回 xlsx（Content-Disposition 误命名 `.csv`，`application/vnd.ms-excel`）。
21 列表头命中规范 v1（`订单号/订单商品ID/可发货数量/物流状态/物流公司/物流单号`）；辅助 `POST /order/ajaxOrderNum`（统计，可做拉取前自检）。

## 4. 发货回传（SourceReturn 素材）

| 平台 | 状态 |
|---|---|
| 聚福宝 | ✅ 契约已记录：`POST /order-supplier/v1/logistics/multi-send`，body `{is_need_logistics, company_id, package_list_json:[{receipt_username, receipt_phone_number, address_detail, product_list:[{main_order_id, sub_order_id, product_id, num, logistics_number...}]}]}`；错误格式 `{"code":"InvalidArgument","message":...}` |
| 彩食鲜 / 飞象 | 未抓包，需补抓（彩食鲜页面有 getExpress 快递字典；飞象页面有 deliveryImport 导入线索） |

## 5. 凭据与安全

- 三个平台账号密码均已从抓包登录请求确认，存 `data-local/*-credentials.txt`（gitignore 内，仅本地）
- 所有脚本凭据走环境变量，token/cookie 不落盘、日志脱敏
- 抓包 HAR 含明文密码与有效会话，勿外传；建议定期改密
- 供应商/租户维度：彩食鲜必须显式 `supplier-code: 20075684`（主供应商，登录后默认可能是「基地」供应商 `20070589`，需显式指定）
