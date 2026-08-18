package cn.zimu.fulfillment.message;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;

/**
 * MessageInterpreter 接缝的互斥注册：配置了 {@code app.message-interpreter.base-url} 时注册
 * {@link DeepSeekMessageInterpreter}（真实模型），否则注册 {@link DefaultMessageInterpreter}
 * （fail-closed 兜底）。base-url 在 yml 中恒存在（默认空串），故用表达式按非空互斥，
 * 而非 @ConditionalOnProperty（对空值也会匹配）。
 */
@Configuration
public class MessageInterpreterConfiguration {

    @Bean
    @ConditionalOnExpression("!('${app.message-interpreter.base-url:}'.isBlank())")
    MessageInterpreter deepSeekMessageInterpreter(
            @Value("${app.message-interpreter.base-url:}") String baseUrl,
            @Value("${app.message-interpreter.api-key:}") String apiKey,
            @Value("${app.message-interpreter.provider:}") String provider,
            @Value("${app.message-interpreter.model:}") String model,
            @Value("${app.message-interpreter.prompt-version:}") String promptVersion,
            @Value("${app.message-interpreter.request-timeout-ms:30000}") long requestTimeoutMillis) {
        return new DeepSeekMessageInterpreter(
                baseUrl, apiKey, provider, model, promptVersion, requestTimeoutMillis);
    }

    @Bean
    @ConditionalOnExpression("'${app.message-interpreter.base-url:}'.isBlank()")
    MessageInterpreter defaultMessageInterpreter(
            @Value("${app.message-interpreter.provider:}") String provider,
            @Value("${app.message-interpreter.model:}") String model,
            @Value("${app.message-interpreter.prompt-version:}") String promptVersion) {
        return new DefaultMessageInterpreter(provider, model, promptVersion);
    }
}
