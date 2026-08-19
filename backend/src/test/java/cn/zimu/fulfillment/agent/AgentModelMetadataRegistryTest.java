package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 01 — Agent 运行元数据 allowlist 投影（对照 message/MessageModelMetadataRegistry 语义）：
 * 服务端登记且可发布的三元组才暴露真实值；未白名单（或别名不可发布）一律折叠 none/none/none；
 * none 三元组只在携带失败码时允许持久化。Agent 结果永远不能自行添加别名。
 */
class AgentModelMetadataRegistryTest {

    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";
    private static final String PROMPT = "agent-foundation-v1";

    private AgentModelMetadataRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentModelMetadataRegistry();
        registry.setPublicMetadataAliases(List.of(alias(PROVIDER, MODEL, PROMPT)));
    }

    private static AgentModelMetadataRegistry.PublicMetadataAlias alias(
            String provider, String model, String promptVersion) {
        AgentModelMetadataRegistry.PublicMetadataAlias alias =
                new AgentModelMetadataRegistry.PublicMetadataAlias();
        alias.setProvider(provider);
        alias.setModel(model);
        alias.setPromptVersion(promptVersion);
        return alias;
    }

    private static AgentRunResult result(String provider, String model, String prompt, String error) {
        if (error != null) {
            return AgentRunResult.failure(provider, model, prompt, AgentFailureCode.AGENT_MODEL_CALL_FAILED);
        }
        return AgentRunResult.success(null, provider, model, prompt);
    }

    @Test
    void registeredTripleIsAllowedAndProjectedAsRealValues() {
        assertThat(registry.allows(result(PROVIDER, MODEL, PROMPT, null))).isTrue();

        AgentModelMetadataRegistry.PublicMetadata projected =
                registry.publicProjection(PROVIDER, MODEL, PROMPT);
        assertThat(projected.provider()).isEqualTo(PROVIDER);
        assertThat(projected.model()).isEqualTo(MODEL);
        assertThat(projected.promptVersion()).isEqualTo(PROMPT);
    }

    @Test
    void unregisteredTripleCollapsesToNoneAndIsRejected() {
        assertThat(registry.allows(result("openai", "gpt-4o", "v9", null))).isFalse();

        AgentModelMetadataRegistry.PublicMetadata projected =
                registry.publicProjection("openai", "gpt-4o", "v9");
        assertThat(projected.provider()).isEqualTo("none");
        assertThat(projected.model()).isEqualTo("none");
        assertThat(projected.promptVersion()).isEqualTo("none");
    }

    @Test
    void noneTripleOnlyAllowedWhenErrorPresent() {
        AgentRunResult failed = result("none", "none", "none", "AGENT_MODEL_NOT_CONFIGURED");
        assertThat(registry.allows(failed)).isTrue();
        assertThat(registry.publicProjection("none", "none", "none").provider())
                .isEqualTo("none");
    }

    @Test
    void unpublishableAliasIsIgnored() {
        registry.setPublicMetadataAliases(List.of(alias("", MODEL, PROMPT)));
        assertThat(registry.allows(result(PROVIDER, MODEL, PROMPT, null))).isFalse();
        assertThat(registry.publicProjection(PROVIDER, MODEL, PROMPT).provider()).isEqualTo("none");
    }

    @Test
    void nullResultIsNeverAllowed() {
        assertThat(registry.allows(null)).isFalse();
    }
}
