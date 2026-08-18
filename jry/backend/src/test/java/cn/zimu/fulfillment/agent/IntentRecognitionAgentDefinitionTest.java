package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 07 — 意图识别 Agent 定义与注册（agent-decision-layer 07）：注册表可见、默认 enabled、
 * 可启停（构造新注册表实例）、model_ref 指向消息解释器配置、提示词版本与
 * app.message-interpreter.prompt-version 对齐、工具白名单为空（单次分类接缝）。
 * 纯单元测试，不依赖 Spring 上下文与数据库。
 */
class IntentRecognitionAgentDefinitionTest {

    private final IntentRecognitionAgentConfiguration configuration =
            new IntentRecognitionAgentConfiguration();

    @Test
    void definitionIsRegisteredWithFixedIdentity() {
        AgentDefinition definition = configuration.intentRecognitionAgentDefinition("interp-v1");

        assertThat(definition.agentSlug()).isEqualTo("intent-recognition");
        assertThat(definition.name()).isEqualTo("意图识别");
        assertThat(definition.description()).isEqualTo("企业微信消息意图分类与分流");
        assertThat(definition.modelRef()).isEqualTo("app.message-interpreter");
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.toolNames()).isEmpty();
        assertThat(definition.systemPrompt()).contains("意图分类与分流").contains("PROMPT_V1");
    }

    @Test
    void promptVersionMirrorsMessageInterpreterConfiguredVersion() {
        AgentDefinition definition = configuration.intentRecognitionAgentDefinition("interp-v7");

        assertThat(definition.promptVersion()).isEqualTo("interp-v7");
    }

    @Test
    void promptVersionFallsBackToFixedSemanticsWhenInterpreterUnconfigured() {
        AgentDefinition definition = configuration.intentRecognitionAgentDefinition("");

        assertThat(definition.promptVersion()).isEqualTo("intent-recognition-v1");
    }

    @Test
    void registrySeesAgentAsEnabledByDefault() {
        AgentRegistry registry = new AgentRegistry(List.of(configuration.intentRecognitionAgentDefinition("v1")));

        assertThat(registry.has("intent-recognition")).isTrue();
        assertThat(registry.isEnabled("intent-recognition")).isTrue();
    }

    @Test
    void registryToggleDisablesObservationViewWithoutRemovingAgent() {
        AgentDefinition enabled = configuration.intentRecognitionAgentDefinition("v1");
        AgentDefinition disabled = AgentDefinition.of(
                enabled.agentSlug(),
                enabled.name(),
                enabled.description(),
                enabled.systemPrompt(),
                enabled.promptVersion(),
                enabled.modelRef(),
                false,
                enabled.toolNames());

        AgentRegistry before = new AgentRegistry(List.of(enabled));
        AgentRegistry after = new AgentRegistry(List.of(disabled));

        assertThat(before.isEnabled("intent-recognition")).isTrue();
        assertThat(after.isEnabled("intent-recognition")).isFalse();
        assertThat(after.has("intent-recognition")).isTrue();
        assertThat(new AgentRegistryChangeAuditor(mockAudits()).diff(before, after))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.agentSlug()).isEqualTo("intent-recognition");
                    assertThat(change.kind()).isEqualTo(AgentRegistryChangeAuditor.Kinds.DISABLED);
                });
    }

    private static cn.zimu.fulfillment.common.audit.AuditLogService mockAudits() {
        return org.mockito.Mockito.mock(cn.zimu.fulfillment.common.audit.AuditLogService.class);
    }
}
