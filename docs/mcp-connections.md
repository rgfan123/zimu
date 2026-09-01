# MCP 连接总览（谁连什么、看得见什么、怎么接）

> 现状文档，最后核对 2026-09-02（生产 zimupc）。传输层协议细节见 [mcp-http-transport.md](mcp-http-transport.md)；
> 权限边界的契约表述见 [api-contract.md](api-contract.md) §8。

## 1. 三个面：一句话区分

| 面 | 入口 | 谁在用 | 工具可见性 | 写操作 |
|---|---|---|---|---|
| **协议面**（对外只读） | `http://<host>:30000/mcp`（nginx 独立监听端口，与业务 Web 物理隔离） | 外部/第三方 Agent | 只读且 `externallyDiscoverable` 的工具 | **不可能**——写工具在 `tools/list` 里就不存在 |
| **Agent 内面** | 进程内（LangChain4j Agent 运行时） | 平台内建 Agent（组包师等） | `AGENT_TOOL_MODULES` 全模块 | 按 Agent 的 `allow_write` 绑定 |
| **特权 stdio 面** | `docker exec -i … java -jar /app/app.jar` | 本机内部工具（hermes/ClawBot） | 全量 57 工具 | 可写，受人类确认闸约束 |

**只读硬边界**：HTTP/SSE 传输在构造时硬编码非特权（`McpServer` 无流构造），任何配置都无法让 30000 端口开出写工具。
特权只能由 stdio 入口以 `MCP_STDIO_PRIVILEGED=true` + `MCP_AGENT_IDENTITY=<身份>` 双门开启，缺身份则启动即拒。

## 2. 生产实际连接（zimupc，2026-09-02 核对）

### 2.1 履约中台自身提供的 MCP

| 项 | 值 |
|---|---|
| 协议面地址 | `http://127.0.0.1:30000/mcp`（另有 `/mcp/sse`、`/mcp/messages`） |
| 协议面鉴权 | `Authorization: Bearer <MCP_HTTP_TOKEN>` |
| 协议面模块 | `MCP_MODULES=masterdata,inventory,orders-read,followup,rawmaterial` |
| Agent/特权面模块 | `AGENT_TOOL_MODULES=messages,orders,masterdata,inventory,procurement,orders-read,bundles-read,followup,control,write,rawmaterial` |
| 特权 stdio 命令 | 见 §4 |

工具计数（2026-09-02 实数，会随开发变动，**以 `list_agent_tools` 实查为准**）：
- 注册面 **54** 个业务工具，由 8 个 provider 提供：`McpDomainReadTools` 14（masterdata/inventory/procurement/orders）、
  `McpReadTools` 13（messages/orders 草稿域）、`McpWriteTools` 8、`McpRawMaterialTools` 8（3 读 5 写）、
  `McpBundleReadTools` 4、`McpFulfillmentWriteTools` 3、`McpOrdersReadTools` 2、`McpControlReadTools` 1、
  `KehuzxRemoteReadTools`（followup，代理 kehuzx）。
- hermes 侧显示 **57**：多出的 3 个是 MCP 协议级伪工具（`list_resources`/`read_resource`/`list_prompts` 一类），
  不是业务能力——对不上数时先想到这个差值。

### 2.2 其他系统提供的 MCP

| 名称 | 传输 | 地址/命令 | 内容 |
|---|---|---|---|
| kehuzx（客户中心） | HTTP | `http://127.0.0.1:29100/mcp`，Bearer token | 客户/需求/订单查询（只读） |
| yuanliaokc（原料仓 WMS） | stdio | `docker exec -i yuanliaokc-mcp python /srv/mcp_server.py` | 原料库存查询 |

> 履约中台的 `rawmaterial` 模块是**另一条路径**：它经后端网关（`YuanliaokcReadGateway`/`WriteGateway`）访问
> yuanliaokc 的 HTTP API，与上面 yuanliaokc 自带的 stdio MCP 是两套东西，互不依赖。

## 3. hermes / ClawBot 的连接（10 个员工微信号）

hermes 装在 `C:\Users\jerry\AppData\Local\hermes\`，网关以计划任务常驻（`hermes gateway status` 查看）。

**两层配置，缺一不可**（这是最容易踩的坑）：

1. `mcp_servers.<name>` —— 服务器定义（连什么、怎么连）
2. `platform_toolsets.weixin` —— **微信频道白名单**，名字不在这个列表里，微信会话就看不到该服务器

CLI 会话（`hermes chat` / `hermes mcp test`）**不过**第 2 层，所以「命令行能测通但微信里没有」是这两层不一致的典型症状。

**配置文件位置**：主配置 `config.yaml`；每个微信号在 `profiles\wx<id>\config.yaml` **各有一份完整独立副本**
（不继承主配置——主配置改了不会传播到 profile，必须逐个改）。花名册见 §5。

已配置的四个服务器（全部 10 个 profile 一致）：`zimu-business`（协议面只读）、`zimu-raw-materials`、
`kehuzx`、`kehuzx-approval`、`zimu-full`（特权面，2026-09-01 开通）。

## 4. 特权 stdio 面的标准连接参数

```
docker exec -i \
  -e MCP_ENABLED=true \
  -e MCP_STDIO_PRIVILEGED=true \
  -e MCP_AGENT_IDENTITY=hermes \
  -e SPRING_MAIN_BANNER_MODE=off \
  -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
  -e LOGGING_FILE_NAME=/tmp/mcp-hermes.log \
  -e JAVA_TOOL_OPTIONS=-Xmx512m \
  -e MCP_HTTP_ENABLED=false \
  -e WECOM_ENABLED=false \
  -e MESSAGE_WORKER_ENABLED=false \
  -e SOURCE_SYNC_AUTO_ENABLED=false \
  -e SCHEDULED_PULL_ENABLED=false \
  -e JD_TRACKING_BACKFILL_ENABLED=false \
  -e FOLLOWUP_ASSIGNMENT_WORKER_ENABLED=false \
  -e SCHEDULED_TASKS_ENABLED=false \
  -e WECOM_EXPORT_WORKER_ENABLED=false \
  -e WECOM_TRACKING_FILE_WORKER_ENABLED=false \
  -e WECOM_CHAT_AGENT_WORKER_ENABLED=false \
  zimu-fulfillment-backend-1 java -jar /app/app.jar
```

每一项都不是装饰：
- **全套 `*_ENABLED=false`**：stdio 进程是一个完整 Spring 实例，不关调度就会跑起 13 条调度线，
  和常驻容器抢外部接口、重复发消息（2026-08-31 事故：漏关 `SCHEDULED_TASKS_ENABLED` 导致孤儿进程刷调度）。
- **`SPRING_MAIN_WEB_APPLICATION_TYPE=none`**：不起 web 容器，进程能随 stdio 关闭而退出，否则 ssh 会话挂死。
- **`JAVA_TOOL_OPTIONS=-Xmx512m`**：无上限 JVM 叠加会打爆 Docker VM（两次事故根因）。
- **禁用 `LOGGING_PATTERN_CONSOLE=`（空值）**：会让 logback 崩溃、进程零输出。

**代价与已知问题**：每次连接冷启一个 JVM（约 9-10 秒，机器繁忙时更久），会话启动的并发发现窗口可能
先行超时把它丢弃（症状：`MCP discovery timed out while 1 server(s) were still connecting: zimu-full`）。
根治方向是加大发现超时或预热常驻，以及给 Docker VM 足够内存（默认 WSL2 只分到 ~2GB）。

## 5. 微信号 ↔ profile 花名册

| 花名 | profile id |
|---|---|
| 测试 | wxf574d0a17011 |
| 范名扬 | wxf6f97e1da1b1 |
| 范名扬1 | wx7e0086df08b5 |
| 大鹏 | wxf866d4819815 |
| 孔令真 | wxa6239d52a7a5 |
| 姚 | wx2762ba68cb1d |
| 张爽 | wxad924de7c09e |
| 刘 | wxdb9e6d9bcc32 |
| 小李 | wx687fef8fcee5 |
| 陈洋 | wx79a287be5001 |

## 6. 写操作的两道闸

1. **人类确认闸**：`submit_jd_outbound`、`cancel_jd_outbound`、`approve_raw_inbound_order`、
   `approve_raw_scrap_order` 四个真实动货/动账的工具必填 `human_confirmation` 参数，值必须精确等于
   「确认」二字（`strip()` 后比较，`确认。`/`ok` 一律拒），否则 422 `HUMAN_CONFIRMATION_REQUIRED`。
   Agent 必须先向用户复述动作、由用户亲自输入，不得代填。判定次序是**先鉴权再过闸**：未认证调用
   只看到鉴权错误，不得从错误码探知闸的存在。建单/成单/路由/拉取不受闸（系统内单据，可改可删）。
2. **京东出库操作人白名单**：`JD_OUTBOUND_AUTHORIZED_OPERATORS`（生产含 `zimu-admin,wecom:jry,hermes`）——
   身份不在名单里，`submit_jd_outbound` / `cancel_jd_outbound` 即使可见也会被拒（提交与取消同一份名单）。

## 7. 排障速查

| 症状 | 大概率原因 | 处置 |
|---|---|---|
| 微信里没有某个 MCP 的工具，但 `hermes mcp test` 能通 | `platform_toolsets.weixin` 白名单没加，或该 profile 的 config.yaml 没同步 | 逐 profile 补两层配置，重启网关 |
| 日志 `MCP discovery timed out … still connecting` | stdio 面 JVM 冷启慢过发现窗口 | 加大发现超时/预热；扩 Docker VM 内存 |
| 微信「未连接」发不出消息 | 网关进程死了（`hermes gateway status` 显示 No gateway process） | `hermes gateway start` |
| 30000 端口看不到写工具 | 设计如此 | 需要写就走特权 stdio 面 |
| `submit_jd_outbound` 报 operator unauthorized | 身份不在白名单 | 加进 `JD_OUTBOUND_AUTHORIZED_OPERATORS` 后重建 backend |
