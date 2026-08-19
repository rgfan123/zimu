# 07 — MCP 权限隔离（读写元数据 + 调用期复核 + stdio 只读）

**What to build:** 权限强制点落地（08 决策）：① `McpTool` 增加读写元数据（形态在「接口默认方法 vs 注解 vs 注册表侧配置」中取其一，使「默认禁写」成为可判定不变式）；② **Agent 面调用期复核**——`AgentToolInvoker.execute` 对每次工具调用按绑定白名单复核（防旁路真强制点；现状只有未注册名报错、不是权限判定）；③ **stdio 面一期收紧为只读**——`toolsList` / `handleToolCall` 过滤写工具（外部客户端共用全局 identity、无 per-agent 权限）；④ `allow_write` 接入注册表判定（白名单含写工具且无 allow_write=true → 绑定期拒绝）；⑤ 两个不变式测试的 `WRITE_TOOL_NAMES` 手抄常量改为向注册表按读写元数据查询（防写工具集合增长后静默漏检）。

**Blocked by:** 02 — 注册表切 DB 真源（设计源：meta-agent-platform 票 08）。

**Status:** resolved
**GitHub:** https://github.com/rgfan123/zimu/issues/8
**Claimed by:** zed-agent (2026-08-19)
**Resolved by:** zed-agent (2026-08-19)

- [x] 写工具零调用不变式可判定（基于读写元数据）；stdio 面 tools/list 无写工具、调用写工具被拒
- [x] 调用期复核拒绝越权调用（白名单外工具即使注册存在也拒绝）并留审计
- [x] allow_write 判定生效；不变式测试不再手抄清单
- [x] 既有 MCP 协议测试（stdio 路径）适配后全绿

## Answer

**交付**（commit `05ce4f4`，独立提交；含 CONTEXT.md 边界同步）：

1. **读写元数据（①）**：`McpTool.readOnly()` 默认 true（「默认禁写」平台可判定不变式）；`SimpleTool` 增 5 参构造（默认只读），`McpWriteTools` 六个写工具显式 `readOnly=false`；`McpToolRegistry.writeToolNames()` 按元数据查询。
2. **Agent 面调用期复核（②）**：`AgentToolInvoker` 携带绑定白名单，`execute` 每次调用复核——白名单外工具即使注册存在也拒绝（`TOOL_NOT_AUTHORIZED`/403 信封，不透出注册表细节），拒绝经统一 `recordToolCall(success=false)` 落 `agent_tool_calls` FAILED 观测行（留审计）；未注册名仍走 `MCP_INTERNAL_ERROR`（注册表漂移兜底，工具名随观测行留痕，两案例语义在 Javadoc 注明）。
3. **stdio 面只读（③）**：`McpServer.toolsList` 只暴露只读工具；`handleToolCall` 对写工具按无效请求拒绝（`-32602` read-only restricted，先于身份/幂等处理——只读接口上写工具不存在，认证语义只对暴露工具生效）。
4. **allow_write 判定（④）**：`AgentToolBindingFactory.bind(runId, toolNames, allowWrite)` 白名单含写工具且非 true → 绑定期 fail-fast；门面传 `definition.allowWrite()`；2 参便捷重载按 fail-closed（false）委托，仅供全只读白名单调用方/测试。
5. **不变式测试改查注册表（⑤）**：`DataQueryAgentDefinitionTest` / `ProcurementPriceAgentInvariantTest` / `McpProtocolAcceptanceTest` 写工具断言一律 `registry.writeToolNames()`，手抄常量删除。
6. **测试适配**：stdio 写流程八例改经 Agent 面（同一注册表/身份/审计路径，`agentWriteCall` 走真实绑定工厂 allowWrite=true）；新增 stdio 写工具拒绝（无副作用）测试、越权拒绝+FAILED 观测断言、绑定期拒绝 fail-fast 测试；守卫纯逻辑拆出 `DataQueryAgentGuardTest`（容器套件只留注册表相关断言）；记录式绑定工厂提取 `McpToolTestSupport.recordingBindingFactory`（覆写 3 参 bind，2 参经虚分派，消除双测试重复）。

**测试**：agent + mcp 包全量 216 例绿；`McpProtocolAcceptanceTest` 15 例（含新拒绝断言）全绿。

**评审结论**（/code-review，基准 af3b16c，Standards+Spec 双轴）：
- Spec：越权拒绝「留审计」已实现（FAILED 观测行）——首轮缺该路径断言，已补（mock observability 捕获 ToolCall）；协议发现测试手抄写清单复种 ⑤ 脆弱点，已改查注册表；`DataQueryAgentDefinitionTest` 升级容器套件拖入纯逻辑断言，已拆分；未注册 vs 越权两案例信封语义已在 Javadoc 注明（权限判定只对注册工具生效，未注册名属漂移兜底）；stdio 拒绝先于身份校验已注释说明。
- Standards：0 硬违规；记录式绑定工厂双处重复已提取；`internalError(detail)` 死参数已删除（detail 无落点，工具名随观测行留痕）；CONTEXT.md 边界（MCP 写面、meta-agent allow_write 例外）已同步 08 决策。
