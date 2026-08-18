package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 一个受管 Agent 的不可变定义（agent-decision-layer 02）。
 *
 * <p>{@code agent_slug} 全局唯一，是注册表查询与审计 operation（{@code agent.{slug}.run}）
 * 的身份键；{@code model_ref} 引用 {@code app.agent.*}（全局模型配置）或按 Agent 覆盖的
 * 模型配置标识（覆盖解析由具体业务 Agent 票落地，本票仅声明字段语义）；{@code tool_names}
 * 是 MCP 工具白名单（引用 MCP 工具名，03 票接入工具执行前仅作声明与审计）。
 *
 * <p>record 的紧凑构造器执行归一化与防御性校验：slug 必须匹配 {@code ^[a-z][a-z0-9-]{0,63}$}，
 * 文本字段 strip 后非空，tool_names 一律防御性拷贝为不可变列表。
 */
public record AgentDefinition(
        String agentSlug,
        String name,
        String description,
        String systemPrompt,
        String promptVersion,
        String modelRef,
        boolean enabled,
        List<String> toolNames) {

    private static final String SLUG_PATTERN = "[a-z][a-z0-9-]{0,63}";

    public AgentDefinition {
        agentSlug = requireSlug(agentSlug);
        name = requireText(name, "name");
        description = requireText(description, "description");
        systemPrompt = requireText(systemPrompt, "system_prompt");
        promptVersion = requireText(promptVersion, "prompt_version");
        modelRef = requireText(modelRef, "model_ref");
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
    }

    public static AgentDefinition of(
            String agentSlug,
            String name,
            String description,
            String systemPrompt,
            String promptVersion,
            String modelRef,
            boolean enabled,
            List<String> toolNames) {
        return new AgentDefinition(
                agentSlug, name, description, systemPrompt, promptVersion, modelRef, enabled, toolNames);
    }

    private static String requireSlug(String slug) {
        String normalized = slug == null ? "" : slug.strip();
        if (!normalized.matches(SLUG_PATTERN)) {
            throw new IllegalArgumentException(
                    "agent_slug 必须匹配 ^[a-z][a-z0-9-]{0,63}$（仅小写），实际: " + slug);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return normalized;
    }
}
