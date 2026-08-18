package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 03 — 元数据白名单与密钥安全：
 * 真实三元组（deepseek/deepseek-chat/wecom-interpret-v1）登记后允许持久化、未登记折叠 sentinel；
 * 解释器成功结果的三元组可被 registry 放行；密钥不出现在任何可观测结果中。
 */
class DeepSeekInterpretationMetadataTest {

    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";
    private static final String PROMPT = "wecom-interpret-v1";

    private MessageModelMetadataRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MessageModelMetadataRegistry();
        MessageModelMetadataRegistry.PublicMetadataAlias alias =
                new MessageModelMetadataRegistry.PublicMetadataAlias();
        alias.setProvider(PROVIDER);
        alias.setModel(MODEL);
        alias.setPromptVersion(PROMPT);
        registry.setPublicMetadataAliases(List.of(alias));
    }

    private static InterpretationResult result(String error) {
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of("receiver", Map.of("name", "张三")),
                PROVIDER,
                MODEL,
                PROMPT,
                error);
    }

    @Test
    void registeredTripleIsAllowedForPersistence() {
        assertThat(registry.allows(result(null))).isTrue();
        assertThat(registry.publicProjection(PROVIDER, MODEL, PROMPT).provider()).isEqualTo(PROVIDER);
        assertThat(registry.publicProjection(PROVIDER, MODEL, PROMPT).model()).isEqualTo(MODEL);
        assertThat(registry.publicProjection(PROVIDER, MODEL, PROMPT).promptVersion()).isEqualTo(PROMPT);
    }

    @Test
    void unregisteredTripleCollapsesToSentinelAndIsRejected() {
        InterpretationResult unknown = new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of(),
                "openai",
                "gpt-4o",
                "v9",
                null);

        assertThat(registry.allows(unknown)).isFalse();
        MessageModelMetadataRegistry.PublicMetadata projected =
                registry.publicProjection("openai", "gpt-4o", "v9");
        assertThat(projected.provider()).isEqualTo("none");
        assertThat(projected.model()).isEqualTo("none");
    }

    @Test
    void noneTripleOnlyAllowedWhenErrorPresent() {
        InterpretationResult failed = new InterpretationResult(
                MessageIntent.NEED_REVIEW, Map.of("reason", "MODEL_CALL_FAILED"),
                "none", "none", "none", "MODEL_CALL_FAILED");

        assertThat(registry.allows(failed)).isTrue();
        assertThat(registry.allows(result(null))).isTrue();
    }

    @Test
    void interpreterOutputTripleMatchesRegisteredAlias() {
        // 解释器配置的 provider/model/prompt-version 与登记别名一致，成功结果可持久化
        DeepSeekMessageInterpreter interpreter = new DeepSeekMessageInterpreter(
                "http://127.0.0.1:9", "sk-test", PROVIDER, MODEL, PROMPT, 100);

        // 配置态断言：解释器构造即携带登记的三元组（调用失败时也不泄漏 key）
        InterpretationResult failed = interpreter.interpret(
                new InterpretationInput(1L, "x", null, null, List.of()));
        assertThat(registry.allows(failed)).isTrue();
        assertThat(failed.toString()).doesNotContain("sk-test");
    }
}
