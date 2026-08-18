package cn.zimu.fulfillment.agent;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 意图识别 Agent 的注册（agent-decision-layer 07）：不重写既有消息解释管线，仅把意图分类
 * 注册为受管 {@link AgentDefinition}（自动进入 02 票 {@link AgentRegistry}），获得统一启停
 * 视图与运行观测。
 *
 * <p>{@code tool_names} 恒为空：意图识别是单次分类接缝，无任何工具调用；真实执行仍由
 * {@code message/MessageInterpreter}（DeepSeekMessageInterpreter 的既有 PROMPT_V1）驱动，
 * 本定义只做声明与观测（prompt_version 与 {@code app.message-interpreter.prompt-version}
 * 对齐：配置了该版本号时镜像其值，未配置时回退到本票固定的版本语义）。
 *
 * <p>{@code model_ref} 指向 {@code app.message-interpreter}（而非 05/06 票的 {@code app.agent}）：
 * 意图分类由既有消息解释器按 {@code app.message-interpreter.*} 配置执行，其模型接缝与本 Agent
 * 的 LangChain4j 运行时相互独立；该引用仅作注册表/审计声明，不解析为 Spring 配置。
 */
@Configuration
public class IntentRecognitionAgentConfiguration {

    public static final String AGENT_SLUG = "intent-recognition";
    public static final String NAME = "意图识别";
    /** 模型引用：意图分类实际由既有消息解释器按 app.message-interpreter.* 配置执行。 */
    public static final String MODEL_REF = "app.message-interpreter";
    /** 未配置 app.message-interpreter.prompt-version 时回退的本 Agent 固定版本语义。 */
    public static final String DEFAULT_PROMPT_VERSION = "intent-recognition-v1";

    @Bean
    AgentDefinition intentRecognitionAgentDefinition(
            @Value("${app.message-interpreter.prompt-version:}") String interpreterPromptVersion) {
        String promptVersion = interpreterPromptVersion == null || interpreterPromptVersion.isBlank()
                ? DEFAULT_PROMPT_VERSION
                : interpreterPromptVersion;
        return AgentDefinition.of(
                AGENT_SLUG,
                NAME,
                "企业微信消息意图分类与分流",
                systemPrompt(),
                promptVersion,
                MODEL_REF,
                true,
                List.of());
    }

    private static String systemPrompt() {
        return """
                你是企业微信消息意图识别 Agent（单次分类接缝，无工具调用）。职责：对企业微信消息做\
                意图分类与分流，输出六枚举意图之一（CUSTOMER_ORDER / SUPPLIER_TRACKING / \
                ORDER_CHANGE / ORDER_CANCEL / NON_BUSINESS / NEED_REVIEW）。

                实际执行由既有消息解释管线（MessageInterpreter / DeepSeekMessageInterpreter，\
                提示词引用既有 PROMPT_V1 语义，版本见 app.message-interpreter.prompt-version）\
                完成；本定义仅将意图识别注册为受管 Agent，获得统一启停视图与运行观测，\
                不改变既有分流行为，不替代既有 MessageInterpretation 持久化。""";
    }
}
