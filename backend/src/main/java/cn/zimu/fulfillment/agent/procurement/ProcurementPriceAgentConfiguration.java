package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentRegistryHolder;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 采购比价 Agent 的装配（agent-decision-layer 05；meta-agent-platform-impl 02 摘定义）：
 * 采购比价专属运行时与编排服务 bean。
 *
 * <p>Agent 定义（slug/提示词/白名单/版本链）真源已随 T02 切到 DB（{@code agent_definitions}
 * 种子 procurement-price-agent，V33 播种），本配置类不再声明定义 bean；{@link
 * ProcurementPriceAgent} 经 {@link AgentRegistryHolder} 取当前生效定义（确认/回滚后
 * reload 换实例即感知），白名单等权限表达以种子为准。
 */
@Configuration
public class ProcurementPriceAgentConfiguration {

    @Bean
    ProcurementPriceAgentRuntime procurementPriceAgentRuntime(AgentModelProperties properties) {
        return new ProcurementPriceAgentRuntime(properties);
    }

    @Bean
    ProcurementPriceAgent procurementPriceAgent(
            AgentRegistryHolder holder,
            ProcurementPriceRuntime runtime,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            AgentToolBindingFactory toolBindingFactory) {
        return new ProcurementPriceAgent(holder, runtime, audits, metadata, toolBindingFactory);
    }
}
