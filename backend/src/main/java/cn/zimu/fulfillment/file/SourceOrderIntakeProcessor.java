package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 单个来源订单附件任务的幂等处理器；模型 fallback 由后续候选提取切片接入。 */
@Service
public class SourceOrderIntakeProcessor {

    private final SourceOrderIntakeService intake;
    private final SourceOrderIntakeFileStore files;
    private final SourceFileParser parser;
    private final SourceImportService imports;
    private final SourceBatchAutomaticReleaseService automaticRelease;

    SourceOrderIntakeProcessor(
            SourceOrderIntakeService intake,
            SourceOrderIntakeFileStore files,
            SourceFileParser parser,
            SourceImportService imports,
            SourceBatchAutomaticReleaseService automaticRelease) {
        this.intake = intake;
        this.files = files;
        this.parser = parser;
        this.imports = imports;
        this.automaticRelease = automaticRelease;
    }

    public void process(AsyncTaskStore.AsyncTask task) {
        long jobId = SourceOrderIntakeService.jobId(task.payloadRef());
        SourceOrderIntakeService.IntakeJob job = intake.load(jobId);
        if ("SUCCEEDED".equals(job.status())
                || "NEEDS_EXTRACTION".equals(job.status())
                || "FAILED".equals(job.status())) {
            return;
        }
        intake.markProcessing(jobId);
        byte[] bytes = files.load(job.fileRef());
        final ParsedSourceFile parsed;
        try {
            parsed = parser.parse(bytes);
        } catch (BusinessException exception) {
            if ("TEMPLATE_FINGERPRINT_NOT_FOUND".equals(exception.getBusinessCode())) {
                intake.markNeedsExtraction(jobId);
            } else {
                intake.markFailed(jobId, exception.getBusinessCode());
            }
            return;
        }
        if (parsed.sourceChannel() != job.sourceChannel()) {
            intake.markFailed(jobId, "SOURCE_CHANNEL_MISMATCH");
            return;
        }
        try {
            CommandContext context = new CommandContext(
                    "source-order-intake-" + jobId,
                    "source-order-intake-" + jobId,
                    job.submittedBy());
            Map<String, Object> batch = imports.upload(
                    bytes,
                    job.originalFilename(),
                    job.importMode(),
                    job.parentBatchId(),
                    job.idempotencyKey(),
                    context);
            long batchId = Long.parseLong(batch.get("id").toString());
            try {
                automaticRelease.releaseIfTrusted(batchId);
            } catch (BusinessException exception) {
                if ("RECONCILIATION_REQUIRED".equals(exception.getBusinessCode())) {
                    intake.markReconciliationRequired(jobId, batchId);
                    return;
                }
                if ("IMPORT_BATCH_BLOCKED".equals(exception.getBusinessCode())
                        || "AUTOMATIC_RELEASE_OUTBOUND_BLOCKED".equals(exception.getBusinessCode())
                        || "TEMPLATE_PROFILE_REVOKED".equals(exception.getBusinessCode())
                        || "TEMPLATE_PROFILE_MISMATCH".equals(exception.getBusinessCode())
                        || "AUTOMATIC_RELEASE_STATE_INVALID".equals(exception.getBusinessCode())) {
                    intake.markNeedsReview(jobId, batchId, exception.getBusinessCode());
                    return;
                }
                throw exception;
            }
            intake.markSucceeded(jobId, batchId);
        } catch (BusinessException exception) {
            intake.markFailed(jobId, exception.getBusinessCode());
        }
    }
}
