package cn.zimu.fulfillment.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 注册表：代码/配置定义的不可变清单（agent-decision-layer 02）。
 *
 * <p>构造时校验 agent_slug 唯一性；实例一经构造即不可变，按 slug 查询、enabled 判定与
 * 枚举。启停/工具白名单等配置变更不通过本类修改，而是构造新实例并用
 * {@link AgentRegistryChangeAuditor} 对前后实例做 diff 并记录审计事件。
 */
public class AgentRegistry {

    private final Map<String, AgentDefinition> bySlug;

    public AgentRegistry(List<AgentDefinition> definitions) {
        Map<String, AgentDefinition> map = new LinkedHashMap<>();
        if (definitions != null) {
            for (AgentDefinition definition : definitions) {
                if (definition == null) {
                    throw new IllegalArgumentException("AgentDefinition 不能为 null");
                }
                if (map.put(definition.agentSlug(), definition) != null) {
                    throw new IllegalArgumentException("agent_slug 重复: " + definition.agentSlug());
                }
            }
        }
        this.bySlug = Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** 全部 Agent 定义，按构造时的声明顺序。 */
    public List<AgentDefinition> definitions() {
        return List.copyOf(bySlug.values());
    }

    /** 按 slug 查询；不存在返回 null。 */
    public AgentDefinition bySlug(String agentSlug) {
        return agentSlug == null ? null : bySlug.get(agentSlug);
    }

    public boolean has(String agentSlug) {
        return bySlug(agentSlug) != null;
    }

    /** enabled 判定：未注册一律视为未启用（fail-closed）。 */
    public boolean isEnabled(String agentSlug) {
        AgentDefinition definition = bySlug(agentSlug);
        return definition != null && definition.enabled();
    }

    /** 全部 slug，按声明顺序。 */
    public Set<String> slugs() {
        return new LinkedHashSet<>(bySlug.keySet());
    }
}
