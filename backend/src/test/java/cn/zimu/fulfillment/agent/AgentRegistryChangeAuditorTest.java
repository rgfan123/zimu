package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 02 — 注册表变更审计验收（agent-decision-layer 02）：启停/工具白名单/模型引用等配置变更
 * 在 before/after 两实例 diff 后逐条落 AGENT 审计（service=agent, operation=agent.registry.changed）。
 */
class AgentRegistryChangeAuditorTest {

    private final AuditLogService audits = mock(AuditLogService.class);
    private final AgentRegistryChangeAuditor auditor = new AgentRegistryChangeAuditor(audits);

    private static AgentDefinition purchasing(boolean enabled, List<String> tools) {
        return AgentDefinition.of(
                "purchasing-comparison",
                "采购比价",
                "d",
                "s",
                "purchasing-v1",
                "app.agent",
                enabled,
                tools);
    }

    private static AgentDefinition intentRecognition(boolean enabled) {
        return AgentDefinition.of(
                "intent-recognition", "意图识别", "d", "s", "intent-v1", "app.agent", enabled, List.of());
    }

    @Test
    void enabledFlipProducesEnableAndDisableAuditEvents() {
        AgentRegistry before = new AgentRegistry(List.of(purchasing(true, List.of())));
        AgentRegistry after = new AgentRegistry(List.of(purchasing(false, List.of())));

        List<AgentRegistryChangeAuditor.Change> changes = auditor.diff(before, after);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).kind()).isEqualTo(AgentRegistryChangeAuditor.Kinds.DISABLED);
        assertThat(changes.get(0).agentSlug()).isEqualTo("purchasing-comparison");

        auditor.recordChanges(before, after);
        AuditLogService.AuditCommand command = lastAuditCommand();
        assertThat(auditField(command, "service")).isEqualTo("agent");
        assertThat(auditField(command, "operation")).isEqualTo("agent.registry.changed");
        assertThat(auditField(command, "businessCode"))
                .isEqualTo(AgentRegistryChangeAuditor.Kinds.DISABLED);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payload =
                (java.util.Map<String, Object>) auditField(command, "requestPayload");
        assertThat(payload).containsEntry("agent_slug", "purchasing-comparison");
        assertThat(payload).containsEntry("kind", AgentRegistryChangeAuditor.Kinds.DISABLED);
    }

    @Test
    void toolWhitelistChangeProducesAuditEvent() {
        AgentRegistry before = new AgentRegistry(List.of(purchasing(true, List.of("t1"))));
        AgentRegistry after = new AgentRegistry(List.of(purchasing(true, List.of("t1", "t2"))));

        List<AgentRegistryChangeAuditor.Change> changes = auditor.diff(before, after);

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).kind())
                .isEqualTo(AgentRegistryChangeAuditor.Kinds.TOOL_WHITELIST_CHANGED);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> detail = changes.get(0).detail();
        assertThat(detail.get("before")).isEqualTo(List.of("t1"));
        assertThat(detail.get("after")).isEqualTo(List.of("t1", "t2"));

        auditor.recordChanges(before, after);
        verify(audits).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addedAndRemovedAgentsProduceAuditEvents() {
        AgentRegistry before = new AgentRegistry(List.of(purchasing(true, List.of())));
        AgentRegistry after = new AgentRegistry(List.of(intentRecognition(true)));

        List<AgentRegistryChangeAuditor.Change> changes = auditor.diff(before, after);

        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).agentSlug()).isEqualTo("purchasing-comparison");
        assertThat(changes.get(0).kind()).isEqualTo(AgentRegistryChangeAuditor.Kinds.REMOVED);
        assertThat(changes.get(1).agentSlug()).isEqualTo("intent-recognition");
        assertThat(changes.get(1).kind()).isEqualTo(AgentRegistryChangeAuditor.Kinds.ADDED);
    }

    @Test
    void noChangeProducesNoAudit() {
        AgentRegistry before = new AgentRegistry(List.of(purchasing(true, List.of("t1"))));
        AgentRegistry after = new AgentRegistry(List.of(purchasing(true, List.of("t1"))));

        assertThat(auditor.diff(before, after)).isEmpty();

        auditor.recordChanges(before, after);
        verify(audits, never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void multipleFieldChangesProduceOneAuditPerKind() {
        AgentRegistry before = new AgentRegistry(List.of(AgentDefinition.of(
                "slug-a", "n", "d", "s", "v1", "app.agent", true, List.of("t1"))));
        AgentRegistry after = new AgentRegistry(List.of(AgentDefinition.of(
                "slug-a", "n", "d", "s", "v2", "app.agent.overrides.slug-a", false, List.of("t2"))));

        List<AgentRegistryChangeAuditor.Change> changes = auditor.diff(before, after);

        assertThat(changes).hasSize(4);
        assertThat(changes).extracting(AgentRegistryChangeAuditor.Change::kind).containsExactly(
                AgentRegistryChangeAuditor.Kinds.DISABLED,
                AgentRegistryChangeAuditor.Kinds.MODEL_REF_CHANGED,
                AgentRegistryChangeAuditor.Kinds.PROMPT_VERSION_CHANGED,
                AgentRegistryChangeAuditor.Kinds.TOOL_WHITELIST_CHANGED);
    }

    @Test
    void versionTransitionProducesRetiredThenActivatedEvents() {
        AgentDefinition v1 = AgentDefinition.of(
                "slug-a", "n", "d", "s", "v1", "app.agent", true, List.of("t1"));
        AgentDefinition v2 = AgentDefinition.of(
                "slug-a", "n", "d", "s", "v2", "app.agent", true, List.of("t1"),
                2, AgentStatus.ACTIVE, "human-1", OffsetDateTime.now(), false, List.of(), null);

        AgentRegistry before = new AgentRegistry(List.of(v1));
        AgentRegistry after = new AgentRegistry(List.of(v2));

        List<AgentRegistryChangeAuditor.Change> changes = auditor.diff(before, after);

        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).agentSlug()).isEqualTo("slug-a");
        assertThat(changes.get(0).kind()).isEqualTo(AgentRegistryChangeAuditor.Kinds.RETIRED);
        assertThat(changes.get(0).detail()).containsEntry("version", 1);
        assertThat(changes.get(1).agentSlug()).isEqualTo("slug-a");
        assertThat(changes.get(1).kind()).isEqualTo(AgentRegistryChangeAuditor.Kinds.ACTIVATED);
        assertThat(changes.get(1).detail()).containsEntry("version", 2);
    }

    @Test
    void versionTransitionIsAuditedWithBothLifecycleKinds() {
        AgentDefinition v1 = AgentDefinition.of(
                "slug-a", "n", "d", "s", "v1", "app.agent", true, List.of("t1"));
        AgentDefinition v2 = AgentDefinition.of(
                "slug-a", "n", "d", "s", "v2", "app.agent", true, List.of("t1"),
                2, AgentStatus.ACTIVE, "human-1", OffsetDateTime.now(), false, List.of(), null);

        auditor.recordChanges(new AgentRegistry(List.of(v1)), new AgentRegistry(List.of(v2)));

        org.mockito.ArgumentCaptor<AuditLogService.AuditCommand> captor =
                org.mockito.ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits, org.mockito.Mockito.times(2)).record(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(cmd -> auditField(cmd, "businessCode"))
                .containsExactly(
                        AgentRegistryChangeAuditor.Kinds.RETIRED,
                        AgentRegistryChangeAuditor.Kinds.ACTIVATED);
    }

    private AuditLogService.AuditCommand lastAuditCommand() {
        ArgumentCaptor<AuditLogService.AuditCommand> captor =
                ArgumentCaptor.forClass(AuditLogService.AuditCommand.class);
        verify(audits).record(captor.capture());
        return captor.getValue();
    }

    private static Object auditField(AuditLogService.AuditCommand command, String field) {
        try {
            java.lang.reflect.Field f =
                    AuditLogService.AuditCommand.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(command);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("无法读取审计命令字段 " + field, ex);
        }
    }
}
