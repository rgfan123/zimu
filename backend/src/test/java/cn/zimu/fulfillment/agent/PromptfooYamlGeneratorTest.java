package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 09 — promptfoo 配置生成器（meta-agent-platform-impl 09）：config YAML + chat 消息 JSON
 * 双文件，结构可被 js-yaml 兼容解析（测试用 SnakeYAML 回读）、deepseek provider + 环境变量
 * 密钥引用（无字面密钥）、chat 消息含定义 system_prompt、tests 按用例展开 contains 断言。
 * 纯单元测试。
 */
class PromptfooYamlGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = "你是采购比价助手，只读分析。\n禁止泄露密钥。";

    private AgentDefinition definition() {
        return AgentDefinition.ofActiveV1(
                "procurement-price-agent",
                "采购比价",
                "d",
                SYSTEM_PROMPT,
                "procurement-price-v1",
                "app.agent",
                true,
                List.of("search_skus"));
    }

    @Test
    void configYamlIsParseableWithExpectedStructure() {
        String yaml = PromptfooYamlGenerator.generateConfig(definition(), List.of(
                new QualityEvalCase(1, "9005 的进货价", List.of("9005", "元")),
                new QualityEvalCase(2, "缺货行数", List.of("缺货"))));

        Object parsed = new Yaml(new LoaderOptions()).load(yaml);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> root = (java.util.Map<String, Object>) parsed;
        assertThat(root.get("description")).isEqualTo("quality-eval-procurement-price-agent-v1");

        // prompts: 引用 chat 消息文件（promptfoo 不接受内联消息数组）
        @SuppressWarnings("unchecked")
        List<Object> prompts = (List<Object>) root.get("prompts");
        assertThat(prompts).containsExactly("file://chat.json");

        // providers: deepseek + 环境变量密钥引用
        @SuppressWarnings("unchecked")
        List<Object> providers = (List<Object>) root.get("providers");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> provider = (java.util.Map<String, Object>) providers.getFirst();
        assertThat(provider.get("id")).isEqualTo("deepseek:deepseek-chat");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> config = (java.util.Map<String, Object>) provider.get("config");
        assertThat(config.get("apiKey")).isEqualTo("{{env.DEEPSEEK_API_KEY}}");

        // tests: 每用例 vars.input + contains 断言
        @SuppressWarnings("unchecked")
        List<Object> tests = (List<Object>) root.get("tests");
        assertThat(tests).hasSize(2);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> first = (java.util.Map<String, Object>) tests.get(0);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> vars = (java.util.Map<String, Object>) first.get("vars");
        assertThat(vars.get("input")).isEqualTo("9005 的进货价");
        @SuppressWarnings("unchecked")
        List<Object> asserts = (List<Object>) first.get("assert");
        assertThat(asserts).hasSize(2);
    }

    @Test
    void chatMessagesJsonCarriesSystemPromptAndInputPlaceholder() throws Exception {
        JsonNode messages = MAPPER.readTree(PromptfooYamlGenerator.chatMessagesJson(definition()));

        assertThat(messages.isArray()).isTrue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isEqualTo(SYSTEM_PROMPT);
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText()).isEqualTo("{{input}}");
    }

    @Test
    void generatedArtifactsNeverContainLiteralSecrets() throws Exception {
        String yaml = PromptfooYamlGenerator.generateConfig(definition(), List.of(
                new QualityEvalCase(1, "含引号与换行的输入\n\"测试\"", List.of("片段"))));
        String chat = PromptfooYamlGenerator.chatMessagesJson(definition());

        assertThat(yaml).doesNotContain("sk-");
        assertThat(yaml).doesNotContain("DEEPSEEK_API_KEY=");
        assertThat(chat).doesNotContain("sk-");
        // 双引号/换行正确转义，仍是合法 YAML 与 JSON（snakeyaml 2.x load 为泛型，先赋 Object）
        Object parsedYaml = new Yaml(new LoaderOptions()).load(yaml);
        assertThat(parsedYaml).isNotNull();
        assertThat(MAPPER.readTree(chat)).isNotNull();
    }

    @Test
    void emptyCasesProduceValidConfig() {
        String yaml = PromptfooYamlGenerator.generateConfig(definition(), List.of());

        Object parsed = new Yaml(new LoaderOptions()).load(yaml);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> root = (java.util.Map<String, Object>) parsed;
        assertThat(root.containsKey("tests")).isTrue();
    }

    @Test
    void quotingEscapesControlCharactersAndQuotes() {
        assertThat(PromptfooYamlGenerator.quoted("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
    }
}
