# 飞象供应商平台抓包记录与接口契约（ziyousupplier.wowcarp.com）

状态：Captured / 认证已确认（2026-08-18 抓包）；**导出链路已于 2026-08-28 废弃，见 §7**
日期：2026-08-18（2026-08-28 补 §7）

> ⚠️ **§2「导出下载」链路已不再使用。** `GET /order/deliveryExport?start_time=&end_time=` 的
> 日期参数平台根本不认（平台用的是 `start_create_time`/`end_create_time`），传什么窗口都
> 静默回落成「只返回拉取当天下单的订单」，导致任何没在下单当天被拉到的单永久丢失。
> 生产实证与替代方案见 **§7**。§1–§5 保留作历史抓包存档。

来源：`~/Desktop/ziyousupplier.wowcarp.com_2026_08_18_10_57_16飞象.har`（361 KB，16 条请求）
副本：`data-local/ziyousupplier.wowcarp.com.har`（同内容，勿提交 git）
抓包环境：Windows + Edge 148，页面与 API 同域 `https://ziyousupplier.wowcarp.com`（ThinkPHP 风格，cookie 认证）
用途：飞象 Connector 从「人工导表」升级为「API 自动拉取」的依据；接口契约存档

> ⚠️ 安全提醒：HAR 含**明文账号密码**（登录表单），请勿外传，密码建议尽快修改；
> HAR 副本在 `data-local/`（已被 .gitignore 忽略），不要提交到 git。

## 1. 抓包概况

16 条请求覆盖「登录 → 订单页 → 导出下载」闭环，核心链路是**一条直达导出**（无任务/轮询）：

```
GET  /welcome/index/                       引导会话（首次访问种下 fxqf_sess cookie）
POST /welcome/index/                       登录（表单 username/password）→ 302
GET  /product_library/publish_list         302 落点（登录成功标志，页面 200）
GET  /order/delivery?page_type=0&start_time=..&end_time=..    发货订单页面（HTML）
GET  /order/deliveryExport?start_time=..&end_time=..          直接返回 xlsx 下载
```

其他请求：`/order/ajaxOrderNum`（订单数量统计，见 3.4）、静态资源、`GET /` → 302 `/manage`（未登录根路径重定向）。

## 2. 认证方式（已确认）

- 认证凭据是 cookie **`fxqf_sess`**（ThinkPHP session），全流程 16 条请求携带同一值
  （实测 `fxqf_sess=avglvo53pdq1ag7d77ckqkkclvk6vsmd`）。
- 登录请求细节（2026-08-18 抓包）：

  | 项 | 值 |
  |---|---|
  | 方法/路径 | `POST /welcome/index/` |
  | Content-Type | `application/x-www-form-urlencoded` |
  | 表单 body | `username=<账号>&password=<密码>`（无 CSRF token、无其他字段） |
  | 登录前 | 请求已携带既有 `fxqf_sess`（由更早的页面访问种下） |
  | 响应 | `302`，`Location: https://ziyousupplier.wowcarp.com/product_library/publish_list` |
  | 302 响应头 | **无 Set-Cookie**（会话 cookie 在页面访问时种下，非登录时刷新） |

- cookie 刷新样例：`/order/ajaxOrderNum` 响应头 `Set-Cookie: fxqf_sess=<值>; expires=Wed, 19-Aug-2026 02:56:07 GMT; Max-Age=86400; path=/; HttpOnly` —— **有效期 1 天（Max-Age=86400）**。
- 未登录行为：`GET /` → 302 `/manage`（登录页兜底）；未登录访问业务接口预计同样 302 回登录页（未在本次抓包直接观测，见风险表）。
- 登录失败行为：未抓包验证（正常应 302 回 `/welcome/index`），脚本以最终 URL 是否停在登录页判定失败。

## 3. 接口契约

### 3.1 POST `https://ziyousupplier.wowcarp.com/welcome/index/` — 登录

请求（form-urlencoded）：

```
username=<凭据已移除，2026-08-28>&password=<凭据已移除，2026-08-28>
```

> 🔒 明文账号密码已于 2026-08-28 从本文件移除；**历史提交仍含明文，遮蔽不等于安全，该凭据需轮换**。

响应：`302` → `Location: https://ziyousupplier.wowcarp.com/product_library/publish_list`（跟随 302 后页面 200，body 为空）。

### 3.2 GET `https://ziyousupplier.wowcarp.com/order/deliveryExport` — 导出下载（核心）

Query 参数（页面 JS `$('#export_csv').click()` 实测只传这两个）：

| 参数 | 样例 | 语义 |
|---|---|---|
| `start_time` | `2026-06-18` | 开始日期 `yyyy-MM-dd` |
| `end_time` | `2026-08-18` | 结束日期 `yyyy-MM-dd`（含当天，待验证） |

响应：

| 响应头 | 实测值 |
|---|---|
| 状态码 | `200` |
| `Content-Type` | `application/vnd.ms-excel` |
| `Content-Disposition` | `attachment;filename=批量发货1787021776.csv` —— **误命名 .csv，实为 XLSX** |

- 响应体以 `PK\x03\x04` 魔数开头（OOXML ZIP），抓包样例 6494 B（仅含表头行，见第 4 节）。
- 无 `content-encoding`、无 `Set-Cookie`；直接 GET 即得文件，**无任务系统、无轮询**。
- 文件名规律：`批量发货<10位Unix时间戳>.csv`（与规范 §3.3 记载的历史 `批量发货1786435269.csv` 同一命名模式，均为误命名 XLSX）。

### 3.3 GET `https://ziyousupplier.wowcarp.com/order/delivery` — 发货订单页面（上下文）

Query：`page_type=0&start_time=..&end_time=..`（`page_type=0` 本次实测；页面 JS 用同一日期区间拼 3.2 的导出链接，并带 10s 防连点）。
响应：200 HTML（20.7 KB，UTF-8，`content-encoding: gzip`）。

### 3.4 POST `https://ziyousupplier.wowcarp.com/order/ajaxOrderNum` — 订单数量统计（辅助）

请求（form-urlencoded，页面 esOrder/index 调用）：

```
start_create_time=2026-06-18&end_create_time=2026-08-18&order_state=&sel_type=1&keyword=&start_finish_time=&end_finish_time=
```

响应（JSON）：`{"status":1,"msg":"ok","data":{"num":"7","product_num":"7"}}`
—— 可用于拉表前自检「区间内订单数 > 0」，`sel_type=1` 语义（按创建时间筛选）待页面确认。

## 4. 下载文件表头指纹与匹配结论

从 HAR 二进制还原的样例（`data-local/feixiang-delivery-export-sample-20260818.xlsx`）：
Sheet 名 `Worksheet`，`dimension A1:U1` —— **本次样例仅含 1 行表头、无数据行**（抓包当时区间内统计有 7 单，导出时机与统计存在先后差异，见风险表）。

真实表头（21 列，顺序原样）：

```
订单号 | 会员名称 | 商品名称 | 商品ID | 商品货号 | 商品规格 | 订单商品ID | 可发货数量 |
成本价/协议价 | 会员价 | 订单状态 | 售后状态 | 物流状态 | 购买人账号 | 收货人姓名 |
收货人手机号 | 收货人地址 | 下单时间 | 物流公司 | 物流单号 | 备注
```

与 `docs/excel-closed-loop-spec.md` §3.2 指纹比对：

| 指纹 | 关键表头 | 命中情况 |
|---|---|---|
| 飞象 **v1**（历史误命名 XLSX） | `订单号` `订单商品ID` `可发货数量` `物流状态` `物流公司` `物流单号` | **全部命中 ✅** |
| 飞象 v2（平台原始 CSV） | `订单号` `订单商品ID` `商品数量` `物流状态` `物流单号` `收货人手机号` | 未命中 ❌（缺 `商品数量`，本表为 `可发货数量`） |

**结论：`deliveryExport` 下载的文件属于「飞象 v1」格式（21 列误命名 XLSX）**，与规范 §4.3/§10.4 中「历史 21 列误命名 XLSX 仅作 v1 兼容输入、不得决定真 CSV 输出」的定位一致。
v2（40 列真 CSV）仍由人工在平台下载获得（规范 §13 已锁定其哈希与编码），与本次接口无关。

## 5. 复用方案

已落地：`scripts/feixiang_fetch_orders.py`

- 自动登录：先 GET `/welcome/index/` 引导会话（种下 `fxqf_sess`）→ POST 表单（`username`/`password`，`allow_redirects=True`）→ 校验最终 URL 不在登录页。
- 拉表：`GET /order/deliveryExport?start_time=..&end_time=..`（默认近 30 天），校验 `PK` 魔数，落盘 `data-local/飞象待发货订单-YYYYMMDD.xlsx`；同名跳过、`--force` 覆盖、`--dry-run` 只打印不保存。
- 凭据只走环境变量 `FEIXIANG_USERNAME` / `FEIXIANG_PASSWORD`；密码不打日志、不落盘。
- 与既有 EXCEL 闭环衔接：下载文件命中 v1 指纹 → `FeixiangConnector` 按 v1 解析（`可发货数量` 为请求数量、回填 `物流状态/物流公司/物流单号`），Connector 零改动。

```bash
FEIXIANG_USERNAME=<凭据已移除，2026-08-28> FEIXIANG_PASSWORD=*** \
  python3 scripts/feixiang_fetch_orders.py --begin 2026-07-18 --end 2026-08-18
```

## 6. 缺口与风险

| 项 | 说明 | 处置 |
|---|---|---|
| ~~无 JSON 订单接口~~ | ~~本 HAR 仅 16 条请求，未发现订单 JSON 列表接口~~ | **已证伪**：2026-08-28 抓包发现 `POST /order/ajaxGetSendBeforePro` 返回订单详情 JSON，见 §7 |
| 会话有效期 | `fxqf_sess` `Max-Age=86400`（1 天），登录后 302 不刷新 cookie | 脚本每次运行重新登录，天然规避 |
| 登录失败/验证码 | 登录失败行为未抓包（未观测到错误页/验证码）；前端未发现验证码字段 | 首次真实运行验证；若遇验证码需人工介入或补抓 |
| 导出样例无数据行 | 样例 `dimension A1:U1` 只有表头，未见数据行 | 用真实区间导一次验证数据行结构仍为 21 列 v1 |
| 导出参数语义 | `end_time` 是否含当天、`start_time/end_time` 是按下单还是发货时间口径未验证（页面为「发货订单」页） | 与页面订单数（ajaxOrderNum）交叉核对 |
| `page_type` 语义 | 页面用 `page_type=0`，未知是否影响导出范围 | 导出接口只传 start/end，不受 page_type 影响（待实测确认） |
| 未登录响应 | 未直接观测未登录访问 deliveryExport 的响应（预计 302 回登录页） | 脚本用 PK 魔数兜底：拿到 HTML 即报错 |
| 凭据安全 | HAR 与凭据文件含明文密码 | HAR/凭据仅存本地（gitignore），脚本只读环境变量 |
| 合规 | 属供应商后台官方导出功能的接口化 | 保持低频（每日 1-2 次），不绕过限流 |

---

## 7. JSON/HTML 拉取链路（2026-08-28 起生效，取代 §3.2 导出下载）

来源：用户 2026-08-28 的 HAR 抓包线索 + 本次实现。**本节严格区分「抓包实据」与「实现推断」。**

### 7.1 为什么废弃导出下载（生产确证的根因）

旧实现传 `start_time`/`end_time`，**平台不认这两个参数名**（列表页用的是
`start_create_time`/`end_create_time`），平台丢弃后回落成「只返回拉取当天下单的订单」。

生产库实证：历史上拉到过的全部 8 行，**拉取日与下单日 100% 相同**，跨 5 天、两个渠道、零反例。

后果：**任何没在下单当天被拉到的单永久丢失。** 已确认丢失实例——飞象订单
`D2026826346818550490`（2026-08-26 16:58 下单，子牧原切牛腱子 500g*2 ×2，¥212），从未进入系统。

### 7.2 新链路

```
POST /welcome/index/                   登录（未改，2026-08-18 抓包实据）
GET  /esOrder/index                    待发货列表第 1 页（HTML）
GET  /esOrder/index/{page}             第 N 页，每页 20
POST /order/ajaxGetSendBeforePro       单笔详情（JSON）   请求: order_son_id=<数字>
POST /order/ajaxOrderNum               区间订单数（JSON） 仅作交叉核对
```

列表页查询参数：`start_create_time`、`end_create_time`（`yyyy-MM-dd`）、`order_state`、
`sel_type=1`、`keyword`。`order_state`：`0`=全部，`1,6`=待付款，`2,7`=待发货，`8`=已发货，
`9-10`=已结算，`11`=已关闭，`12`=售后。

### 7.3 字段映射（与既有 Excel 链路同口径，保证判重不失效）

| Excel 列 | JSON 字段 | 去向 |
|---|---|---|
| 订单号 | `receive_info.order_sn`（D…） | `orders.source_ref` |
| 订单商品ID | `order_product[].order_product_id` | 订单行来源标识 |
| 商品ID | `order_product[].product_id` | `source_sku_ref` |
| 商品名称 | `order_product[].title` | 商品名 |
| 商品规格 | `order_product[].product_spec_name` | 规格 |
| 可发货数量 | `order_product[].pronum` | 数量 |
| 下单时间 | `receive_info.create_time` | **`orders.source_ordered_at`**（V64 列） |
| 收货人姓名/手机号/地址 | `receive_info.name` / `phone` / `area_name`+`address` | Receiver |

### 7.4 标识符陷阱（五种不可混用的 ID）

`order_sn`（D… 订单号）· `order_son_sn`（S… 子订单号）· `order_son_id`（详情/校验用**数字** ID）
· `order_id`（内部父订单）· `order_product_id`（商品行）。

HAR 分析里已出现过一次混用事故：`get_myt_order_express` 把 `order_son_id` 当 `order_id`
提交，平台回「供应商不正确」。实现侧的门闩：详情接口在发出请求**之前**校验参数必须是纯数字，
D…/S… 开头的单号会被直接拒。

### 7.5 明确未验证的部分（不要当成已确认的契约）

| 项 | 状态 | 说明 |
|---|---|---|
| **列表页 HTML 结构** | ❌ **完全未验证** | HAR 只给了路径与查询参数，**没有页面 HTML**。`order_son_id` 如何出现在标记里是推断。现覆盖 5 种写法：HTML 属性 `order_son_id="123"`、data 属性 `data-order-son-id="123"`、查询串 `?order_son_id=123`、内联 JSON `"order_son_id":123`、隐藏表单域 `<input name="order_son_id" value="123">`。若平台把 ID 只藏在 `onclick="sendBefore(123)"` 这类**不含字段名**的调用里，现有正则会捞不到 |
| `order_state=2,7` 是否接受逗号列表 | ❌ 未验证 | 实现改为逐状态各拉一轮再取并集，无论平台认单值/认列表/忽略该参数都不漏单 |
| 每页确实是 20 条 | ⚠️ 仅线索 | 用作「不满一页即末页」判据；若实际不是 20，翻页会提前停止 |
| `create_time` 的格式 | ❌ 未验证 | 实现兼容 epoch 秒/毫秒与四种日期时间串；解析不出就落 null 并转人工复核，不猜 |
| `pronum` 是否等于「可发货数量」 | ⚠️ 仅线索 | 按 Excel 的「可发货数量」列对应关系推断 |
| `express_state` 数字码语义 | ❌ 未验证 | 「已发货」主判据取 `sn`（物流单号）/`express_code`（物流公司）非空，不依赖该码 |
| `status=1` 表示成功 | ✅ 有实据 | 2026-08-18 `ajaxOrderNum` 响应 `{"status":1,"msg":"ok"}` |
| `ajaxOrderNum` 的 `num` 口径 | ⚠️ 仅作交叉核对 | 是订单数还是子订单数未确认；只用于「解析出 0 单时判断是真没单还是选择器失效」，不作硬门禁 |

**首次真实运行必须核对**：拉一次真实窗口，确认枚举到的订单数与平台页面显示一致。
若报 `FEIXIANG_ORDER_LIST_UNPARSEABLE`，说明 7.5 第一行的推断没中，需补抓一次列表页 HTML
并在 `FeixiangOrderListParser` 补对应模式——**不要放宽成「抓页面上所有数字」**，那会把商品 ID、
金额、订单号一起当成 `order_son_id`，正是标识符混用事故的温床。

### 7.6 范围边界

本次**只做拉取**。以下写接口**未实现、未验证**，留给后续回传票：
`POST /order/ajaxSendOrderProduct`（提交运单号发货）、`ajax_change_express`、
`ajax_get_product_by_sn`、`POST /order/ajaxCheckSend`（发货资格校验）。
HAR 分析自己声明「本次没有点确定，没有抓到成功写入和回查结果」——未经验证，且会真实改变
平台发货状态。
