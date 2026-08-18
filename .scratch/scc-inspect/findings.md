# 彩食鲜后台（scc.freshfood.cn）HAR 网络抓包分析报告

> 只读侦察分析 · 来源：`scc.freshfood.cn.har`（92 entries，2026-08-14 07:12:01Z ~ 07:13:41Z，约 100 秒）
> 分析日期：2026-08-14 · 本报告所有示例值均已脱敏，不含任何 token / cookie / 手机号 / 订单号。

---

## 0. 重要声明：抓包覆盖范围

本次 HAR **只捕获了一个业务页面（任务中心）的一次操作**（发起导出 → 轮询任务 → 下载文件），**92 条请求全部落在同一个 API 域名 `wapi.freshfood.cn`，只有 3 个唯一业务路径**：

| 路径 | 非预检请求数 |
|---|---|
| `GET /task/task/my` | 44 |
| `POST /scc/bbc/order/exportDeliverExcl` | 1 |
| `GET /task/file/download` | 1 |

**没有**捕获到：登录/SSO、订单列表、订单详情、商品、库存、对账、组织权限等接口。录制环境为 Windows Edge 浏览器（非微信内置 WebView），HAR 内 0 个 HTML 文档，属于"仅 XHR/Fetch"录制。以下分析基于现有证据 + 合理推断，推断部分已明确标注。

---

## 1. 概况统计

| 指标 | 值 |
|---|---|
| 总 entries | 92 |
| 涉及 host | 1：`wapi.freshfood.cn`（100%） |
| 时间范围 | 2026-08-14 07:12:01Z → 07:13:41Z（约 100 秒） |
| 请求方法分布 | OPTIONS 46（CORS 预检）、GET 45、POST 1 |
| 状态码 | 200 × 91、204 × 1（预检） |
| 响应类型 | application/json × 45、text/plain × 45（预检）、application/vnd.ms-excel × 1 |
| 业务接口（非预检） | 46 个 = 44 轮询 + 1 导出 + 1 下载 |
| 网关/部署 | Kong API Gateway（`Via: kong/0.13.1`）+ 腾讯云 ELB（`Server: elb`），响应头含 `X-Kong-Proxy-Latency`/`X-Kong-Upstream-Latency` |

---

## 2. 完整 API 清单（按业务域分组）

域名前缀：`https://wapi.freshfood.cn`

### 2.1 订单 / 履约 / 导出（本次抓包核心）

| Method | Path | 频次 | 疑似用途 |
|---|---|---|---|
| POST | `/scc/bbc/order/exportDeliverExcl` | 1 | **导出配送/待发货订单 Excel**（`bbc` 疑为 B2B 渠道标识；`Excl` 为官方拼写）。同步发起异步导出任务，响应 `data` 为任务 ID |
| GET | `/task/file/download?name=&url=` | 1 | 下载导出文件（文件实体在腾讯云 COS，本接口做下载跳转/代理） |
| GET | `/task/task/my?sysCode=&taskType=` | 44 | **任务中心"我的任务"轮询**：查询异步任务列表/进度，每 10 秒轮询一次 |

### 2.2 登录 / 认证

| Method | Path | 频次 | 疑似用途 |
|---|---|---|---|
| — | *未捕获* | — | 本 HAR 无 login/auth/sso 请求，会话在抓包前已建立（见 §3 认证机制） |

### 2.3 商品 / 库存 / 对账 / 组织

| 域 | 状态 |
|---|---|
| goods / sku / product / category | 未捕获 |
| stock / inventory | 未捕获 |
| settlement / bill / finance / reconciliation | 未捕获 |
| org / user / dept / role / permission | 未捕获 |

> 证据缺口：以上业务域接口需另行抓包（或直接对接联调环境）补齐。

---

## 3. 认证机制（无敏感值）

**机制：双自定义请求头认证，无 Authorization、无 Cookie。**

| 请求头名 | 值形态（仅格式，非值） | 作用推断 |
|---|---|---|
| `login-token` | 227 字符字母数字串（无分隔符，非 JWT 三段式） | 主登录凭证，携带用户身份与登录态 |
| `supplier-code` | 8 位十六进制串（如 32 位整数的 hex 形式） | 供应商身份标识，与业务对象 `SourceChannel.CAISHIXIAN` 视角下的"供应商/渠道"概念对应 |

其他关键事实：

- 46 个非预检请求**全部**携带上述两头；OPTIONS 预检不携带（仅声明 `Access-Control-Request-Headers`）。
- **滚动续期证据**：响应头也回传 `login-token`，且 CORS 配置 `Access-Control-Expose-Headers: login-token` 显式暴露给前端 JS——典型的前端读响应头刷新 token 模式。token 有效期约等于一次业务会话，超期后服务端不再返回新值。
- CORS `Access-Control-Allow-Origin: https://scc.freshfood.cn`（仅允许主站源）；`Access-Control-Allow-Headers` 白名单：`supplier-code, origin, content-type, accept, authorization, x-requested-with, login-token`——白名单**预留了 `authorization` 头**，说明网关层兼容标准 Bearer 写法（`login-token` 可能是 `authorization` 的自定义别名或历史遗留）。
- token 获取接口未在本次抓包出现；从 `Access-Control-Allow-Origin` 与 Referer 推断，登录发生在 `https://scc.freshfood.cn` 主站（或其 SSO），前端 SPA 将 token 存于 localStorage/内存后注入请求头。
- 轮询参数 `taskType`（20~25 字符字母数字串，4 个不同值）与 `sysCode`（15 字符，恒定）疑似为任务类型编码与系统编码——**注意 `taskType` 值本身形态类似 token，对接时不要与凭证混淆**。

---

## 4. 关键接口脱敏样例

### 4.1 发起导出（订单履约核心入口）

```
POST https://wapi.freshfood.cn/scc/bbc/order/exportDeliverExcl
Content-Type: application/json
Headers: login-token: <token> / supplier-code: <hex>
```

请求体：

```json
{
  "payTimeBegin": "<date yyyy-MM-dd>",
  "payTimeEnd":   "<date yyyy-MM-dd>",
  "pageNum":      <number>,      // 分页页号（从 1 起）
  "pageSize":     <number>,      // 分页大小
  "orderStatus":  "<digits>"     // 订单状态筛选，本次为单字符数字 "3"
}
```

响应体：

```json
{ "code": 200000, "message": "success", "data": <number> }  // data = 异步任务 ID
```

### 4.2 任务轮询（进度查询）

```
GET https://wapi.freshfood.cn/task/task/my?sysCode=<alnum-15>&taskType=<alnum-20..25>
```

响应体（`data` 为任务数组，字段结构 ≤3 层）：

```json
{
  "code": 200000, "message": "<string>",
  "data": [{
    "id": <number>,
    "sysCode": "<string>", "taskName": "<string>",      // 固定业务文案，如"企业购导出待发货订单"
    "taskCode": "<36-char alnum，含长数字段>",
    "taskType": "<string>", "taskTimeout": <number>,
    "taskStatus": <number>,      // 本次样本恒为 2（疑似"已完成"态）
    "totalProgress": <number>, "currProgress": <number>,  // 本次样本 100/100
    "resultCode": <number>,      // 本次样本 200000（成功）
    "taskResult": <number>, "processStatus": <number>,
    "taskParam": "<长序列化串，含时间戳/11位数字段>",
    "taskData": "<string>", "taskAttach": "<string>",    // 疑含 COS 文件路径/下载信息
    "userId": <number>,
    "createBy": "<string>", "createTime": "<date>",
    "updateBy": "<string>", "updateTime": "<date>",
    "taskRedirect": <null>, "progressPercent": <number>
  }],
  "count": <number>
}
```

> 敏感说明：`taskCode`（36 字符）与 `taskParam`（约 1200 字符，内含 13 位时间戳段与 11 位数字段）**疑似内嵌手机号/单号**，本报告一律不展示值。

### 4.3 文件下载

```
GET https://wapi.freshfood.cn/task/file/download?name=<文件名>&url=<COS 文件 URL>
```

| 参数 | 形态 | 说明 |
|---|---|---|
| `name` | `<string>` | 展示用文件名，本次为"待发货订单.xlsx"（固定文案） |
| `url` | `<string>` 130 字符 | **腾讯云 COS 私有桶地址**：`https://csx-prd-<bucket-id>.cos.ap-shanghai.myqcloud.com/<5 段路径>`，无 query 签名（下载凭证走 `login-token` 透传） |

响应：`Content-Type: application/vnd.ms-excel`、`Content-Disposition: attachment; filename=待发货订单.xlsx`（URL 编码）、body 为 base64 编码的 .xlsx 二进制（本次样本 4035 字节，内容为空壳/少量数据）。

---

## 5. 数据流推断

```
用户打开任务中心页面 (https://scc.freshfood.cn)
  │
  ├─ [页面加载即启动] 轮询 GET /task/task/my?sysCode&taskType  ×4 并行（每 10 秒一轮）
  │      → 4 个不同 taskType（任务分类），本次样本含两类任务记录：
  │        "企业购导出待发货订单" ×43 条、"合作商品价格查询导出" ×11 条（均为已完成态）
  │
  ├─ [用户点击"导出"] POST /scc/bbc/order/exportDeliverExcl {payTime范围, pageNum/size, orderStatus}
  │      → 返回任务 ID（data: <number>）
  │
  ├─ [导出中] 继续每 10s 轮询任务列表 → 观察该任务 taskStatus / progress 直到完成
  │
  └─ [任务完成] GET /task/file/download?name=xxx.xlsx&url=<COS URL>
         → 下载 Excel（经后端校验 login-token 后放行，文件在腾讯云 COS 私有桶）
```

要点：
1. **所有导出是异步任务**：提交 → 轮询 → 下载，任务实体由统一任务中心管理（`/task/*`），导出文件落 COS。
2. **轮询是页面级全局行为**：每 10 秒 4 个并行 GET（非导出触发，页面常驻轮询），44 次 GET 中有约 43 次返回空或已完成任务列表。
3. 全程无 Cookie/无 Authorization，纯 `login-token` 头认证，服务端滚动续期。

---

## 6. 分页 / 查询模式

| 接口 | 分页参数 | 筛选参数 | 备注 |
|---|---|---|---|
| `POST /scc/bbc/order/exportDeliverExcl` | `pageNum`、`pageSize`（body 内 int） | `orderStatus`（字符串数字）、`payTimeBegin`/`payTimeEnd`（`yyyy-MM-dd`） | MyBatis PageHelper 风格命名；导出范围=筛选结果全量 |
| `GET /task/task/my` | 无（返回全量 + `count`） | `sysCode`、`taskType`（query） | 任务列表不分页，按类型过滤 |
| `GET /task/file/download` | — | — | 按 `name` + `url` 直接取 |

> 推断：后台其他列表接口（订单列表等，未捕获）大概率沿用 `pageNum/pageSize` + 时间范围筛选 + 状态筛选的同一套约定，可先按此模式设计对接侧分页。

---

## 7. 与「订单/履约/对账」对接相关的接口高亮与建议

### 高价值接口（本次样本内）

| 优先级 | 接口 | 对接价值 |
|---|---|---|
| ★★★ | `POST /scc/bbc/order/exportDeliverExcl` | 订单履约侧唯一确认存在的**订单导出入口**（配送/待发货口径）。`bbc` 路径段 + `orderStatus` 参数说明订单域接口前缀为 `/scc/bbc/order/*`，是后续探测订单列表/详情接口的**命名空间锚点** |
| ★★★ | `GET /task/task/my` + `/task/file/download` | 完整异步任务链路（任务中心 + COS 文件），**对账/批量拉取场景可直接复用**：提交任务 → 轮询 → 取文件，无需同步拉大列表 |
| ★★☆ | `login-token` / `supplier-code` 双头认证 + 响应头续期 | 对接方需复刻该认证注入与续期逻辑；网关白名单预留 `authorization`，可问彩食鲜侧是否支持标准 Bearer |

### 建议

1. **补齐抓包**：本次仅覆盖任务中心。建议再录制：登录页（拿 token 获取接口）、订单管理列表/详情（拿订单字段结构，验证 `pageNum/pageSize` 约定）、对账/结算页、商品与库存页。抓包环境用 Edge/Chrome 无痕 + DevTools "仅 Fetch/XHR" 即可。
2. **认证对接**：向彩食鲜索取 `login-token` 的获取/续期协议（是否支持服务间调用、是否有 appid/appsecret 方式），避免走浏览器登录态；确认 `supplier-code` 与"我司供应商编码"的映射。
3. **导出链路复用**：若目标是"对账数据拉取"，`exportDeliverExcl` 的异步任务模式（提交 → 轮询 `/task/task/my` → `/task/file/download` 取 COS 文件）大概率可复用到其他导出（对账单、价目表等，本样本中"合作商品价格查询导出"即同类任务）。轮询建议 >10s 间隔并做超时熔断。
4. **数据口径**：`orderStatus` 枚举值需向彩食鲜索取字典（本次仅见 `"3"` 一个值）；`taskStatus`（本次恒为 2）与 `resultCode`（200000=success）枚举同理。
5. **工程侧**：本项目 `SourceChannel.CAISHIXIAN` 已存在，对接建议在 fulfillment 中新增 `SccOrderFetcher`/`SccReconService` 时，先按本次确认的 `pageNum/pageSize` + 时间窗模式写适配层，并预留异步任务状态机（与任务中心字段 `taskStatus/totalProgress/currProgress/resultCode` 对齐）。

---

## 8. 分析脚本

- `inspect-scc-har.py`：主分析脚本（概况/端点/认证头名/请求响应字段树脱敏/时间线）
- `probe2.py` / `probe3.py`：值形态与枚举补充探测（只输出长度/字符集/枚举，不输出值）
- `analysis-output.txt`：主脚本脱敏输出存档

安全声明：所有脚本与输出均只含头名、字段名、值形态（长度/字符集/枚举），不含任何 token、cookie、手机号、订单号或真实文件内容。
