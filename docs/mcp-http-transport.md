# MCP 外部 HTTP / SSE 传输接入说明

面向：需要把子牧 MCP 工具接入某个外部 Agent 平台的人（那个平台要求填「传输协议」+ URL）。

子牧 MCP 原本只有 stdio 传输（容器内 `docker exec -i ... java -jar app.jar`），只能被本机/SSH 直连的客户端使用。本文档描述的是新增的**对外 HTTP/SSE 传输面**——同一套工具、同一套协议分发逻辑，换了一层收发方式，专供无法用 stdio 接入的外部平台使用。

## 1. 外部平台上要填什么

大多数「MCP 接入」表单会问两件事：**传输协议**、**URL**（有的还会分开问「服务器地址」和「鉴权方式/Header」）。

| 表单里的字段 | 填什么 |
|---|---|
| 传输协议 | 优先选 **Streamable HTTP**（有些平台写成 `HTTP` / `Streamable HTTP`）；只有 Streamable HTTP 不在下拉框里时才选 **SSE** |
| URL（选 Streamable HTTP 时） | `http://114.244.13.53:30000/mcp` |
| URL（选 SSE 时） | `http://114.244.13.53:30000/mcp/sse` |
| 鉴权 / Authorization / Bearer Token | 见下方「鉴权」一节；不要把 token 明文粘贴进聊天记录或工单，只填进该平台自己的密钥管理界面 |

**这两个地址走的是独立的 30000 端口**，不是子牧业务 Web 用的 28443/80——端口隔离是故意的：30000 上除了 `/mcp` 系列路径之外什么都没有，业务 API 和管理界面一律不会从这个端口暴露。

## 2. 两种协议怎么选、有什么区别

| | Streamable HTTP（推荐） | 老版 SSE（兼容） |
|---|---|---|
| 规范版本 | MCP 2025-03-26 | MCP 2024-11-05（上一代） |
| 端点数量 | 1 个：`POST /mcp`（收请求），`GET /mcp` 可选（建流） | 2 个：`GET /mcp/sse`（建流）+ `POST /mcp/messages?sessionId=...`（发消息） |
| 请求/响应关系 | 一次 POST 一次 JSON 响应，简单直接 | POST 只回 202，真正的响应从 GET 建立的那条 SSE 流里以 `message` 事件推回来 |
| 什么时候必须用老 SSE | 外部平台的下拉框里没有 Streamable HTTP 选项，只有 SSE 一种时 |

两条路径背后是**同一个** JSON-RPC 分发实现（`McpServer.handleRequest`）——和现在生产在跑的 stdio 传输一模一样的协议行为：同样的 `initialize` / `tools/list` / `tools/call`，同样的只读收紧（写工具一律拒绝，见第 4 节),同样的工具集合。选哪种传输不影响能调用哪些工具、返回什么结果。

## 3. 鉴权：Bearer Token

对外的 HTTP/SSE 端点用 **Bearer Token** 校验，不是 Basic Auth（外部 MCP 客户端软件通常只支持 Bearer，不支持网关级 Basic Auth）：

```
Authorization: Bearer <MCP_HTTP_TOKEN 的值>
```

- Token 由运维在部署环境变量 `MCP_HTTP_TOKEN` 里配置（占位符，**不要把真实值写进任何文档/代码/工单**，问运维要，或自己生成一个高强度随机串配置上去）。
- 外部平台的表单如果单独有「Token」/「API Key」/「Authorization」字段，直接填 token 值本身（**不要**带 `Bearer ` 前缀，平台通常会自己拼）；如果表单让你填完整 Header，才需要带上 `Bearer ` 前缀。
- 校验失败（token 缺失/错误）：所有端点一律返回 `401`。
- **`MCP_HTTP_TOKEN` 未配置时，`/mcp` 与 `/mcp/sse` 这些端点直接不存在**（HTTP 404），不是「放行不鉴权」——这是故意的 fail-closed 设计，运维忘配 token 时不会变成裸奔的公网入口。
- token 不会出现在任何日志、审计记录或错误响应里（校验逻辑内置常数时间比较，且异常消息不拼接 token 明文）。

## 4. 通过这条通道能做什么、不能做什么

- **只能调只读工具**。与现在生产在跑的 stdio 传输完全一致（08 决策）：`tools/list` 只列只读工具，`tools/call` 调写工具一律返回协议层错误（`-32602`，消息含 `read-only`），不会执行、不会产生任何审计记录或副作用。
- 具体有哪些只读工具、每个工具的参数 schema，用 `tools/list` 现场查（工具集合会随子牧迭代增减，不在这里手抄一份容易过期的清单）。
- 想调写操作（下单确认、状态变更等），仍然只能走既有的 Agent 面（内部工具绑定 + 服务端注入身份），这条对外 HTTP/SSE 通道不提供。

## 5. 开关与配置（给运维/部署方看）

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| 传输总开关 | `MCP_HTTP_ENABLED` | `false` | 不显式打开就完全不注册这两套端点，不新增攻击面 |
| Bearer token | `MCP_HTTP_TOKEN` | 空 | 未配置时端点不注册（见第 3 节）；生产环境务必配置为高强度随机串，**不要**复用 `MCP_AGENT_IDENTITY` 或其他任何已有凭据 |

后端配置对应 `app.mcp.http.enabled` / `app.mcp.http.token`（`backend/src/main/resources/application.yml`）。

## 6. 网络路径（部署侧参考）

```
外部 MCP 客户端
    │  http://114.244.13.53:30000/mcp        （或 /mcp/sse + /mcp/messages）
    ▼
光猫端口映射：公网 30000 → 192.168.1.22:30000
    ▼
nginx 容器 zimu-fulfillment-nginx-1，独立 server 块 listen 30000
  （只认 /mcp 前缀，其余路径一律 404；不套 Basic Auth；SSE 相关：
   proxy_buffering off / proxy_read_timeout 3600s / Connection ''）
    ▼
backend 容器 zimu-fulfillment-backend-1:8080
  （Spring MVC 控制器 McpStreamableHttpController / McpLegacySseController，
   Bearer token 校验通过后复用 stdio 面同一套 McpServer 协议分发逻辑）
```

**需要在生产 compose 上补的端口映射**（本次改动不动生产 compose 文件，由运维手动加）：

```yaml
# nginx 服务的 ports 下新增一行
- "30000:30000"
```

**需要在光猫（路由器）上加的端口映射**：外部 `30000` → 内部 `192.168.1.22:30000`（与现有 `28443 → 80` 的映射并列，走同一台路由器的同一个配置页面）。

## 7. 涉及的代码位置（给后续维护者）

- 协议分发核心（stdio 与 HTTP/SSE 共用）：`backend/src/main/java/cn/zimu/fulfillment/mcp/McpServer.java`（`handleRequest(String)` 方法）
- HTTP/SSE 传输面（新增，全部在这个包下）：`backend/src/main/java/cn/zimu/fulfillment/mcp/http/`
  - `McpHttpTransportCondition`：注册条件（开关 + token 都满足才注册端点）
  - `McpHttpTokenAuthenticator`：Bearer token 校验
  - `McpHttpJsonRpcHandler`：包一层 `McpServer`，两个控制器共用
  - `McpStreamableHttpController`：`POST /mcp` + `GET /mcp`
  - `McpLegacySseController` + `McpSseSessionRegistry`：`GET /mcp/sse` + `POST /mcp/messages`
- nginx 路由：`docker/nginx/default.conf`（独立 `listen 30000` server 块）
- 配置项：`backend/src/main/resources/application.yml`（`app.mcp.http.*`）
- 测试：`backend/src/test/java/cn/zimu/fulfillment/mcp/http/`（含条件穷举、鉴权穷举、端到端鉴权/协议一致性/老 SSE 往返、token 缺失时 404 的独立场景）；stdio 回归见 `backend/src/test/java/cn/zimu/fulfillment/mcp/McpProtocolAcceptanceTest.java`（未改动，保持绿）
