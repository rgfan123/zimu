package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.wecom.WecomChatReplyPolicyService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息提交生命周期：接收事务内原子保存渠道证据、创建提交并登记解释任务。
 *
 * <p>同一渠道消息的重复回调通过幂等键与 ON CONFLICT 保持只创建一次提交和一次任务；
 * 并发重复回调也不会产生重复提交或重复任务。
 */
@Service
public class MessageSubmissionService {

    public static final String INTERPRET_TASK_TYPE = "INTERPRET_MESSAGE";
    public static final String WECOM_TRACKING_FILE_TASK_TYPE = "WECOM_TRACKING_FILE";
    public static final String WECOM_CHAT_AGENT_TASK_TYPE = "WECOM_CHAT_AGENT";
    static final String WECOM_TRACKING_FILE_KEY_KIND = "wecom-tracking-file";
    static final String WECOM_CHAT_AGENT_KEY_KIND = "wecom-chat-agent";

    private final ChannelMessageIntakeService intakeService;
    private final MessageSubmissionRepository submissions;
    private final AsyncTaskStore taskStore;
    private final JdbcTemplate jdbc;
    private final AuditLogService audits;
    private final WecomChatReplyPolicyService replyPolicies;

    public MessageSubmissionService(
            ChannelMessageIntakeService intakeService,
            MessageSubmissionRepository submissions,
            AsyncTaskStore taskStore,
            JdbcTemplate jdbc,
            AuditLogService audits,
            WecomChatReplyPolicyService replyPolicies) {
        this.intakeService = intakeService;
        this.submissions = submissions;
        this.taskStore = taskStore;
        this.jdbc = jdbc;
        this.audits = audits;
        this.replyPolicies = replyPolicies;
    }

    @Transactional
    public long submit(ChannelMessageCommand command) {
        long messageId = intakeService.store(command);
        String submissionNo = "SUB-" + messageId;
        List<Long> created = jdbc.query(
                """
                INSERT INTO app.message_submissions (submission_no, source_message_id)
                VALUES (?, ?)
                ON CONFLICT (submission_no) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                submissionNo,
                messageId);
        long submissionId;
        if (created.isEmpty()) {
            Long existing = jdbc.queryForObject(
                    "SELECT id FROM app.message_submissions WHERE submission_no = ?",
                    Long.class,
                    submissionNo);
            if (existing == null) {
                throw new IllegalStateException("message submission was not visible after conflict");
            }
            submissionId = existing;
        } else {
            submissionId = created.getFirst();
        }
        String taskType = initialTaskType(command, submissionId);
        taskStore.enqueue(
                taskType,
                "submission:" + submissionId,
                AsyncTaskStore.key(taskKeyPrefix(taskType), submissionId),
                3);
        return submissionId;
    }

    @Transactional
    public long reinterpret(long submissionId, CommandContext context) {
        MessageSubmission submission = requireSubmissionForUpdate(submissionId);
        int retiredOrderDrafts = jdbc.update(
                """
                UPDATE app.order_drafts
                SET status = 'REJECTED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE submission_id = ? AND status = 'OPEN'
                """,
                context.operator(),
                submissionId);
        int retiredTrackingDrafts = jdbc.update(
                """
                UPDATE app.provider_tracking_drafts
                SET status = 'REJECTED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE submission_id = ? AND status = 'OPEN'
                """,
                context.operator(),
                submissionId);
        int dismissedCases = jdbc.update(
                """
                UPDATE app.review_cases rc
                SET status = 'DISMISSED', resolved_by = ?, resolved_at = CURRENT_TIMESTAMP,
                    resolution = jsonb_build_object(
                        'resolution_type', 'SUPERSEDED_BY_NEW_INTERPRETATION',
                        'note', 'SUPERSEDED_BY_NEW_INTERPRETATION',
                        'message_submission_id', ?::text),
                    resolution_version = resolution_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE rc.status = 'OPEN' AND (
                    rc.message_submission_id = ?
                    OR rc.order_draft_id IN (
                        SELECT id FROM app.order_drafts WHERE submission_id = ?
                    )
                    OR rc.provider_tracking_draft_id IN (
                        SELECT id FROM app.provider_tracking_drafts WHERE submission_id = ?
                    )
                )
                """,
                context.operator(),
                submissionId,
                submissionId,
                submissionId,
                submissionId);

        submission.setStatus(MessageSubmission.Status.RECEIVED);
        submissions.save(submission);
        String messageType = jdbc.queryForObject(
                """
                SELECT cm.message_type
                FROM app.channel_messages cm
                JOIN app.message_submissions ms ON ms.source_message_id=cm.id
                WHERE ms.id=?
                """,
                String.class,
                submissionId);
        taskStore.enqueue(
                taskType(messageType),
                "submission:" + submissionId,
                AsyncTaskStore.reinterpretKey(submissionId),
                3);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("message-submission")
                .operation("message_submission.reinterpret")
                .requestPayload(Map.of("submission_id", String.valueOf(submissionId)))
                .responsePayload(Map.of(
                        "retired_order_drafts", retiredOrderDrafts,
                        "retired_tracking_drafts", retiredTrackingDrafts,
                        "dismissed_review_cases", dismissedCases,
                        "task_status", "PENDING"))
                .httpStatus(200)
                .businessCode("MESSAGE_REINTERPRETATION_QUEUED"));
        return submissionId;
    }

    private MessageSubmission requireSubmissionForUpdate(long submissionId) {
        return submissions
                .findByIdForUpdate(submissionId)
                .orElseThrow(() -> BusinessException.notFound("消息提交不存在: " + submissionId));
    }

    private static String taskType(String messageType) {
        return "file".equals(messageType) ? WECOM_TRACKING_FILE_TASK_TYPE : INTERPRET_TASK_TYPE;
    }

    /**
     * 首次提交按会话绑定选择前置 Agent；重复回调沿用已经持久化的首次任务类型，
     * 避免绑定在两次平台重投之间变化时为同一消息并排创建两条入口流水线。
     */
    private String initialTaskType(ChannelMessageCommand command, long submissionId) {
        List<String> existing = jdbc.query(
                """
                SELECT task_type FROM app.async_tasks
                WHERE payload_ref = ?
                  AND task_type IN (?, ?, ?)
                ORDER BY id
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1),
                "submission:" + submissionId,
                INTERPRET_TASK_TYPE,
                WECOM_TRACKING_FILE_TASK_TYPE,
                WECOM_CHAT_AGENT_TASK_TYPE);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        if ("file".equals(command.messageType())) {
            return WECOM_TRACKING_FILE_TASK_TYPE;
        }
        return replyPolicies.assignedAgent(command.chatId(), command.chatType()).isPresent()
                ? WECOM_CHAT_AGENT_TASK_TYPE
                : INTERPRET_TASK_TYPE;
    }

    private static String taskKeyPrefix(String taskType) {
        return switch (taskType) {
            case WECOM_TRACKING_FILE_TASK_TYPE -> WECOM_TRACKING_FILE_KEY_KIND;
            case WECOM_CHAT_AGENT_TASK_TYPE -> WECOM_CHAT_AGENT_KEY_KIND;
            default -> "interpret";
        };
    }
}
