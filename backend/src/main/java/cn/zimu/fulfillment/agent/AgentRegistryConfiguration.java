package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 注册表与运行时门面的装配（agent-decision-layer 02；meta-agent-platform-impl 02）。
 *
 * <p>定义真源已从代码切到 DB（{@code app.agent_definitions}，T01 播种）：{@link
 * AgentRegistryHolder} 启动时经 {@link AgentDefinitionRepository} 全量加载 active 行构造
 * 不可变 {@link AgentRegistry}，运行路径（{@link AgentRuntimeFacade}）每次取 holder 当前
 * 引用——确认/回滚后 {@code holder.reload()} 换实例即可感知，无需重启。
 *
 * <p>三个业务消费方（{@code DataQueryAgentService} / {@code IntentRecognitionAgentBridge} /
 * {@code ProcurementPriceAgent}）同样注入 holder，保持「DB 真源 + 运行期感知」一致；
 * 代码定义 bean 已随 T02 删除，种子为唯一来源。
 */
@Configuration
public class AgentRegistryConfiguration {

    @Bean
    AgentRegistryChangeAuditor agentRegistryChangeAuditor(AuditLogService audits) {
        return new AgentRegistryChangeAuditor(audits);
    }

    @Bean
    AgentRuntimeFacade agentRuntimeFacade(
            AgentRegistryHolder holder,
            AgentRuntime runtime,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            AgentToolBindingFactory toolBindingFactory) {
        return new AgentRuntimeFacade(holder, runtime, audits, metadata, toolBindingFactory);
    }
}
