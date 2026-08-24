package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认门禁引擎（05 决策；T10 写工具静态门禁与 T11 确认前全量复跑共用）：六项阻断 +
 * PII 警告，判定口径：
 * <ol>
 *   <li>结构完整性（必填/空白/长度——record 构造器已强制必填与格式，门禁补长度上限与
 *       空白项复查，覆盖超长提示词等构造器不拦截的场景）；</li>
 *   <li>工具白名单合法性（每个名称必须在 {@link McpToolRegistry} 唯一工具源）；</li>
 *   <li>只读不变式（白名单含写工具且无 allow_write=true → 阻断，07 票读写元数据）；</li>
 *   <li>output_schema 可解析（networknt，{@link JsonSchemaValidator#schemaParses}）；</li>
 *   <li>凭据扫描（提示词会进 DB，红线）；</li>
 *   <li>越权指令扫描（要求写操作/绕过审计）。</li>
 * </ol>
 * PII 扫描仅警告（示例数据可能合理，确认流程高亮，不阻断）。
 *
 * <p>失败隔离：评估意外异常收敛为阻断项（fail-closed，评估不了即不可确认），不外抛；
 * 门禁不参与 Agent 运行路径（运行期守卫是 {@link AgentGuard}），既有运行不受影响。
 */
@Component
public class DefaultAgentGateEngine implements AgentGateEngine {

    /** 结构完整性长度上限（05 决策「结构完整性（必填/类型/长度）」；提示词会进模型上下文与 DB）。 */
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_DESCRIPTION_LENGTH = 4000;
    private static final int MAX_SYSTEM_PROMPT_LENGTH = 32000;
    private static final int MAX_PROMPT_VERSION_LENGTH = 128;
    private static final int MAX_MODEL_REF_LENGTH = 128;

    private final McpToolRegistry registry;

    public DefaultAgentGateEngine(McpToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentGateReport evaluate(AgentDefinition definition) {
        try {
            return evaluateInternal(definition);
        } catch (RuntimeException ex) {
            // 失败隔离：引擎故障收敛为阻断（安全默认），不外抛、不影响既有运行
            return AgentGateReport.blocked(
                    List.of("门禁评估失败: " + ex.getClass().getSimpleName()), List.of());
        }
    }

    private AgentGateReport evaluateInternal(AgentDefinition definition) {
        List<String> blockers = new ArrayList<>();
        List<String> piiWarnings = new ArrayList<>();

        // 1) 结构完整性（必填已由 record 构造器强制；此处补长度上限与空白项复查）
        requireLength("name", definition.name(), MAX_NAME_LENGTH, blockers);
        requireLength("description", definition.description(), MAX_DESCRIPTION_LENGTH, blockers);
        requireLength("system_prompt", definition.systemPrompt(), MAX_SYSTEM_PROMPT_LENGTH, blockers);
        requireLength("prompt_version", definition.promptVersion(), MAX_PROMPT_VERSION_LENGTH, blockers);
        requireLength("model_ref", definition.modelRef(), MAX_MODEL_REF_LENGTH, blockers);

        // 2/3) 工具白名单合法性 + 只读不变式：单次遍历、每个名称只解析一次（07 票读写元数据）
        for (String name : definition.toolNames()) {
            if (name == null || name.isBlank()) {
                blockers.add("tool_names 含空白项");
                continue;
            }
            McpTool tool = registry.find(name).orElse(null);
            if (tool == null) {
                blockers.add("白名单工具未注册: " + name);
                continue;
            }
            if (!tool.readOnly() && !definition.allowWrite()) {
                blockers.add("白名单含写工具但未声明 allow_write=true: " + name);
            }
        }

        // 4) output_schema 可解析（networknt）
        if (definition.outputSchema() != null
                && !JsonSchemaValidator.schemaParses(definition.outputSchema().toString())) {
            blockers.add("output_schema 无法解析为合法 JSON Schema");
        }

        // 5) 凭据扫描（提示词会进 DB，红线）
        blockers.addAll(AgentGateScan.credentialProblems(definition.systemPrompt()));

        // 6) 越权指令扫描
        blockers.addAll(AgentGateScan.escalationProblems(definition.systemPrompt()));

        // PII 警告（不阻断）
        piiWarnings.addAll(AgentGateScan.piiWarnings(definition.systemPrompt()));

        return new AgentGateReport(blockers, piiWarnings);
    }

    private static void requireLength(String field, String value, int maxLength, List<String> blockers) {
        if (value != null && value.length() > maxLength) {
            blockers.add(field + " 超长（上限 " + maxLength + "，实际 " + value.length() + "）");
        }
    }
}
