package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 08 — 可观测性开关关闭验收（agent-decision-layer 08，Testcontainers）：
 * {@code app.agent.observability.enabled=false} 时注入 {@link NoopAgentObservability}，
 * Agent 运行结果与审计完全正常，且 agent_run / agent_tool_call 零落库。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.agent.observability.enabled=false",
            "app.mcp.agent-identity=acceptance-agent"
        })
class AgentObservabilityDisabledIntegrationTest {

    private static final String SLUG = "obs-test-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentRuntimeFacade facade;

    @Autowired
    private AgentRegistryHolder holder;

    @Autowired
    private AgentObservability observability;

    @Autowired
    private AgentToolBindingFactory bindingFactory;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE app.agent_runs, app.agent_tool_calls, app.audit_logs
                RESTART IDENTITY CASCADE
                """);
        // T02 后定义真源为 DB：测试 Agent 先删后插（幂等），holder 换实例即被运行路径感知
        // T02 后定义真源为 DB：测试 Agent 幂等注册（先删同 slug 再插），holder 换实例即被运行路径感知
        AgentSeedFixtures.upsertActiveDefinition(
                jdbc,
                AgentDefinition.ofActiveV1(
                        SLUG, "可观测性验收 Agent", "d", "你是只读助手。", "obs-v1", "app.agent", true,
                        List.of("search_skus")));
        holder.reload();
    }

    @Test
    void disabledSwitchKeepsBusinessWorkingWithoutObservabilityRows() {
        assertThat(observability).isInstanceOf(NoopAgentObservability.class);

        AgentRunResult result = facade.invoke(SLUG, "汇总一下进货价", null);

        // 业务结果与关闭前一致（fail-closed 稳定码，不触碰模型）
        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");

        // 工具调用也照常执行，但零观测落库
        AgentToolBinding binding = bindingFactory.bind(AgentRuntimeFacade.newRunId(), List.of("search_skus"));
        AgentToolInvoker invoker = (AgentToolInvoker) binding.tools().values().iterator().next();
        invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("search_skus")
                        .arguments("{\"query\":\"羊\"}")
                        .build(),
                null);

        Integer runs = jdbc.queryForObject("SELECT count(*) FROM app.agent_runs", Integer.class);
        Integer calls =
                jdbc.queryForObject("SELECT count(*) FROM app.agent_tool_calls", Integer.class);
        assertThat(runs).isZero();
        assertThat(calls).isZero();
    }
}
