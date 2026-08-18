package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 可观测性 provider 装配（agent-decision-layer 08）。
 *
 * <p>开关 {@code app.agent.observability.enabled}（默认 true）：开启时注册默认 DB 实现
 * {@link JdbcAgentObservability}；关闭时注册 {@link NoopAgentObservability}（业务与审计
 * 零影响）。未来替换 Langfuse/OTLP provider 只需改这里，业务代码不 import 第三方 SDK。
 */
@Configuration
public class AgentObservabilityConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "app.agent.observability",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    AgentObservability jdbcAgentObservability(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcAgentObservability(jdbc, mapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "app.agent.observability",
            name = "enabled",
            havingValue = "false")
    AgentObservability noopAgentObservability() {
        return AgentObservability.disabled();
    }
}
