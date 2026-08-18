package cn.zimu.fulfillment.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时接缝的互斥注册：配置了 {@code app.agent.base-url} 时注册
 * {@link LangChain4jAgentRuntime}（真实模型），否则注册 {@link DefaultAgentRuntime}
 * （fail-closed 兜底）。base-url 在 yml 中恒存在（默认空串），故用表达式按非空互斥，
 * 而非 @ConditionalOnProperty（对空值也会匹配）。{@code app.message-interpreter.*} 行为不受影响。
 *
 * <p>08 票：真实模型运行时注入 {@link AgentObservability} provider（模型调用 token 用量
 * 随 run_id 落可观测记录），关闭开关时注入 no-op，运行行为不变。
 */
@Configuration
public class AgentRuntimeConfiguration {

    @Bean
    @ConditionalOnExpression("!('${app.agent.base-url:}'.isBlank())")
    AgentRuntime langChain4jAgentRuntime(
            AgentModelProperties properties, AgentObservability observability) {
        return new LangChain4jAgentRuntime(properties, observability);
    }

    @Bean
    @ConditionalOnExpression("'${app.agent.base-url:}'.isBlank()")
    AgentRuntime defaultAgentRuntime(AgentModelProperties properties) {
        return new DefaultAgentRuntime(properties);
    }
}
