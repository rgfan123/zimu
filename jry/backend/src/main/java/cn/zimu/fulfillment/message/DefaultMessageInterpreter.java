package cn.zimu.fulfillment.message;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认解释器：模型未配置时的诚实兜底。
 *
 * <p>没有真实模型配置时，本实现不猜测任何意图，一律返回 {@link MessageIntent#NEED_REVIEW} 并带
 * 错误原因，使每条无法自动判断的消息进入人工复核队列。真实模型接入通过实现
 * {@link MessageInterpreter} 替换本 Bean（配置 {@code app.message-interpreter.base-url} 时
 * {@link DeepSeekMessageInterpreter} 注册，两者互斥），本类不包含任何业务规则。
 */
@ConditionalOnProperty(name = "app.message-interpreter.base-url", matchIfMissing = true)
public class DefaultMessageInterpreter implements MessageInterpreter {

    private final String provider;
    private final String model;
    private final String promptVersion;
    private final boolean configured;

    public DefaultMessageInterpreter(
            @Value("${app.message-interpreter.provider:}") String provider,
            @Value("${app.message-interpreter.model:}") String model,
            @Value("${app.message-interpreter.prompt-version:}") String promptVersion) {
        this.provider = provider;
        this.model = model;
        this.promptVersion = promptVersion;
        this.configured = !provider.isBlank() && !model.isBlank() && !promptVersion.isBlank();
    }

    @Override
    public InterpretationResult interpret(InterpretationInput input) {
        if (!configured) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reason", "MODEL_NOT_CONFIGURED");
            return new InterpretationResult(
                    MessageIntent.NEED_REVIEW,
                    output,
                    "none",
                    "none",
                    "none",
                    InterpretationFailureCode.MODEL_NOT_CONFIGURED.name());
        }
        throw new IllegalStateException(
                "app.message-interpreter 已配置但未实现模型客户端：请实现 MessageInterpreter 替换默认 Bean");
    }
}
