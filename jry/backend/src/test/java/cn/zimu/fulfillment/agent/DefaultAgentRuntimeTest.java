package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 01 — fail-closed 兜底（agent-decision-layer 01 验收）：
 * 模型未配置时 DefaultAgentRuntime 不连接任何模型、不猜测任何输出，
 * 返回稳定失败码 AGENT_MODEL_NOT_CONFIGURED 与 none/none/none 三元组。
 */
class DefaultAgentRuntimeTest {

    private static AgentModelProperties unconfigured() {
        return new AgentModelProperties();
    }

    private static AgentModelProperties configured() {
        AgentModelProperties properties = new AgentModelProperties();
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setApiKey("sk-test");
        properties.setProvider("deepseek");
        properties.setModel("deepseek-chat");
        return properties;
    }

    @Test
    void unconfiguredFailsClosedWithNoneTriple() {
        AgentRuntime runtime = new DefaultAgentRuntime(unconfigured());

        AgentRunResult result = runtime.run(new AgentTaskRequest("sys", "你好"));

        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(result.output()).isNull();
        assertThat(result.provider()).isEqualTo("none");
        assertThat(result.model()).isEqualTo("none");
        assertThat(result.promptVersion()).isEqualTo("none");
    }

    @Test
    void configuredButNoClientThrowsFailClosed() {
        AgentRuntime runtime = new DefaultAgentRuntime(configured());

        assertThatThrownBy(() -> runtime.run(new AgentTaskRequest("sys", "你好")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请实现 AgentRuntime");
    }

    @Test
    void failClosedResultIsRegistryAllowedOnlyWhenErrorPresent() {
        AgentRunResult result = new DefaultAgentRuntime(unconfigured())
                .run(new AgentTaskRequest("sys", "你好"));

        assertThat(result.error()).isNotBlank();
        assertThat(result.toString()).doesNotContain("sk-test");
    }
}
