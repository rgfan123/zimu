package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * promptfoo eval 配置生成器（07 决策 11 中间路线；meta-agent-platform-impl 09）：由定义
 * system_prompt（提示词真源在 {@code agent_definitions}）+ QUALITY 用例生成
 * {@code npx promptfoo eval} 可直接消费的配置（config YAML + chat 消息 JSON 双文件）。
 *
 * <p>形态：deepseek provider（{@code deepseek:deepseek-chat}），apiKey 经
 * {@code {{env.DEEPSEEK_API_KEY}}} 引用环境变量——密钥绝不进入 YAML/JSON/DB/日志/产物；
 * chat 消息数组（system = 定义提示词，user = {@code {{input}}} 占位）按 promptfoo 约定
 * 落 {@code chat.json} 由配置 {@code file://chat.json} 引用（prompts 内联消息数组不被
 * promptfoo 接受）；断言用确定性 {@code contains}（expected.answer_contains 片段），
 * 不依赖判分模型（QUALITY 是参考指标，不钉基线）。
 *
 * <p>手写 YAML 发射器：全部标量走双引号转义（YAML 双引号规则），结构固定为
 * description/prompts/providers/tests 四段，可被 js-yaml（promptfoo 依赖）稳定解析。
 */
public final class PromptfooYamlGenerator {

    private static final String PROVIDER = "deepseek:deepseek-chat";
    private static final String CHAT_PROMPT_FILE = "file://chat.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptfooYamlGenerator() {}

    /** chat 消息 JSON（system = 定义提示词，user = {{input}}），写 {@code chat.json}。 */
    public static String chatMessagesJson(AgentDefinition definition) {
        try {
            ArrayNode messages = MAPPER.createArrayNode();
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", definition.systemPrompt());
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", "{{input}}");
            return MAPPER.writeValueAsString(messages);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("chat 消息 JSON 生成失败", ex);
        }
    }

    /** 生成 promptfoo 配置 YAML。{@code cases} 为空时仍产出合法配置（无 tests，运行即通过）。 */
    public static String generateConfig(AgentDefinition definition, List<QualityEvalCase> cases) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("description: ").append(quoted("quality-eval-" + definition.agentSlug() + "-v" + definition.version())).append('\n');
        yaml.append("prompts:\n");
        yaml.append("  - ").append(quoted(CHAT_PROMPT_FILE)).append('\n');
        yaml.append("providers:\n");
        yaml.append("  - ").append(quoted("id")).append(": ").append(quoted(PROVIDER)).append('\n');
        yaml.append("    ").append(quoted("config")).append(":\n");
        yaml.append("      ").append(quoted("apiKey")).append(": ").append(quoted("{{env.DEEPSEEK_API_KEY}}")).append('\n');
        yaml.append("tests:\n");
        for (QualityEvalCase evalCase : cases) {
            yaml.append("  - ").append(quoted("vars")).append(":\n");
            yaml.append("      ").append(quoted("input")).append(": ").append(quoted(evalCase.input())).append('\n');
            yaml.append("    ").append(quoted("assert")).append(":\n");
            for (String fragment : evalCase.answerContains()) {
                yaml.append("      - ").append(quoted("type")).append(": ").append(quoted("contains")).append('\n');
                yaml.append("        ").append(quoted("value")).append(": ").append(quoted(fragment)).append('\n');
            }
        }
        return yaml.toString();
    }

    /** YAML 双引号标量：转义反斜杠/引号/控制字符（C0 \n\t\r、其余 <0x20、DEL 0x7F、C1 0x80–0x9F）。 */
    static String quoted(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)) {
                        out.append(String.format("\\u%04X", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
