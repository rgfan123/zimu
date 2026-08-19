package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一个受管 Agent 的不可变定义（agent-decision-layer 02；meta-agent-platform 03 扩展）。
 *
 * <p>{@code agent_slug} 全局唯一，是注册表查询与审计 operation（{@code agent.{slug}.run}）
 * 的身份键；{@code model_ref} 引用 {@code app.agent.*}（全局模型配置）或按 Agent 覆盖的
 * 模型配置标识；{@code tool_names} 是 MCP 工具白名单（一期白名单即权限 profile，08 决策）。
 *
 * <p>版本链字段（03 决策，DB 真源 {@code app.agent_definitions}）：{@code version} 从 1
 * 递增、每次修改追加全快照行；{@code status} 三态（draft/active/retired，无回边）；
 * {@code activated_by}/{@code activated_at} 记录确认事实（与 status='active' 同事务）；
 * {@code allow_write} 为写工具白名单开关（默认 false，仅 meta-agent 为 true）；
 * {@code guard_exemptions} 为平台默认守卫（PII 拒绝）的豁免枚举数组（默认空 = 生效）；
 * {@code output_schema} 为定义携带的输出 JSON schema（T04 Adapter 动态约束 + 客户端校验）。
 *
 * <p>record 的紧凑构造器执行归一化与防御性校验：slug 必须匹配 {@code ^[a-z][a-z0-9-]{0,63}$}，
 * 文本字段 strip 后非空，tool_names / guard_exemptions 一律防御性拷贝为不可变列表。
 * 8 参 {@link #of} 工厂保留既有调用面（等价 version=1 / status=ACTIVE / allow_write=false /
 * 无豁免 / 无 output_schema 的活跃版本），供测试与旧调用点使用。
 */
public record AgentDefinition(
        String agentSlug,
        String name,
        String description,
        String systemPrompt,
        String promptVersion,
        String modelRef,
        boolean enabled,
        List<String> toolNames,
        int version,
        AgentStatus status,
        String activatedBy,
        OffsetDateTime activatedAt,
        boolean allowWrite,
        List<String> guardExemptions,
        JsonNode outputSchema) {

    private static final String SLUG_PATTERN = "[a-z][a-z0-9-]{0,63}";

    public AgentDefinition {
        agentSlug = requireSlug(agentSlug);
        name = requireText(name, "name");
        description = requireText(description, "description");
        systemPrompt = requireText(systemPrompt, "system_prompt");
        promptVersion = requireText(promptVersion, "prompt_version");
        modelRef = requireText(modelRef, "model_ref");
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须为正整数，实际: " + version);
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为 null");
        }
        guardExemptions = guardExemptions == null ? List.of() : List.copyOf(guardExemptions);
    }

    /** 既有 8 参调用面：等价于一个 version=1 的 active 定义（无写权限、无守卫豁免、无输出 schema）。 */
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
                agentSlug,
                name,
                description,
                systemPrompt,
                promptVersion,
                modelRef,
                enabled,
                toolNames,
                1,
                AgentStatus.ACTIVE,
                null,
                null,
                false,
                List.of(),
                null);
    }

    /** 全量构造（DB 真源加载/草稿确认路径）。 */
    public static AgentDefinition of(
            String agentSlug,
            String name,
            String description,
            String systemPrompt,
            String promptVersion,
            String modelRef,
            boolean enabled,
            List<String> toolNames,
            int version,
            AgentStatus status,
            String activatedBy,
            OffsetDateTime activatedAt,
            boolean allowWrite,
            List<String> guardExemptions,
            JsonNode outputSchema) {
        return new AgentDefinition(
                agentSlug,
                name,
                description,
                systemPrompt,
                promptVersion,
                modelRef,
                enabled,
                toolNames,
                version,
                status,
                activatedBy,
                activatedAt,
                allowWrite,
                guardExemptions,
                outputSchema);
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
