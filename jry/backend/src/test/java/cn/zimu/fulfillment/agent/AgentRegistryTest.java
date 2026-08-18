package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 02 — Agent 注册表验收（agent-decision-layer 02）：不可变清单按 slug 查询、enabled 判定、
 * 枚举、slug 唯一性/格式校验。纯单元测试，不依赖 Spring 上下文。
 */
class AgentRegistryTest {

    private static AgentDefinition purchasing() {
        return AgentDefinition.of(
                "purchasing-comparison",
                "采购比价",
                "汇总 SKU 进货价与库存上下文给出比价建议",
                "你是采购比价助手，只读分析，不触发任何写操作。",
                "purchasing-v1",
                "app.agent",
                true,
                List.of("search_provider_skus", "get_sku_price"));
    }

    private static AgentDefinition intentRecognition() {
        return AgentDefinition.of(
                "intent-recognition",
                "意图识别",
                "企业微信消息意图分类与分流",
                "把消息归类为已知意图。",
                "intent-v1",
                "app.agent",
                false,
                List.of());
    }

    @Test
    void listsAllDefinitionsInDeclarationOrder() {
        AgentRegistry registry = new AgentRegistry(List.of(purchasing(), intentRecognition()));

        List<AgentDefinition> all = registry.definitions();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).agentSlug()).isEqualTo("purchasing-comparison");
        assertThat(all.get(1).agentSlug()).isEqualTo("intent-recognition");
        assertThat(registry.slugs()).containsExactly("purchasing-comparison", "intent-recognition");
    }

    @Test
    void bySlugReturnsDefinitionAndNullForUnknown() {
        AgentRegistry registry = new AgentRegistry(List.of(purchasing()));

        assertThat(registry.bySlug("purchasing-comparison").name()).isEqualTo("采购比价");
        assertThat(registry.bySlug("missing-agent")).isNull();
        assertThat(registry.has("purchasing-comparison")).isTrue();
        assertThat(registry.has("missing-agent")).isFalse();
    }

    @Test
    void enabledJudgementIsPerDefinitionAndFailClosedForUnknown() {
        AgentRegistry registry = new AgentRegistry(List.of(purchasing(), intentRecognition()));

        assertThat(registry.isEnabled("purchasing-comparison")).isTrue();
        assertThat(registry.isEnabled("intent-recognition")).isFalse();
        assertThat(registry.isEnabled("missing-agent")).isFalse();
    }

    @Test
    void duplicateSlugIsRejected() {
        assertThatThrownBy(() -> new AgentRegistry(List.of(purchasing(), purchasing())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void nullDefinitionIsRejected() {
        assertThatThrownBy(() -> new AgentRegistry(java.util.Arrays.asList(purchasing(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为 null");
    }

    @Test
    void emptyRegistryIsValid() {
        AgentRegistry registry = new AgentRegistry(List.of());

        assertThat(registry.definitions()).isEmpty();
        assertThat(registry.isEnabled("anything")).isFalse();
    }

    @Test
    void invalidSlugIsRejected() {
        assertThatThrownBy(() -> AgentDefinition.of(
                "PURCHASING", "n", "d", "s", "v1", "app.agent", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent_slug");
        assertThatThrownBy(() -> AgentDefinition.of(
                "", "n", "d", "s", "v1", "app.agent", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentDefinition.of(
                "a b", "n", "d", "s", "v1", "app.agent", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRequiredTextFieldsAreRejected() {
        assertThatThrownBy(() -> AgentDefinition.of(
                "ok-agent", "", "d", "s", "v1", "app.agent", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> AgentDefinition.of(
                "ok-agent", "n", "d", "s", "", "app.agent", true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt_version");
    }

    @Test
    void registryIsImmutable() {
        List<String> tools = new java.util.ArrayList<>(List.of("t1"));
        AgentDefinition definition = AgentDefinition.of(
                "slug-a", "n", "d", "s", "v1", "app.agent", true, tools);
        tools.add("t2");

        assertThat(definition.toolNames()).containsExactly("t1");
        AgentRegistry registry = new AgentRegistry(List.of(definition));
        assertThat(registry.bySlug("slug-a").toolNames()).containsExactly("t1");
        assertThatThrownBy(() -> registry.definitions().add(definition))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
