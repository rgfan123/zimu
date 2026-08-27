package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.WriteCommands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class SourceOrderIntakeController {

    private final SourceOrderIntakeService service;

    SourceOrderIntakeController(SourceOrderIntakeService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/source-order-intake-jobs", consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam("source_channel") String sourceChannel,
            @RequestParam(value = "import_mode", defaultValue = "NEW") String importMode,
            @RequestParam(value = "parent_import_batch_id", required = false) String parentBatchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) throws IOException {
        SourceChannel channel;
        try {
            channel = SourceChannel.valueOf(sourceChannel == null ? "" : sourceChannel.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw BusinessException.badRequest("SOURCE_CHANNEL_INVALID", "source_channel 不是已登记来源渠道");
        }
        Long parent = parentBatchId == null ? null : WriteCommands.parseIdentifier(parentBatchId);
        Map<String, Object> job = service.submit(
                file.getBytes(),
                file.getOriginalFilename(),
                file.getContentType(),
                channel,
                importMode,
                parent,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator));
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/api/v1/source-order-intake-jobs/{job_id}")
    Map<String, Object> get(@PathVariable("job_id") String jobId) {
        return service.get(WriteCommands.parseIdentifier(jobId));
    }

    @GetMapping("/api/v1/source-order-intake-jobs/{job_id}/file")
    ResponseEntity<byte[]> download(
            @PathVariable("job_id") String jobId,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        SourceOrderIntakeService.FileDownload file = service.download(
                WriteCommands.parseIdentifier(jobId), WriteCommands.writeContext(operator));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.bytes());
    }
}
