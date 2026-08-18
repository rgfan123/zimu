# 聚福宝供应商平台抓包记录与接口契约（supplier-apis.jufubao.cn）

状态：Captured / 认证与订单契约已确认（2026-08-18 抓包）
日期：2026-08-18
来源：`~/Desktop/supplier-apis.jufubao.cn_2026_08_18_11_16_14.har`（833 KB，114 请求，登录后抓取）
环境：前端 `https://g.jufubao.cn`（供货商后台管理系统）→ API 域名 `supplier-apis.jufubao.cn`
用途：聚福宝 Connector 从「人工导 Excel」升级为「JSON 直连」的依据；发货回传契约存档

> ⚠️ 安全提醒：HAR 含**明文账号密码**（登录请求体）与有效 JWT cookie，请勿外传，密码建议尽快修改；HAR 副本勿提交 git。

## 1. 关键结论

- **聚福宝没有 Excel 导出接口**（HAR 内无 export/download/file 类接口）——订单数据走 **JSON 接口直连**，与彩食鲜（任务导出）和飞象（CSV 误命名直下）模式都不同。
- 认证：`JFB_SESSION_CID`（会话）+ `JFB-ADMIN-ACCESS-TOKEN`（JWT 访问令牌）双 cookie；登录接口可程序化，token 自动续期可行。
- 订单查询 `POST /order-supplier/v1/orders/query`，`tab=no_delivery` 即「待发货」。
- 发货回传 `POST /order-supplier/v1/logistics/multi-send` 契约已记录（供 SourceReturn 参考）。

## 2. 认证方式（已确认）

### 2.1 登录 `POST https://supplier-apis.jufubao.cn/idaas-auth/v1/login-by-username`

表单（`application/x-www-form-urlencoded`）：`username=<账号>&password=<密码>&system=supplier`

前置：先 `GET https://g.jufubao.cn/` 种会话 cookie `JFB_SESSION_CID`（登录请求本身也带它）。

响应头 Set-Cookie 下发三个 JWT：

| Cookie | 用途 | 有效期（抓包实测） |
|---|---|---|
| `JFB-ADMIN-ACCESS-TOKEN` | 访问令牌（业务请求带） | ~12.8h（`access_token_expire_in=45962`） |
| `JFB-ADMIN-REFRESH-TOKEN` | 刷新令牌 | 15 天（`refresh_token_expire_in=1296000`） |
| `JFB-ADMIN-CSRF-TOKEN` | CSRF 双提交 cookie | 同 access |

响应体：`{"is_second_verify":false,"access_token_expire_in":45962,"access_token_cookie_key":"JFB-ADMIN-ACCESS-TOKEN",...}`（本次无二次验证/验证码）。

### 2.2 业务请求头

```
Cookie: JFB_SESSION_CID=<会话>; JFB-ADMIN-ACCESS-TOKEN=<JWT>
Content-Type: application/json;charset=UTF-8
Origin: https://g.jufubao.cn
Referer: https://g.jufubao.cn/
```

（业务请求未见额外 CSRF 头，CSRF 走 cookie 双提交。）

### 2.3 当前账号

username `京诚乾元`（partner_id=161，real_name 邹皕芃）；登录前需 JFB_SESSION_CID（前端页面种下）。

## 3. 订单查询 `POST /order-supplier/v1/orders/query`（JSON 直连）

请求体：

```json
{"tab":"no_delivery","filter":{"created_time_range":{"start_time":1786418151,"end_time":1787022951}},
 "page_token":"1","page_size":20,"system":"supplier"}
```

| 字段 | 语义 |
|---|---|
| `tab` | `no_delivery` 待发货（默认）/ `delivered` 已发货 / `all` 全部 |
| `filter.created_time_range` | Unix epoch 秒（本地 08:00 时区口径待验证） |
| `page_token` | 分页游标，首页 `"1"`；翻页用响应 `next_page_token` |
| `page_size` | 每页（实测 20） |

响应 `{"list":[...],"next_page_token":"","total_size":N,"request_id":"..."}`；`next_page_token` 为空即末页。

订单对象字段（样例，`s947785003889885546`）：

| 字段 | 样例 | 语义 |
|---|---|---|
| `sub_order_id` / `main_order_id` | `s947785003889885546` / `m947785003453677929` | 子/主订单号（对应 Excel 闭环的 `拆单号`/`主单号`） |
| `product_list[]` | `product_id`/`product_name`/`product_sku_id`/`product_num`/`purchase_price`(分)/`market_price`/`aftersale_status`/`brand_name` | 商品明细 |
| `order_status` / `order_status_name` | `NO_DELIVERY` / `待发货` | 状态枚举 |
| `delivery_method` / `delivery_method_name` | `logistics` / `快递配送` | 配送方式 |
| `supplier_name` | `京诚乾元` | 供应商 |
| `created_time` | `1786929554` | 创建时间 epoch |
| `total_amount` / `purchase_amount` | `0` / `6900` | 金额（分） |
| `button_list` | `[{"text":"发货","action":"send_good"}]` | 可执行操作 |

## 4. 其他接口（契约存档）

| 接口 | 方法 | 用途 |
|---|---|---|
| `/order-supplier/v1/logistics/import-task-list?page_token=1&page_size=20&system=supplier` | GET | 物流导入任务列表（本次空） |
| `/order-supplier/v1/logistics/multi-send` | POST | **发货回传**（见下） |
| `/order-supplier/v1/logistics/multi-send-form` / `sub-order-info` | GET | 发货表单数据 |
| `/idaas-auth/v1/userinfo`、`/menus-permission` | GET | 用户信息/菜单权限 |
| `/supplier/v1/supplier/get`、`get-retail-money`、`get-gift-money` | GET | 供应商信息/余额 |
| `/order-public/v1/logistics-company/options` 等 `/options` 系列 | GET | 物流公司/订单类型/配送方式等字典 |
| `/stat-supplier/v1/out-service/trade-order-trending` 等 | GET | 数据看板 |

### 4.1 发货回传 `POST /order-supplier/v1/logistics/multi-send`（记录，未实现）

```json
{"is_need_logistics":"Y","company_id":65,
 "package_list_json":"[{\"receipt_username\":\"谢先生\",\"receipt_phone_number\":\"18905931977\",\"address_detail\":\"...\",\"subscribe_time\":\"\",\"comment\":\"\",\"product_list\":[{\"main_order_id\":\"m...\",\"sub_order_id\":\"s...\",\"product_id\":66662134,\"product_name\":\"...\",\"product_sku_id\":\"0\",\"num\":1,\"logistics_number\":\"\",\"remarks\":\"\",...}],...}]"}
```

- 错误格式：`{"code":"InvalidArgument","message":"快递单号只允许包含字母和数字","request_id":"..."}`（实测样例因单号含非法字符被拒）
- `company_id` 对应 `/order-public/v1/logistics-company/options` 字典

## 5. 复用方案

### 5.1 已落地：JSON 直连拉表（scripts/jufubao_fetch_orders.py）

```bash
JFUBAO_USERNAME=<账号> JFUBAO_PASSWORD=<密码> python3 scripts/jufubao_fetch_orders.py \
    --begin 2026-07-18 --end 2026-08-18
# 或 cookie 直连（session 已有时，省登录）
JFUBAO_COOKIE="JFB_SESSION_CID=...; JFB-ADMIN-ACCESS-TOKEN=..." python3 scripts/jufubao_fetch_orders.py
```

- 默认 `tab=no_delivery` 近 30 天，落 `data-local/聚福宝订单-no_delivery-YYYY-MM-DD.json`；`--dry-run` / `--force` / `--page-size` / `--tab`
- 登录自动完成（前端页面种 JFB_SESSION_CID → 表单登录 → session 保存 3 个 JWT cookie），无验证码（本次 `is_second_verify=false`）

### 5.2 JSON → CanonicalOrder 映射建议（后续）

JSON 字段可直接映射项目 CanonicalOrder，无需 Excel 指纹：
- `main_order_id` → source order reference（对应 Excel 闭环 `主单号`）
- `sub_order_id` → source line reference（`拆单号`）
- `product_list[].product_id/product_name` → source product reference/snapshot（`商品编号`语义）
- `product_list[].product_num` → requested quantity（`下单数量`）
- `order_status=NO_DELIVERY` → 待发货状态
- 收货人信息注意：orders/query 的 list 对象未直接含收货人（在 multi-send-form / sub-order-info 或 product_list 内），如需收货人字段需补抓 `sub-order-info` 确认

### 5.3 发货回传（未来）

`multi-send` 契约已记录，可与京东/第三方发货结果回填打通（SourceReturn 目标），先记后做。

## 6. 缺口与风险

| 项 | 说明 | 处置 |
|---|---|---|
| 凭据安全 | HAR 含明文密码 + JWT cookie | 勿外传/勿入库；密码建议修改；脚本凭据只走环境变量 |
| access token 有效期 | ~12.8h；refresh 15 天 | 脚本每次运行自动登录（成本低）；长期运行需补 refresh 逻辑 |
| JFB_SESSION_CID 来源 | 登录前需前端页面种下 | 脚本已先 GET 前端页再登录 |
| 时区口径 | created_time_range 的 epoch 起点（当天 00:00 所属时区）待验证 | 与页面筛选结果交叉核对 |
| 收货人字段 | orders/query list 未含收货人明细 | 需补抓 sub-order-info / multi-send-form 确认字段路径 |
| 字典 | company_id 等需按 options 接口维护映射 | 补抓/手工维护 logistics-company/options 快照 |
| 合规 | 登录 + 查询属供应商后台官方功能接口化 | 保持低频（每日 1-2 次） |
