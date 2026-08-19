package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 注册表配置变更审计（agent-decision-layer 02）。
 *
 * <p>对前后两个 {@link AgentRegistry} 实例做按 slug 的 diff，为每个变更（新增/移除/启停/
 * 模型引用/提示词版本/工具白名单/版本生命周期 ACTIVATED·RETIRED）记录一条 AGENT 审计
 * （service=agent, operation=agent.registry.changed）。注册表本身不可变：启停、工具白名单等
 * 变更只能通过新实例体现，本审计器在变更发生时（如配置重新加载/部署/草稿确认）被调用，
 * 保证变更留痕可追。版本生命周期（03）：同 slug 生效版本被替换时，旧版本 RETIRED +
 * 新版本 ACTIVATED（版本切换吸收字段级 diff）。
 */
public class AgentRegistryChangeAuditor {

    private static final String OPERATION = "agent.registry.changed";

    /** 变更类型，同时作为审计 business_code。 */
    public static final class Kinds {
        public static final String ADDED = "AGENT_REGISTRY_ADDED";
        public static final String REMOVED = "AGENT_REGISTRY_REMOVED";
        public static final String ENABLED = "AGENT_REGISTRY_ENABLED";
        public static final String DISABLED = "AGENT_REGISTRY_DISABLED";
        public static final String MODEL_REF_CHANGED = "AGENT_REGISTRY_MODEL_REF_CHANGED";
        public static final String PROMPT_VERSION_CHANGED = "AGENT_REGISTRY_PROMPT_VERSION_CHANGED";
        public static final String TOOL_WHITELIST_CHANGED = "AGENT_REGISTRY_TOOL_WHITELIST_CHANGED";
        /** 版本生命周期（03）：同 slug 生效版本被新版本替换（旧版本 retired + 新版本 activated）。 */
        public static final String ACTIVATED = "AGENT_REGISTRY_ACTIVATED";
        public static final String RETIRED = "AGENT_REGISTRY_RETIRED";

        private Kinds() {}
    }

    /** 单个 Agent 的一条变更：kind 见 {@link Kinds}，detail 含变更前后值（可能缺字段）。 */
    public record Change(String agentSlug, String kind, Map<String, Object> detail) {}

    private final AuditLogService audits;

    public AgentRegistryChangeAuditor(AuditLogService audits) {
        this.audits = audits;
    }

    /** 比较 before/after，为每个变更记录一条审计；无变化不产生任何审计。 */
    public void recordChanges(AgentRegistry before, AgentRegistry after) {
        for (Change change : diff(before, after)) {
            record(change);
        }
    }

    /** 纯 diff，不写审计；便于测试与外部复用。 */
    public List<Change> diff(AgentRegistry before, AgentRegistry after) {
        AgentRegistry prev = before == null ? new AgentRegistry(List.of()) : before;
        AgentRegistry next = after == null ? new AgentRegistry(List.of()) : after;
        Set<String> slugs = new LinkedHashSet<>(prev.slugs());
        slugs.addAll(next.slugs());

        List<Change> changes = new ArrayList<>();
        for (String slug : slugs) {
            AgentDefinition oldDef = prev.bySlug(slug);
            AgentDefinition newDef = next.bySlug(slug);
            if (oldDef == null) {
                changes.add(new Change(slug, Kinds.ADDED, Map.of(
                        "enabled", newDef.enabled(),
                        "model_ref", newDef.modelRef(),
                        "prompt_version", newDef.promptVersion(),
                        "tool_names", newDef.toolNames())));
            } else if (newDef == null) {
                changes.add(new Change(slug, Kinds.REMOVED, Map.of(
                        "enabled", oldDef.enabled(),
                        "model_ref", oldDef.modelRef(),
                        "prompt_version", oldDef.promptVersion(),
                        "tool_names", oldDef.toolNames())));
            } else if (oldDef.version() != newDef.version()) {
                // 版本生命周期（03 全快照链）：生效版本被新版本替换 = 旧版本 retired + 新版本 activated。
                // 版本切换即整快照替换，字段级 diff 被版本化快照语义吸收，不再逐字段输出。
                changes.add(new Change(slug, Kinds.RETIRED, Map.of(
                        "version", oldDef.version(),
                        "status", oldDef.status().toDb())));
                changes.add(new Change(slug, Kinds.ACTIVATED, Map.of(
                        "version", newDef.version(),
                        "status", newDef.status().toDb(),
                        "prompt_version", newDef.promptVersion(),
                        "allow_write", newDef.allowWrite())));
            } else {
                if (oldDef.enabled() != newDef.enabled()) {
                    changes.add(new Change(slug,
                            newDef.enabled() ? Kinds.ENABLED : Kinds.DISABLED,
                            Map.of("enabled", newDef.enabled())));
                }
                if (!oldDef.modelRef().equals(newDef.modelRef())) {
                    changes.add(new Change(slug, Kinds.MODEL_REF_CHANGED,
                            Map.of("before", oldDef.modelRef(), "after", newDef.modelRef())));
                }
                if (!oldDef.promptVersion().equals(newDef.promptVersion())) {
                    changes.add(new Change(slug, Kinds.PROMPT_VERSION_CHANGED,
                            Map.of("before", oldDef.promptVersion(), "after", newDef.promptVersion())));
                }
                if (!oldDef.toolNames().equals(newDef.toolNames())) {
                    changes.add(new Change(slug, Kinds.TOOL_WHITELIST_CHANGED,
                            Map.of("before", oldDef.toolNames(), "after", newDef.toolNames())));
                }
            }
        }
        return List.copyOf(changes);
    }

    private void record(Change change) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .operator("agent-config")
                .actorType(AuditActorType.AGENT)
                .service("agent")
                .operation(OPERATION)
                .requestPayload(Map.of("agent_slug", change.agentSlug(), "kind", change.kind()))
                .responsePayload(change.detail())
                .businessCode(change.kind()));
    }
}
