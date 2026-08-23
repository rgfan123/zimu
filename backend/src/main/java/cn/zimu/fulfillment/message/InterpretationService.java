package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.agent.IntentRecognitionAgentBridge;
import cn.zimu.fulfillment.agent.IntentRecognitionRunMetadata;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.wecom.WecomMediaEvidenceService;
import cn.zimu.fulfillment.connector.wecom.MediaEvidenceCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 消息解释业务：调用模型接缝、追加解释版本、更新提交状态并执行意图分流。
 *
 * <p>解释版本按提交递增且不覆盖历史；路由与版本写入在同一事务内，Worker 重试或并发领取
 * 不会重复创建解释版本或复核事项。
 */
@Service
public class InterpretationService {

    private final MessageInterpreter interpreter;
    private final MessageSubmissionRepository submissions;
    private final MessageInterpretationRepository interpretations;
    private final ChannelMessageQueryService messageQuery;
    private final IntentRouter intentRouter;
    private final AsyncTaskStore taskStore;
    private final MessageStructuredOutputBoundary outputBoundary;
    private final WecomMediaEvidenceService mediaService;
    private final IntentRecognitionAgentBridge intentRecognitionBridge;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate required;

    public InterpretationService(
            MessageInterpreter interpreter,
            MessageSubmissionRepository submissions,
            MessageInterpretationRepository interpretations,
            ChannelMessageQueryService messageQuery,
            IntentRouter intentRouter,
            AsyncTaskStore taskStore,
            MessageStructuredOutputBoundary outputBoundary,
            WecomMediaEvidenceService mediaService,
            IntentRecognitionAgentBridge intentRecognitionBridge,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.interpreter = interpreter;
        this.submissions = submissions;
        this.interpretations = interpretations;
        this.messageQuery = messageQuery;
        this.intentRouter = intentRouter;
        this.taskStore = taskStore;
        this.outputBoundary = outputBoundary;
        this.mediaService = mediaService;
        this.intentRecognitionBridge = intentRecognitionBridge;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.required = new TransactionTemplate(transactionManager);
    }

    /**
     * 三段式解释：事务内领取持久化因果门禁，事务外调用模型，再在单一事务中
     * 重验租约/代际并原子应用解释结果与任务成功状态。
     *
     * <p>07 票运行桥：模型调用前后经 {@link IntentRecognitionAgentBridge} 把本次尝试写入
     * Agent 运行记录（thread_id=任务 id，business_entity=MESSAGE_SUBMISSION），
     * 与既有 MessageInterpretation 持久化并存；桥写入失败不影响解释结果。
     */
    public void interpret(AsyncTaskStore.AsyncTask task) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("消息解释模型不得在数据库事务内调用");
        }
        Optional<InterpretationInput> prepared = required.execute(status -> prepare(task));
        if (prepared == null || prepared.isEmpty()) {
            return;
        }

        InterpretationInput input = prepared.get();
        String threadId = String.valueOf(task.id());
        String agentRunId = intentRecognitionBridge.runStarted(
                threadId, task.submissionId(), input.content());
        long startedNanos = System.nanoTime();
        InterpretationResult result;
        try {
            result = outputBoundary.failClosed(interpreter.interpret(input));
        } catch (RuntimeException ex) {
            intentRecognitionBridge.runFinished(
                    agentRunId,
                    IntentRecognitionRunMetadata.failed(InterpretationFailureCode.MODEL_CALL_FAILED.name()),
                    elapsedMillis(startedNanos));
            throw ex;
        }
        String errorCode = InterpretationFailureCode.normalize(result.error(), result.structuredOutput());
        intentRecognitionBridge.runFinished(
                agentRunId,
                new IntentRecognitionRunMetadata(
                        result.provider(),
                        result.model(),
                        result.promptVersion(),
                        result.intent().name(),
                        errorCode),
                elapsedMillis(startedNanos));
        if (InterpretationFailureCode.MODEL_CALL_FAILED.name().equals(errorCode)) {
            throw new RetryableInterpretationFailure();
        }
        required.executeWithoutResult(status -> complete(task, result));
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private Optional<InterpretationInput> prepare(AsyncTaskStore.AsyncTask task) {
        MessageSubmission submission = requireSubmissionForUpdate(task.submissionId());
        AsyncTaskStore.ApplicationFence fence =
                taskStore.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return Optional.empty();
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return Optional.empty();
        }
        ChannelMessageDetailDto message = messageQuery.detail(submission.getSourceMessageId());
        if ("file".equals(message.messageType())) {
            // #86 fail-closed 兼容门：升级前遗留的 INTERPRET_MESSAGE 文件任务也绝不调用模型，
            // 原子补排专用确定性文件任务后把旧任务收敛为成功。
            taskStore.enqueue(
                    MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE,
                    "submission:" + submission.getId(),
                    AsyncTaskStore.key(MessageSubmissionService.WECOM_TRACKING_FILE_KEY_KIND, submission.getId()),
                    3);
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return Optional.empty();
        }
        return Optional.of(new InterpretationInput(
                submission.getId(),
                message.content(),
                message.quoteType(),
                message.quoteContent(),
                prepareMediaRefs(task, submission, message)));
    }

    /**
     * 解释前的媒体证据准备（wecom-message-intake 07）：从原始载荷提取 image/mixed 媒体项并
     * 下载解密到受控存储（幂等，已 AVAILABLE 直接复用），受控引用进入 {@code mediaContentRefs}。
     *
     * <p>暂时失败（PENDING）或终态失败（FAILED）都抛 {@link RetryableInterpretationFailure}：
     * 走既有 3 次重试与终态 NEED_REVIEW 收口，模型不会在证据缺失时被调用。
     */
    private List<String> prepareMediaRefs(
            AsyncTaskStore.AsyncTask task, MessageSubmission submission, ChannelMessageDetailDto message) {
        JsonNode rawPayload = rawPayload(message);
        if (rawPayload == null) {
            return List.of();
        }
        List<WecomMediaEvidenceService.MediaRef> refs =
                WecomMediaEvidenceService.extractMediaRefs(rawPayload.path("body"));
        if (refs.isEmpty()) {
            return List.of();
        }
        List<String> contentRefs = new ArrayList<>();
        for (int index = 0; index < refs.size(); index++) {
            WecomMediaEvidenceService.MediaRef ref = refs.get(index);
            MediaResult result = mediaService.storeMedia(new MediaEvidenceCommand(
                    submission.getSourceMessageId(),
                    submission.getId(),
                    "img-" + index,
                    "image",
                    ref.url(),
                    ref.aeskey()));
            if (result.status() != MediaResultStatus.SUCCEEDED) {
                throw new RetryableInterpretationFailure();
            }
            contentRefs.add(result.storageRef());
        }
        return contentRefs;
    }

    private JsonNode rawPayload(ChannelMessageDetailDto message) {
        List<JsonNode> rows = jdbc.query(
                "SELECT raw_payload FROM app.channel_messages WHERE id = ?",
                (resultSet, rowNumber) -> {
                    try {
                        return objectMapper.readTree(resultSet.getString("raw_payload"));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                        throw new IllegalStateException("渠道消息原始载荷不是合法 JSON", ex);
                    }
                },
                Long.parseLong(message.id()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void complete(AsyncTaskStore.AsyncTask task, InterpretationResult result) {
        MessageSubmission submission = requireSubmissionForUpdate(task.submissionId());
        AsyncTaskStore.ApplicationFence fence =
                taskStore.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return;
        }

        String safeError = InterpretationFailureCode.normalize(
                result.error(), result.structuredOutput());
        InterpretationResult safeResult = new InterpretationResult(
                safeError == null ? result.intent() : MessageIntent.NEED_REVIEW,
                safeError == null ? result.structuredOutput() : Map.of("reason", safeError),
                result.provider(),
                result.model(),
                result.promptVersion(),
                safeError);

        int version = interpretations.currentVersion(submission.getId()) + 1;
        MessageInterpretation interpretation = new MessageInterpretation();
        interpretation.setSubmissionId(submission.getId());
        interpretation.setVersion(version);
        interpretation.setProvider(safeResult.provider());
        interpretation.setModel(safeResult.model());
        interpretation.setPromptVersion(safeResult.promptVersion());
        interpretation.setIntent(safeResult.intent());
        interpretation.setStructuredOutput(safeResult.structuredOutput());
        interpretation.setError(safeResult.error());
        interpretations.save(interpretation);

        // 先设置基础状态，再路由：草稿工厂可在同一事务内把状态升级为 DRAFTED
        submission.setStatus(safeResult.error() == null
                ? MessageSubmission.Status.INTERPRETED
                : MessageSubmission.Status.FAILED);
        submissions.save(submission);

        intentRouter.route(submission, safeResult);
        taskStore.succeedOwned(task.id(), task.leaseOwner());
    }

    /**
     * 记录本次解释失败：任务重试/终态与最终 NEED_REVIEW 在同一事务中收口。
     *
     * <p>如果该任务已丢失租约，则旧 Worker 不得写入任何业务事实；如果已有更新一代任务，
     * 则只将旧任务收敛为成功。最终待办写入失败时，任务 FAILED 与 Submission FAILED 也一并回滚，
     * 保留 RUNNING 租约供超时后恢复。
     */
    public void recordFailure(
            AsyncTaskStore.AsyncTask task, String error, Duration backoff) {
        FailureAction action = required.execute(status -> prepareFailure(task, error, backoff));
        if (action == FailureAction.FINALIZE) {
            required.executeWithoutResult(status -> finalizeFailure(task, error));
        }
    }

    /** 恢复已耗尽三次模型调用的最终收口；此路径绝不再进入 {@link MessageInterpreter}。 */
    public void resumeFinalization(AsyncTaskStore.AsyncTask task) {
        Boolean redirected = required.execute(status -> redirectLegacyFileFinalization(task));
        if (Boolean.TRUE.equals(redirected)) {
            return;
        }
        required.executeWithoutResult(status -> finalizeFailure(task, task.lastError()));
    }

    /** 升级前已进入 FINALIZING 的 file 解释任务也改道专用队列，不能落成伪模型失败。 */
    private boolean redirectLegacyFileFinalization(AsyncTaskStore.AsyncTask task) {
        MessageSubmission submission = requireSubmissionForUpdate(task.submissionId());
        ChannelMessageDetailDto message = messageQuery.detail(submission.getSourceMessageId());
        if (!"file".equals(message.messageType())) {
            return false;
        }
        AsyncTaskStore.ApplicationFence fence = taskStore.lockFinalizationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return true;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return true;
        }
        taskStore.enqueue(
                MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE,
                "submission:" + submission.getId(),
                AsyncTaskStore.key(MessageSubmissionService.WECOM_TRACKING_FILE_KEY_KIND, submission.getId()),
                3);
        taskStore.succeedOwned(task.id(), task.leaseOwner());
        return true;
    }

    private FailureAction prepareFailure(
            AsyncTaskStore.AsyncTask task, String error, Duration backoff) {
        MessageSubmission submission = requireSubmissionForUpdate(task.submissionId());
        AsyncTaskStore.ApplicationFence fence =
                taskStore.lockApplicationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return FailureAction.NONE;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return FailureAction.NONE;
        }

        AsyncTaskStore.FailureTransition transition =
                taskStore.recordFailureOwned(task.id(), task.leaseOwner(), error, backoff);
        return transition == AsyncTaskStore.FailureTransition.FINALIZING
                ? FailureAction.FINALIZE
                : FailureAction.NONE;
    }

    private void finalizeFailure(AsyncTaskStore.AsyncTask task, String error) {
        MessageSubmission submission = requireSubmissionForUpdate(task.submissionId());
        AsyncTaskStore.ApplicationFence fence =
                taskStore.lockFinalizationFence(task.id(), task.leaseOwner());
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            return;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            taskStore.succeedOwned(task.id(), task.leaseOwner());
            return;
        }

        String safeError = InterpretationFailureCode.normalize(error, Map.of());
        if (safeError == null) {
            safeError = InterpretationFailureCode.MODEL_CALL_FAILED.name();
        }
        MessageInterpretation terminal = new MessageInterpretation();
        terminal.setSubmissionId(submission.getId());
        terminal.setVersion(interpretations.currentVersion(submission.getId()) + 1);
        terminal.setProvider("none");
        terminal.setModel("none");
        terminal.setPromptVersion("none");
        terminal.setIntent(MessageIntent.NEED_REVIEW);
        terminal.setStructuredOutput(Map.of("reason", safeError));
        terminal.setError(safeError);
        interpretations.save(terminal);

        submission.setStatus(MessageSubmission.Status.FAILED);
        submissions.save(submission);
        intentRouter.routeFinalFailure(submission, safeError);
        taskStore.finalizeFailedOwned(task.id(), task.leaseOwner(), safeError);
    }

    private enum FailureAction {
        NONE,
        FINALIZE
    }

    /** Stable control-flow signal; raw provider failures must never become exception messages. */
    private static final class RetryableInterpretationFailure extends RuntimeException {

        private RetryableInterpretationFailure() {
            super(null, null, false, false);
        }
    }

    private MessageSubmission requireSubmissionForUpdate(long submissionId) {
        return submissions
                .findByIdForUpdate(submissionId)
                .orElseThrow(() -> BusinessException.notFound("消息提交不存在: " + submissionId));
    }
}
