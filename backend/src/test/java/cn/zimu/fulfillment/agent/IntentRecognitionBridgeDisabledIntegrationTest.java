package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.InterpretationInput;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.InterpretationService;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageInterpretation;
import cn.zimu.fulfillment.message.MessageInterpretationRepository;
import cn.zimu.fulfillment.message.MessageInterpreter;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 07 — 意图识别 Agent 启停验收（agent-decision-layer 07，T06 适配，Testcontainers）：注册表中
 * intent-recognition 被停用（enabled=false）时，既有消息解释任务照常执行并持久化
 * MessageInterpretation，但 Agent 观测（agent_runs）零写入——启停只影响观测/
 * 注册视图，不影响既有消息管线执行。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
class IntentRecognitionBridgeDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void disableScheduledWorker(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter stubInterpreter() {
            return ignored -> RESULT;
        }
    }

    private static final InterpretationResult RESULT = new InterpretationResult(
            MessageIntent.CUSTOMER_ORDER,
            Map.of("customer", "禁用观测的草稿仍照常生成"),
            "test-provider",
            "test-model",
            "test-prompt-v1",
            null);

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private InterpretationService interpretationService;

    @Autowired
    private MessageInterpretationRepository interpretations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentRegistryHolder holder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE app.agent_runs, app.agent_tool_calls, app.audit_logs,
                         app.async_tasks, app.message_submissions, app.message_interpretations,
                         app.review_cases, app.order_drafts
                RESTART IDENTITY CASCADE
                """);
        // T02 后定义真源为 DB：停用 = 改 DB 行 + holder 换实例（无需重启即感知）
        jdbc.update("UPDATE app.agent_definitions SET enabled = false "
                + "WHERE agent_slug = 'intent-recognition' AND version = 1");
        holder.reload();
    }

    @Test
    void disabledAgentWritesNoObservabilityWhilePipelineStillExecutes() {
        long submissionId = submissionService.submit(new ChannelMessageCommand(
                "corp-test",
                "connection-test",
                "bot-test",
                "BRIDGE-DISABLED-001",
                "chat-test",
                "group",
                "operator-test",
                "text",
                "请帮我下一单",
                null,
                null,
                objectMapper.createObjectNode().put("message_id", "BRIDGE-DISABLED-001")));
        AsyncTaskStore.AsyncTask task =
                taskStore.claim("bridge-disabled-test", Duration.ofSeconds(30)).orElseThrow();

        interpretationService.interpret(task);

        // 既有解释任务照常执行：解释版本照常落库（启停不影响既有管线）
        List<MessageInterpretation> persisted =
                interpretations.findBySubmissionIdOrderByVersionDesc(submissionId);
        assertThat(persisted).singleElement().satisfies(item ->
                assertThat(item.getIntent()).isEqualTo(MessageIntent.CUSTOMER_ORDER));

        // 停用 Agent：零观测（重复审计通道已删，无桥审计可查）
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_runs WHERE agent_slug = 'intent-recognition'",
                        Long.class))
                .isZero();
    }
}
