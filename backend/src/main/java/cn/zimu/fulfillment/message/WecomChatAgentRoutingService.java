package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.wecom.WecomChatAgentReplyDispatcher;
import cn.zimu.fulfillment.connector.wecom.WecomChatReplyPolicyService;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #178 会话级 Agent 前置路由：受限只读问答成功即回原会话，其余结局原子补排既有解释任务。
 *
 * <p>模型与企微外呼都在事务外执行；持久任务租约在外呼前复查/续期，成功或回落再于事务内
 * 锁定 submission → task fence 收口。回落只创建原有 {@code INTERPRET_MESSAGE} 任务，后续
 * 仍由 {@link InterpretationService}/{@link IntentRouter} 原样处理，不复制任何收单规则。
 */
@Service
public class WecomChatAgentRoutingService {

    public static final Set<String> READ_ONLY_MODULES = Set.of("masterdata", "inventory");

    private static final Logger log = LoggerFactory.getLogger(WecomChatAgentRoutingService.class);

    private final MessageSubmissionRepository submissions;
    private final ChannelMessageQueryService messages;
    private final WecomChatReplyPolicyService replyPolicies;
    private final MessageInterpreter businessIntentClassifier;
    private final MessageStructuredOutputBoundary outputBoundary;
    private final AgentRuntimeFacade agents;
    private final WecomChatAgentReplyDispatcher replies;
    private final AsyncTaskStore tasks;
    private final AuditLogService audits;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate required;

    public WecomChatAgentRoutingService(
            MessageSubmissionRepository submissions,
            ChannelMessageQueryService messages,
            WecomChatReplyPolicyService replyPolicies,
            MessageInterpreter businessIntentClassifier,
            MessageStructuredOutputBoundary outputBoundary,
            AgentRuntimeFacade agents,
            WecomChatAgentReplyDispatcher replies,
            AsyncTaskStore tasks,
            AuditLogService audits,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.submissions = submissions;
        this.messages = messages;
        this.replyPolicies = replyPolicies;
        this.businessIntentClassifier = businessIntentClassifier;
        this.outputBoundary = outputBoundary;
        this.agents = agents;
        this.replies = replies;
        this.tasks = tasks;
        this.audits = audits;
        this.jdbc = jdbc;
        this.required = new TransactionTemplate(transactionManager);
    }

    public void route(AsyncTaskStore.AsyncTask task, Duration lease) {
        Optional<PreparedRoute> prepared = required.execute(status -> prepare(task));
        if (prepared == null || prepared.isEmpty()) {
            return;
        }
        PreparedRoute route = prepared.get();
        if (route.deliveryUncertain()) {
            log.warn(
                    "会话 Agent 存在未收口发送围栏，禁止盲目重发 submission_id={} task_id={}",
                    route.submissionId(),
                    route.agentTaskId());
            fallback(task, route, "WECOM_REPLY_UNKNOWN", null, "UNKNOWN");
            return;
        }
        if (route.agentSlug() == null) {
            fallback(task, route, "AGENT_BINDING_MISSING", null, null);
            return;
        }

        String businessIntentReason = businessIntentReason(route);
        if (businessIntentReason != null) {
            fallback(task, route, businessIntentReason, null, null);
            return;
        }

        AgentRunResult result;
        try {
            result = agents.invokeReadOnlyModules(
                    route.agentSlug(),
                    route.message().content(),
                    new AgentRunContext(
                            route.message().chatId(),
                            "wecom:" + route.message().senderUserId(),
                            "MESSAGE_SUBMISSION",
                            String.valueOf(route.submissionId())),
                    READ_ONLY_MODULES);
        } catch (RuntimeException ex) {
            log.warn(
                    "会话 Agent 运行异常，回落收单 submission_id={} agent_slug={} reason=AGENT_RUNTIME_EXCEPTION",
                    route.submissionId(),
                    route.agentSlug());
            fallback(task, route, "AGENT_RUNTIME_EXCEPTION", null, null);
            return;
        }

        AgentDecision decision = AgentDecision.from(result);
        if (decision.fallbackReason() != null) {
            fallback(task, route, decision.fallbackReason(), result.runId(), null);
            return;
        }

        if (!tasks.renewLease(task.id(), task.leaseOwner(), lease)) {
            log.warn(
                    "会话 Agent 回答前租约已丢失，放弃外呼等待新 owner 收口 submission_id={}",
                    route.submissionId());
            return;
        }
        if (!markSending(task, route, result.runId())) {
            return;
        }
        if (!tasks.renewLease(task.id(), task.leaseOwner(), lease)) {
            // SENDING 已持久化但旧 owner 在真正外呼前失租：绝不发送。新 owner 重领后会把
            // 未收口围栏转为 UNKNOWN 并回落原收单流水线。
            log.warn(
                    "会话 Agent 外呼前二次续租失败，禁止旧 owner 发送 submission_id={} task_id={}",
                    route.submissionId(),
                    task.id());
            return;
        }

        WecomSendResult sent;
        try {
            sent = replies.send(
                    replyPolicies.outboundChatId(
                            route.message().chatId(), route.message().chatType()),
                    decision.answer());
        } catch (RuntimeException ex) {
            log.warn(
                    "会话 Agent 企微出口提交结果未知，回落收单 submission_id={} reason=WECOM_REPLY_UNKNOWN",
                    route.submissionId());
            fallback(task, route, "WECOM_REPLY_UNKNOWN", result.runId(), "UNKNOWN");
            return;
        }
        if (sent.status() != WecomSendStatus.SUCCESS) {
            String reason = sent.status() == WecomSendStatus.TIMEOUT
                    ? "WECOM_REPLY_UNKNOWN"
                    : "WECOM_REPLY_" + sent.status().name();
            log.warn(
                    "会话 Agent 回答未送达，回落收单 submission_id={} reason={}",
                    route.submissionId(),
                    reason);
            fallback(task, route, reason, result.runId(), sent.status().name());
            return;
        }

        completeAnswer(task, route, result.runId(), sent.status().name());
    }

    /** Worker 自身连续失败后的最终恢复：不再调用 Agent，直接保证原收单任务存在。 */
    public void resumeFinalFallback(AsyncTaskStore.AsyncTask task, String reason) {
        required.executeWithoutResult(status -> {
            MessageSubmission submission = requireSubmission(task.submissionId());
            AsyncTaskStore.ApplicationFence fence =
                    tasks.lockFinalizationFence(task.id(), task.leaseOwner());
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
                return;
            }
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
                tasks.succeedOwned(task.id(), task.leaseOwner());
                return;
            }
            ChannelMessageDetailDto message = messages.detail(submission.getSourceMessageId());
            PreparedRoute route = new PreparedRoute(
                    task.id(),
                    submission.getId(),
                    message,
                    replyPolicies.assignedAgent(message.chatId(), message.chatType()).orElse(null),
                    false);
            enqueueOriginalIntake(submission.getId());
            String stableReason = reason == null || reason.isBlank()
                    ? "WECOM_CHAT_AGENT_WORKER_FAILED"
                    : reason;
            recordRouteAuditRequired(
                    route, "WECOM_CHAT_AGENT_FALLBACK_INTAKE", stableReason, null, null);
            tasks.finalizeFailedOwned(task.id(), task.leaseOwner(), stableReason);
        });
    }

    public void recordWorkerFailure(
            AsyncTaskStore.AsyncTask task, String error, Duration backoff) {
        AsyncTaskStore.FailureTransition transition = required.execute(status -> {
            requireSubmission(task.submissionId());
            AsyncTaskStore.ApplicationFence fence =
                    tasks.lockApplicationFence(task.id(), task.leaseOwner());
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
                return null;
            }
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
                tasks.succeedOwned(task.id(), task.leaseOwner());
                return null;
            }
            return tasks.recordFailureOwned(task.id(), task.leaseOwner(), error, backoff);
        });
        if (transition == AsyncTaskStore.FailureTransition.FINALIZING) {
            resumeFinalFallback(task, error);
        }
    }

    private Optional<PreparedRoute> prepare(AsyncTaskStore.AsyncTask task) {
        MessageSubmission submission = requireSubmission(task.submissionId());
        AsyncTaskStore.ApplicationFence fence =
                tasks.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return Optional.empty();
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), task.leaseOwner());
            return Optional.empty();
        }
        ChannelMessageDetailDto message = messages.detail(submission.getSourceMessageId());
        return Optional.of(new PreparedRoute(
                task.id(),
                submission.getId(),
                message,
                replyPolicies.assignedAgent(message.chatId(), message.chatType()).orElse(null),
                hasUnfinishedSendFence(task.id())));
    }

    /**
     * 发送前持久围栏：必须先提交 SENDING 审计事实，之后才允许调用企微。围栏写失败不外呼；
     * 进程若在 ACK 后、任务成功前崩溃，重领会命中该事实并转 UNKNOWN/原收单，不会盲发第二次。
     */
    private boolean markSending(
            AsyncTaskStore.AsyncTask task, PreparedRoute route, String runId) {
        Boolean marked = required.execute(status -> {
            requireSubmission(route.submissionId());
            AsyncTaskStore.ApplicationFence fence =
                    tasks.lockApplicationFence(task.id(), task.leaseOwner());
            if (fence.disposition() != AsyncTaskStore.ApplicationDisposition.CURRENT) {
                return false;
            }
            recordRouteAuditRequired(
                    route, "WECOM_CHAT_AGENT_REPLY_SENDING", null, runId, "SENDING");
            return true;
        });
        return Boolean.TRUE.equals(marked);
    }

    private boolean hasUnfinishedSendFence(long taskId) {
        Boolean unfinished = required.execute(status -> jdbcBoolean(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.audit_logs sending
                    WHERE sending.operation = 'wecom.chat-agent.route'
                      AND sending.business_code = 'WECOM_CHAT_AGENT_REPLY_SENDING'
                      AND sending.request_payload ->> 'task_id' = ?
                ) AND NOT EXISTS (
                    SELECT 1 FROM app.audit_logs terminal
                    WHERE terminal.operation = 'wecom.chat-agent.route'
                      AND terminal.business_code IN (
                          'WECOM_CHAT_AGENT_ANSWER_SENT',
                          'WECOM_CHAT_AGENT_FALLBACK_INTAKE',
                          'WECOM_CHAT_AGENT_REPLY_UNKNOWN')
                      AND terminal.request_payload ->> 'task_id' = ?
                )
                """,
                String.valueOf(taskId),
                String.valueOf(taskId)));
        return Boolean.TRUE.equals(unfinished);
    }

    /**
     * 复用现有六意图解释契约只做安全门：明确业务意图直接回原流水线；查询类当前会落
     * NEED_REVIEW，因此继续交会话 Agent。非纯文本证据（表格/图片/混合消息）不在这里
     * 重做媒体准备，直接交回原流水线，保证业务单据不会被问答出口截走。
     */
    private String businessIntentReason(PreparedRoute route) {
        ChannelMessageDetailDto message = route.message();
        if (!"text".equals(message.messageType())) {
            return "BUSINESS_EVIDENCE_REQUIRES_INTAKE";
        }
        InterpretationResult classified;
        try {
            classified = outputBoundary.failClosed(businessIntentClassifier.interpret(
                    new InterpretationInput(
                            route.submissionId(),
                            message.content(),
                            message.quoteType(),
                            message.quoteContent(),
                            List.of())));
        } catch (RuntimeException ex) {
            log.warn(
                    "会话 Agent 业务意图安全门异常，回落收单 submission_id={} reason=BUSINESS_INTENT_CHECK_FAILED",
                    route.submissionId());
            return "BUSINESS_INTENT_CHECK_FAILED";
        }
        String error = InterpretationFailureCode.normalize(
                classified.error(), classified.structuredOutput());
        if (error != null) {
            return "BUSINESS_INTENT_CHECK_" + error;
        }
        return switch (classified.intent()) {
            case CUSTOMER_ORDER, SUPPLIER_TRACKING, ORDER_CHANGE, ORDER_CANCEL ->
                    "BUSINESS_INTENT_" + classified.intent().name();
            case NON_BUSINESS, NEED_REVIEW -> null;
        };
    }

    private void fallback(
            AsyncTaskStore.AsyncTask task,
            PreparedRoute route,
            String reason,
            String runId,
            String outboundStatus) {
        required.executeWithoutResult(status -> {
            requireSubmission(route.submissionId());
            AsyncTaskStore.ApplicationFence fence =
                    tasks.lockApplicationFence(task.id(), task.leaseOwner());
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
                return;
            }
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
                tasks.succeedOwned(task.id(), task.leaseOwner());
                return;
            }
            enqueueOriginalIntake(route.submissionId());
            String businessCode = "WECOM_REPLY_UNKNOWN".equals(reason)
                    ? "WECOM_CHAT_AGENT_REPLY_UNKNOWN"
                    : "WECOM_CHAT_AGENT_FALLBACK_INTAKE";
            recordRouteAuditRequired(
                    route, businessCode, reason, runId, outboundStatus);
            tasks.succeedOwned(task.id(), task.leaseOwner());
        });
        log.info(
                "会话 Agent 已回落原收单流水线 submission_id={} agent_slug={} reason={}",
                route.submissionId(),
                route.agentSlug(),
                reason);
    }

    private void completeAnswer(
            AsyncTaskStore.AsyncTask task,
            PreparedRoute route,
            String runId,
            String outboundStatus) {
        required.executeWithoutResult(status -> {
            requireSubmission(route.submissionId());
            AsyncTaskStore.ApplicationFence fence =
                    tasks.lockApplicationFence(task.id(), task.leaseOwner());
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
                return;
            }
            if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
                tasks.succeedOwned(task.id(), task.leaseOwner());
                return;
            }
            recordRouteAuditRequired(
                    route, "WECOM_CHAT_AGENT_ANSWER_SENT", null, runId, outboundStatus);
            tasks.succeedOwned(task.id(), task.leaseOwner());
        });
        log.info(
                "会话 Agent 回答已送达 submission_id={} agent_slug={} run_id={}",
                route.submissionId(),
                route.agentSlug(),
                runId);
    }

    private void enqueueOriginalIntake(long submissionId) {
        tasks.enqueue(
                MessageSubmissionService.INTERPRET_TASK_TYPE,
                "submission:" + submissionId,
                AsyncTaskStore.key("interpret", submissionId),
                3);
    }

    private void recordRouteAuditRequired(
            PreparedRoute route,
            String businessCode,
            String reason,
            String runId,
            String outboundStatus) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", businessCode);
        if (reason != null) {
            response.put("reason", reason);
        }
        if (runId != null && !runId.isBlank()) {
            response.put("run_id", runId);
        }
        if (outboundStatus != null) {
            response.put("outbound_status", outboundStatus);
        }
        String requestId = runId == null || runId.isBlank()
                ? "wecom-chat-agent-task-" + route.submissionId()
                : runId;
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(requestId)
                .traceId(requestId)
                .operator("wecom-chat-agent")
                .actorType(AuditActorType.SYSTEM)
                .service("wecom-chat-agent")
                .operation("wecom.chat-agent.route")
                .requestPayload(Map.of(
                        "task_id", String.valueOf(route.agentTaskId()),
                        "submission_id", String.valueOf(route.submissionId()),
                        "chat_id", route.message().chatId(),
                        "agent_slug", route.agentSlug() == null ? "none" : route.agentSlug(),
                        "allowed_modules", READ_ONLY_MODULES))
                .responsePayload(response)
                .businessCode(businessCode));
    }

    private boolean jdbcBoolean(String sql, Object... args) {
        Boolean value = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(value);
    }

    private MessageSubmission requireSubmission(long submissionId) {
        return submissions.findByIdForUpdate(submissionId)
                .orElseThrow(() -> BusinessException.notFound("消息提交不存在: " + submissionId));
    }

    private record PreparedRoute(
            long agentTaskId,
            long submissionId,
            ChannelMessageDetailDto message,
            String agentSlug,
            boolean deliveryUncertain) {}

    private record AgentDecision(String answer, String fallbackReason) {

        private static AgentDecision from(AgentRunResult result) {
            if (result == null) {
                return new AgentDecision(null, "AGENT_RESULT_MISSING");
            }
            if (result.error() != null) {
                return new AgentDecision(null, result.error());
            }
            JsonNode output = result.output();
            if (output == null || output.isNull()) {
                return new AgentDecision(null, "AGENT_OUTPUT_INVALID");
            }
            if (output.path("business_document").asBoolean(false)
                    || output.path("route_to_intake").asBoolean(false)
                    || output.path("requires_human").asBoolean(false)
                    || "BUSINESS_INTAKE".equals(output.path("route").asText())) {
                return new AgentDecision(null, "AGENT_ROUTED_TO_INTAKE");
            }
            String answer = output.isTextual()
                    ? output.asText()
                    : output.path("answer").asText("");
            if (answer == null || answer.isBlank()) {
                return new AgentDecision(null, "AGENT_OUTPUT_INVALID");
            }
            return new AgentDecision(answer.strip(), null);
        }
    }
}
