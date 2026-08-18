# 01 — 启动 MCP stdio server 并冒烟验证

**Type:** implementation

**What to build:** 让 fulfillment-hub-mcp 能以 stdio 模式在本地真实启动：应用以 MCP 模式运行（stdout 专用于协议帧，应用日志重定向到文件），一个最小 MCP 客户端能完成协议握手、枚举工具并实际调用一个只读工具拿到业务数据。运行方式固化为可复用底座，供后续票直接使用。

**Blocked by:** None — can start immediately

Status: resolved

**Claimed by:** dsh-agent

- [x] 以 MCP 模式启动应用（依赖本地 PostgreSQL）后，进程能完成 initialize 握手，返回 serverInfo=fulfillment-hub-mcp、协议版本 2025-03-26。
- [x] tools/list 返回注册表中的全部既有工具（读 13 / 写 4 / 领域只读 11，共 28 个）。
- [x] tools/call 调用一个只读工具返回业务数据；stdout 无应用日志污染（日志已重定向到文件）。
- [x] ping / shutdown / 未知方法按协议正确响应。
- [x] 运行方式固化（启动命令与客户端冒烟脚本，或等价文档），后续票可直接复用同一底座。

## Answer

- 运行方式：`MCP_SPAWN=docker node tmp/mcp-smoke/client.mjs`（在 backend 容器内以 MCP 模式跑 `/app/app.jar`，`--server.port=0` 避免与 REST 实例端口冲突，`--logging.console.enabled=false` 保证 stdout 只承载协议帧，日志写 `/tmp/mcp-smoke.log`）。冒烟脚本在 `tmp/mcp-smoke/client.mjs`，驱动 initialize → notifications/initialized → tools/list → tools/call → ping → unknown → shutdown 全流程并断言。
- 实测结果：initialize 返回 serverInfo=fulfillment-hub-mcp / 0.1.0、协议 2025-03-26；tools/list 返回 28 个工具（读 13 / 写 4 / 领域只读 11）；tools/call `list_fulfillment_providers` 返回真实数据（含京东云仓 JD_WAREHOUSE）；ping / METHOD_NOT_FOUND(-32601) / shutdown 均正确；stdout 无日志污染。SMOKE PASS。
- 踩坑记录：宿主机直连 compose postgres（172.24.0.4:5432）在 JDBC 认证阶段 EOF（原因未明，postgres 无认证失败日志；容器内 psql 正常），改在 backend 容器内跑绕开宿主网络；`?sslmode=disable` 与 `--logging.console.enabled=false` 是必要参数。
