package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>{@code app.agent.observability.token-warn-threshold}（129 票，默认 0 = 关闭）：
 * 单次运行 total token 超此值时在 {@code token_usage} 留 {@code over_threshold} 标记并打
 * WARN。默认关闭是刻意的——阈值随 Agent 形态差异极大（比价 Agent 与业务草稿整理 Agent
 * 不在一个量级），拍一个全局默认值只会制造噪声告警。
 */
@Configuration
public class AgentObservabilityConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "app.agent.observability",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    AgentObservability jdbcAgentObservability(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${app.agent.observability.token-warn-threshold:0}") int tokenWarnThreshold) {
        return new JdbcAgentObservability(jdbc, mapper, tokenWarnThreshold);
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
