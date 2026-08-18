# 飞象供应商平台抓包记录与接口契约（ziyousupplier.wowcarp.com）

状态：Captured / 认证与导出已确认（2026-08-18 抓包）
日期：2026-08-18
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
username=Jcqy13901259928&password=Jc012304.
```

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
FEIXIANG_USERNAME=Jcqy13901259928 FEIXIANG_PASSWORD=*** \
  python3 scripts/feixiang_fetch_orders.py --begin 2026-07-18 --end 2026-08-18
```

## 6. 缺口与风险

| 项 | 说明 | 处置 |
|---|---|---|
| 无 JSON 订单接口 | 本 HAR 仅 16 条请求，`order/delivery` 为服务端渲染页面，未发现订单 JSON 列表接口（仅 `ajaxOrderNum` 统计） | 若要 JSON 直连，需再抓订单页滚动/翻页时的 AJAX 请求确认 |
| 会话有效期 | `fxqf_sess` `Max-Age=86400`（1 天），登录后 302 不刷新 cookie | 脚本每次运行重新登录，天然规避 |
| 登录失败/验证码 | 登录失败行为未抓包（未观测到错误页/验证码）；前端未发现验证码字段 | 首次真实运行验证；若遇验证码需人工介入或补抓 |
| 导出样例无数据行 | 样例 `dimension A1:U1` 只有表头，未见数据行 | 用真实区间导一次验证数据行结构仍为 21 列 v1 |
| 导出参数语义 | `end_time` 是否含当天、`start_time/end_time` 是按下单还是发货时间口径未验证（页面为「发货订单」页） | 与页面订单数（ajaxOrderNum）交叉核对 |
| `page_type` 语义 | 页面用 `page_type=0`，未知是否影响导出范围 | 导出接口只传 start/end，不受 page_type 影响（待实测确认） |
| 未登录响应 | 未直接观测未登录访问 deliveryExport 的响应（预计 302 回登录页） | 脚本用 PK 魔数兜底：拿到 HTML 即报错 |
| 凭据安全 | HAR 与凭据文件含明文密码 | HAR/凭据仅存本地（gitignore），脚本只读环境变量 |
| 合规 | 属供应商后台官方导出功能的接口化 | 保持低频（每日 1-2 次），不绕过限流 |
