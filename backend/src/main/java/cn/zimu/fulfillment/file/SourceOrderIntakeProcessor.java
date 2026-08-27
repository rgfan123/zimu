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

    SourceOrderIntakeProcessor(
            SourceOrderIntakeService intake,
            SourceOrderIntakeFileStore files,
            SourceFileParser parser,
            SourceImportService imports) {
        this.intake = intake;
        this.files = files;
        this.parser = parser;
        this.imports = imports;
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
            intake.markSucceeded(jobId, Long.parseLong(batch.get("id").toString()));
        } catch (BusinessException exception) {
            intake.markFailed(jobId, exception.getBusinessCode());
        }
    }
}
