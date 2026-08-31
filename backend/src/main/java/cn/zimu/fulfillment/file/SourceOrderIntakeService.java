package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 来源订单附件任务的提交、查询与状态真源。 */
@Service
public class SourceOrderIntakeService {

    public static final String TASK_TYPE = "SOURCE_ORDER_FILE_INTAKE";
    private static final String PAYLOAD_PREFIX = "source-order-intake:";
    private static final String IDEMPOTENCY_SCOPE = "source_order_intake.submit";

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;
    private final SourceOrderIntakeFileStore files;
    private final AuditLogService audit;
    private final IdempotencyService idempotency;

    SourceOrderIntakeService(
            JdbcTemplate jdbc,
            AsyncTaskStore tasks,
            SourceOrderIntakeFileStore files,
            AuditLogService audit,
            IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.files = files;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> submit(
            byte[] bytes,
            String originalFilename,
            String contentType,
            SourceChannel sourceChannel,
            String importMode,
            Long parentBatchId,
            String idempotencyKey,
            CommandContext context) {
        String mode = validateMode(importMode, parentBatchId, sourceChannel);
        IntakeRequest request = new IntakeRequest(
                sourceChannel.name(),
                SourceOrderIntakeFileStore.contentSha256(bytes),
                mode,
                parentBatchId);
        return idempotency.execute(
                IDEMPOTENCY_SCOPE,
                idempotencyKey,
                request,
                202,
                () -> createJob(
                        bytes,
                        originalFilename,
                        contentType,
                        sourceChannel,
                        mode,
                        parentBatchId,
                        idempotencyKey,
                        context));
    }

    private Map<String, Object> createJob(
            byte[] bytes,
            String originalFilename,
            String contentType,
            SourceChannel sourceChannel,
            String mode,
            Long parentBatchId,
            String idempotencyKey,
            CommandContext context) {
        SourceOrderIntakeFileStore.StoredFile stored = files.store(bytes, originalFilename, contentType);
        Long existing = existing(sourceChannel, stored.sha256(), mode, parentBatchId);
        if (existing != null) {
            return get(existing);
        }
        String jobNo = "SOI-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        List<Long> inserted = jdbc.query(
                """
                INSERT INTO app.source_order_intake_jobs
                    (job_no, source_channel, import_mode, parent_import_batch_id,
                     original_file_name, content_type, file_format, content_sha256,
                     file_ref, idempotency_key, submitted_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                jobNo,
                sourceChannel.name(),
                mode,
                parentBatchId,
                stored.originalFilename(),
                stored.contentType(),
                stored.format(),
                stored.sha256(),
                stored.fileRef(),
                idempotencyKey,
                context.operator());
        long id = inserted.isEmpty()
                ? requireExisting(sourceChannel, stored.sha256(), mode, parentBatchId)
                : inserted.getFirst();
        tasks.enqueue(TASK_TYPE, PAYLOAD_PREFIX + id, PAYLOAD_PREFIX + id, 3);
        return get(id);
    }

    public Map<String, Object> get(long id) {
        return jdbc.query(
                        """
                        SELECT id, job_no, source_channel, import_mode, parent_import_batch_id,
                               original_file_name, file_format, content_sha256, status, error_code,
                               import_batch_id, lock_version, created_at, updated_at
                        FROM app.source_order_intake_jobs
                        WHERE id=?
                        """,
                        (resultSet, rowNumber) -> response(resultSet),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        404, "SOURCE_ORDER_INTAKE_JOB_NOT_FOUND", "来源订单附件任务不存在"));
    }

    public FileDownload download(long id, CommandContext context) {
        FileReference reference = jdbc.query(
                        """
                        SELECT original_file_name, content_type, file_ref
                        FROM app.source_order_intake_jobs WHERE id=?
                        """,
                        (resultSet, rowNumber) -> new FileReference(
                                resultSet.getString("original_file_name"),
                                resultSet.getString("content_type"),
                                resultSet.getString("file_ref")),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        404, "SOURCE_ORDER_INTAKE_JOB_NOT_FOUND", "来源订单附件任务不存在"));
        byte[] bytes = files.load(reference.fileRef());
        audit.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("source-order-intake")
                .operation("original.download")
                .requestPayload(Map.of("job_id", id))
                .httpStatus(200)
                .businessCode("SOURCE_ORDER_ORIGINAL_DOWNLOADED"));
        return new FileDownload(reference.filename(), reference.contentType(), bytes);
    }

    IntakeJob load(long id) {
        return jdbc.query(
                        """
                        SELECT id, source_channel, import_mode, parent_import_batch_id,
                               original_file_name, content_sha256, file_ref, status,
                               idempotency_key, submitted_by
                        FROM app.source_order_intake_jobs WHERE id=?
                        """,
                        (resultSet, rowNumber) -> new IntakeJob(
                                resultSet.getLong("id"),
                                SourceChannel.valueOf(resultSet.getString("source_channel")),
                                resultSet.getString("import_mode"),
                                nullableLong(resultSet, "parent_import_batch_id"),
                                resultSet.getString("original_file_name"),
                                resultSet.getString("content_sha256"),
                                resultSet.getString("file_ref"),
                                resultSet.getString("status"),
                                resultSet.getString("idempotency_key"),
                                resultSet.getString("submitted_by")),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        404, "SOURCE_ORDER_INTAKE_JOB_NOT_FOUND", "来源订单附件任务不存在"));
    }

    @Transactional
    void markProcessing(long id) {
        jdbc.update(
                """
                UPDATE app.source_order_intake_jobs
                SET status='PROCESSING', error_code=NULL, error_detail=NULL, lock_version=lock_version+1
                WHERE id=? AND status IN ('RECEIVED', 'PROCESSING')
                """,
                id);
    }

    @Transactional
    void markNeedsExtraction(long id) {
        jdbc.update(
                """
                UPDATE app.source_order_intake_jobs
                SET status='NEEDS_EXTRACTION', error_code='TEMPLATE_FINGERPRINT_NOT_FOUND',
                    error_detail=jsonb_build_object('message', '文件结构未命中已知模板，等待 Agent 提取'),
                    lock_version=lock_version+1
                WHERE id=?
                """,
                id);
    }

    @Transactional
    void markSucceeded(long id, long importBatchId) {
        jdbc.update(
                """
                UPDATE app.source_order_intake_jobs
                SET status='SUCCEEDED', import_batch_id=?, error_code=NULL, error_detail=NULL,
                    lock_version=lock_version+1
                WHERE id=?
                """,
                importBatchId,
                id);
    }

    @Transactional
    void markNeedsReview(long id, long importBatchId, String errorCode) {
        jdbc.update(
                """
                UPDATE app.source_order_intake_jobs
                SET status='NEEDS_REVIEW', import_batch_id=?, error_code=?,
                    error_detail=jsonb_build_object(
                        'message', '受信模板批次未通过整批确定性门禁，未自动成单或发货'),
                    lock_version=lock_version+1
                WHERE id=?
                """,
                importBatchId,
                errorCode,
                id);
    }

    @Transactional
    void markReconciliationRequired(long id, long importBatchId) {
        jdbc.update(
                """
                UPDATE app.source_order_intake_jobs
                SET status='RECONCILIATION_REQUIRED', import_batch_id=?,
                    error_code='RECONCILIATION_REQUIRED',
                    error_detail=jsonb_build_object(
                        'message', '京东外部调用结果未知，必须逐 Shipment 对账，禁止自动重试'),
                    lock_version=lock_version+1
                WHERE id=?
                """,
                importBatchId,
                id);
    }

    @Transactional
    void markFailed(long id, String errorCode) {
        jdbc.update(
                """
                UPDATE app.source_order_intake_jobs
                SET status='FAILED', error_code=?, lock_version=lock_version+1
                WHERE id=?
                """,
                errorCode,
                id);
    }

    @Transactional
    void recordWorkerFailure(
            long taskId, String owner, long jobId, String errorCode, Duration backoff) {
        if (tasks.fail(taskId, owner, errorCode, backoff)) {
            markFailed(jobId, errorCode);
        }
    }

    static long jobId(String payloadRef) {
        if (payloadRef == null || !payloadRef.startsWith(PAYLOAD_PREFIX)) {
            throw new IllegalArgumentException("无效来源订单附件任务引用");
        }
        return Long.parseLong(payloadRef.substring(PAYLOAD_PREFIX.length()));
    }

    private String validateMode(String importMode, Long parentBatchId, SourceChannel sourceChannel) {
        String mode = importMode == null ? "NEW" : importMode.strip().toUpperCase();
        if (!"NEW".equals(mode) && !"REVISION".equals(mode)) {
            throw BusinessException.badRequest("IMPORT_MODE_INVALID", "import_mode 只能是 NEW 或 REVISION");
        }
        if ("NEW".equals(mode) && parentBatchId != null) {
            throw BusinessException.badRequest("PARENT_BATCH_NOT_ALLOWED", "NEW 导入不能提供父批次");
        }
        if ("REVISION".equals(mode) && parentBatchId == null) {
            throw BusinessException.badRequest("PARENT_BATCH_REQUIRED", "REVISION 导入必须提供父批次");
        }
        if (parentBatchId != null) {
            List<String> channels = jdbc.query(
                    "SELECT source_channel FROM app.import_batches WHERE id=? AND batch_type='SOURCE_ORDER'",
                    (resultSet, rowNumber) -> resultSet.getString(1),
                    parentBatchId);
            if (channels.isEmpty()) {
                throw new BusinessException(404, "PARENT_IMPORT_BATCH_NOT_FOUND", "父来源订单批次不存在");
            }
            if (!sourceChannel.name().equals(channels.getFirst())) {
                throw BusinessException.unprocessable("PARENT_SOURCE_CHANNEL_MISMATCH", "修订批次来源渠道不一致");
            }
        }
        return mode;
    }

    private Long existing(SourceChannel sourceChannel, String sha256, String mode, Long parentBatchId) {
        return jdbc.query(
                        """
                        SELECT id FROM app.source_order_intake_jobs
                        WHERE source_channel=? AND content_sha256=? AND import_mode=?
                          AND COALESCE(parent_import_batch_id, 0)=COALESCE(?, 0)
                        """,
                        (resultSet, rowNumber) -> resultSet.getLong(1),
                        sourceChannel.name(),
                        sha256,
                        mode,
                        parentBatchId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private long requireExisting(SourceChannel channel, String sha256, String mode, Long parentBatchId) {
        Long existing = existing(channel, sha256, mode, parentBatchId);
        if (existing == null) {
            throw new IllegalStateException("来源订单附件幂等冲突后未找到既有任务");
        }
        return existing;
    }

    private static Map<String, Object> response(ResultSet resultSet) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", String.valueOf(resultSet.getLong("id")));
        value.put("job_no", resultSet.getString("job_no"));
        value.put("source_channel", resultSet.getString("source_channel"));
        value.put("import_mode", resultSet.getString("import_mode"));
        Long parent = nullableLong(resultSet, "parent_import_batch_id");
        value.put("parent_import_batch_id", parent == null ? null : String.valueOf(parent));
        value.put("original_file_name", resultSet.getString("original_file_name"));
        value.put("file_format", resultSet.getString("file_format"));
        value.put("content_sha256", resultSet.getString("content_sha256"));
        value.put("status", resultSet.getString("status"));
        value.put("error_code", resultSet.getString("error_code"));
        Long batchId = nullableLong(resultSet, "import_batch_id");
        value.put("import_batch_id", batchId == null ? null : String.valueOf(batchId));
        value.put("lock_version", resultSet.getLong("lock_version"));
        value.put("created_at", resultSet.getObject("created_at", OffsetDateTime.class));
        value.put("updated_at", resultSet.getObject("updated_at", OffsetDateTime.class));
        return value;
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    record IntakeJob(
            long id,
            SourceChannel sourceChannel,
            String importMode,
            Long parentBatchId,
            String originalFilename,
            String contentSha256,
            String fileRef,
            String status,
            String idempotencyKey,
            String submittedBy) {}

    private record FileReference(String filename, String contentType, String fileRef) {}

    private record IntakeRequest(
            String sourceChannel,
            String contentSha256,
            String importMode,
            Long parentImportBatchId) {}

    public record FileDownload(String filename, String contentType, byte[] bytes) {}
}
