package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 注册表与运行时门面的装配（agent-decision-layer 02）。
 *
 * <p>业务 Agent（05/06/07 票）只需注册 {@code AgentDefinition} bean 即自动进入注册表；
 * 当前没有任何业务 Agent bean 时注册表为空清单（不阻断启动）。{@link AgentRuntimeFacade}
 * 组合 01 的 {@link AgentRuntime} 模型接缝与注册表/审计/元数据投影，未配置模型时由
 * {@link DefaultAgentRuntime} fail-closed 兜底（01 互斥注册不变）。
 */
@Configuration
public class AgentRegistryConfiguration {

    @Bean
    AgentRegistry agentRegistry(List<AgentDefinition> definitions) {
        return new AgentRegistry(definitions);
    }

    @Bean
    AgentRegistryChangeAuditor agentRegistryChangeAuditor(AuditLogService audits) {
        return new AgentRegistryChangeAuditor(audits);
    }

    @Bean
    AgentRuntimeFacade agentRuntimeFacade(
            AgentRegistry registry,
            AgentRuntime runtime,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            AgentToolBindingFactory toolBindingFactory) {
        return new AgentRuntimeFacade(registry, runtime, audits, metadata, toolBindingFactory);
    }
}
