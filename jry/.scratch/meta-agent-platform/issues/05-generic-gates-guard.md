# 05 — 通用门禁与守卫泛化设计

**Type:** grilling
**Status:** resolved
**Blocked by:** 02 — 现有四条路径收敛点审计

## Question

已确认方向：草稿提交时平台自动跑「通用门禁」；PII 拒绝/歧义澄清泛化为平台默认 AgentGuard（定义可豁免，默认不豁免）。

待决策点（grilling，一次一个，带推荐答案）：

1. 门禁清单与判定：结构完整性、工具白名单合法性（必须在 McpToolRegistry 中）、只读不变式（默认禁写工具，白名单含写工具时如何处置）、output_schema 可解析、提示词安全检查（PII/凭据/越权指令扫描）——各自判定口径、**阻断 vs 警告**、跑在哪一层（提交时 / 确认时 / 运行时）。
2. AgentGuard 泛化：PII 拒绝与歧义澄清从 DataQueryAgentGuard 提升为平台默认守卫的接口设计、豁免机制（默认不豁免）、与 08 权限隔离的协作边界（守卫是行为约束，权限是访问控制）。

## Answer

1. **门禁清单与判定**（草稿不可确认的条件）：**阻断项** = 结构完整性（必填/类型/长度）、工具白名单合法性（必须在 McpToolRegistry）、只读不变式（白名单含写工具且无 `allow_write=true`）、output_schema 可解析（networknt）、凭据扫描（密钥/Token 模式——提示词会进 DB，红线）、越权指令（要求写操作/绕过审计的指令）；**仅警告** = PII 扫描（示例数据含手机号等可能是合理内容，人工确认时高亮，不阻断）。
2. **门禁时机（三时机分工）**：① 写工具内嵌**静态门禁**（结构/白名单/只读/凭据/越权，毫秒级无需模型，不过即拒绝落库——06 的服务端校验扩展为完整清单）；② **确认动作前全量复跑**（静态项 + INVARIANT stub 评测 + output_schema 解析，全绿才可确认——防「提交后内容被人工编辑导致状态过期」）；③ **运行期守卫**（AgentGuard 每 run 生效，权限在工具调用时强制——08）。
3. **AgentGuard 泛化**：平台默认守卫链 = **[PII 拒绝]**（输入含客户/收货人/手机号/地址模式 → outcome=REJECTED 转人工，不进模型）；**歧义澄清不并入平台默认链**——数据查询类的参数歧义是领域行为，由该类 Agent 提示词 + NEEDS_INPUT outcome 承载（04 已定），DataQueryAgentGuard 保留为该校验器实现；**豁免** = `agent_definitions.guard_exemptions` 枚举（默认空 = [PII] 生效）；与 08 边界：守卫是行为约束（模型调用前对输入判定），权限是访问控制（工具调用时强制），互不替代。

**Schema 增量**：`agent_definitions` 新增 `guard_exemptions`（枚举数组，默认空）。
