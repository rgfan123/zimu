package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
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
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
 * 07 — 意图识别运行桥验收（agent-decision-layer 07，Testcontainers）：真实 PostgreSQL 下，
 * 解释任务执行（既有 InterpretationWorker/InterpretationService 路径）向 Agent 运行记录写入
 * provider/model/prompt_version/intent/error_code 元数据，run_id 与审计 trace_id 双向关联，
 * 与既有 MessageInterpretation 持久化并存（行为零变化）；Agent 未配置 allowlist 时投影 none；
 * 失败路径落 FAILED 行与稳定错误码。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
class IntentRecognitionBridgeIntegrationTest {

    private static final String CONTENT = "请处理这条消息";

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
            return InterpreterControl::next;
        }
    }

    static final class InterpreterControl {

        private static final ConcurrentLinkedQueue<InterpretationResult> RESULTS =
                new ConcurrentLinkedQueue<>();
        private static volatile RuntimeException failure;

        static InterpretationResult next(InterpretationInput ignored) {
            RuntimeException currentFailure = failure;
            if (currentFailure != null) {
                throw currentFailure;
            }
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("bridge test interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            RESULTS.clear();
            failure = null;
        }
    }

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private InterpretationService interpretationService;

    @Autowired
    private MessageInterpretationRepository interpretations;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private AgentRegistryHolder holder;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetDatabaseAndInterpreter() {
        InterpreterControl.reset();
        jdbc.execute("""
                TRUNCATE app.agent_runs, app.agent_tool_calls, app.audit_logs,
                         app.async_tasks, app.message_submissions, app.message_interpretations,
                         app.review_cases
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void intentRecognitionIsRegisteredAndEnabledByDefault() {
        assertThat(holder.current().has("intent-recognition")).isTrue();
        assertThat(holder.current().isEnabled("intent-recognition")).isTrue();
        AgentDefinition definition = holder.current().bySlug("intent-recognition");
        assertThat(definition.modelRef()).isEqualTo("app.message-interpreter");
        assertThat(definition.toolNames()).isEmpty();
        assertThat(definition.description()).isEqualTo("企业微信消息意图分类与分流");
    }

    @Test
    void successfulInterpretationWritesAgentRunLinkedToSubmissionAndAudit() {
        long submissionId = submit("BRIDGE-SUCCESS-001");
        AsyncTaskStore.AsyncTask task = claim();
        InterpreterControl.RESULTS.add(successResult(MessageIntent.NON_BUSINESS));

        interpretationService.interpret(task);

        // 既有持久化并存：解释版本照常落库（行为零变化）
        List<MessageInterpretation> persisted =
                interpretations.findBySubmissionIdOrderByVersionDesc(submissionId);
        assertThat(persisted).singleElement().satisfies(item -> {
            assertThat(item.getIntent()).isEqualTo(MessageIntent.NON_BUSINESS);
            assertThat(item.getProvider()).isEqualTo("test-provider");
            assertThat(item.getModel()).isEqualTo("test-model");
            assertThat(item.getPromptVersion()).isEqualTo("test-prompt-v1");
        });

        // Agent 运行记录：run_id 关联 agent_slug/thread_id(任务)/prompt_version/业务提交
        Map<String, Object> run = singleRun();
        String runId = (String) run.get("run_id");
        assertThat(run.get("agent_slug")).isEqualTo("intent-recognition");
        assertThat(run.get("thread_id")).isEqualTo(String.valueOf(task.id()));
        assertThat(run.get("prompt_version")).isEqualTo("intent-recognition-v1");
        assertThat(run.get("model")).isEqualTo("none");
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat(run.get("error_type")).isNull();
        assertThat(run.get("input_digest")).isEqualTo(sha256(CONTENT));
        assertThat(run.values()).doesNotContain(CONTENT);
        assertThat(run.get("business_entity_type")).isEqualTo("MESSAGE_SUBMISSION");
        assertThat(run.get("business_entity_id")).isEqualTo(String.valueOf(submissionId));

        // 双向追溯：从业务提交可回溯 run_id
        assertThat(runIdsForSubmission(submissionId)).containsExactly(runId);

        // 审计按 run 关联五元组（allowlist 未配置 → provider/model/prompt_version 投影 none）
        AuditLog audit = auditForOperation("agent.intent-recognition.run");
        assertThat(audit.getTraceId()).isEqualTo(runId);
        assertThat(audit.getRequestId()).isEqualTo(runId);
        assertThat(audit.getActorType()).isEqualTo(cn.zimu.fulfillment.common.audit.AuditActorType.AGENT);
        Map<String, Object> response = audit.getResponsePayload();
        assertThat(response.get("intent")).isEqualTo("NON_BUSINESS");
        assertThat(response.get("provider")).isEqualTo("none");
        assertThat(response.get("model")).isEqualTo("none");
        assertThat(response.get("prompt_version")).isEqualTo("none");
        assertThat(response.get("error_code")).isNull();
        assertThat(response.get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void retryableFailureWritesFailedRunWithStableErrorCode() {
        long submissionId = submit("BRIDGE-FAILURE-001");
        AsyncTaskStore.AsyncTask task = claim();
        InterpreterControl.RESULTS.add(new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "MODEL_CALL_FAILED"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                "MODEL_CALL_FAILED"));

        assertThatThrownBy(() -> interpretationService.interpret(task))
                .isInstanceOf(RuntimeException.class);

        Map<String, Object> run = singleRun();
        assertThat(run.get("agent_slug")).isEqualTo("intent-recognition");
        assertThat(run.get("thread_id")).isEqualTo(String.valueOf(task.id()));
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_type")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(run.get("business_entity_id")).isEqualTo(String.valueOf(submissionId));

        Map<String, Object> response = auditForOperation("agent.intent-recognition.run").getResponsePayload();
        assertThat(response.get("status")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(response.get("error_code")).isEqualTo("MODEL_CALL_FAILED");
    }

    @Test
    void interpreterExceptionWritesFailedRunWithoutMaskingTheException() {
        long submissionId = submit("BRIDGE-EXCEPTION-001");
        AsyncTaskStore.AsyncTask task = claim();
        InterpreterControl.failure = new IllegalStateException("forced interpreter failure");

        assertThatThrownBy(() -> interpretationService.interpret(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced interpreter failure");

        Map<String, Object> run = singleRun();
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_type")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(run.get("business_entity_id")).isEqualTo(String.valueOf(submissionId));
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private long submit(String messageId) {
        return submissionService.submit(new ChannelMessageCommand(
                "corp-test",
                "connection-test",
                "bot-test",
                messageId,
                "chat-test",
                "group",
                "operator-test",
                "text",
                CONTENT,
                null,
                null,
                objectMapper.createObjectNode().put("message_id", messageId)));
    }

    private AsyncTaskStore.AsyncTask claim() {
        return taskStore.claim("bridge-test", Duration.ofSeconds(30)).orElseThrow();
    }

    private static InterpretationResult successResult(MessageIntent intent) {
        return new InterpretationResult(
                intent, Map.of("marker", "bridge"), "test-provider", "test-model", "test-prompt-v1", null);
    }

    private Map<String, Object> singleRun() {
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT run_id, thread_id, agent_slug, prompt_version, model, input_digest,"
                        + " status, error_type, business_entity_type, business_entity_id"
                        + " FROM app.agent_runs WHERE agent_slug = 'intent-recognition'");
        assertThat(runs).as("agent_runs 应恰好有一条 intent-recognition 运行行").hasSize(1);
        return runs.get(0);
    }

    private List<String> runIdsForSubmission(long submissionId) {
        return jdbc.queryForList(
                "SELECT run_id FROM app.agent_runs"
                        + " WHERE business_entity_type = 'MESSAGE_SUBMISSION'"
                        + "   AND business_entity_id = ?",
                String.class,
                String.valueOf(submissionId));
    }

    private AuditLog auditForOperation(String operation) {
        return audits.findAll().stream()
                .filter(log -> operation.equals(log.getOperation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 " + operation + " 审计"));
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
