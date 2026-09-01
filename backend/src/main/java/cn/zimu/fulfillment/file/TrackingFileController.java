package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.web.WriteCommands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
class TrackingFileController {

    private static final Logger log = LoggerFactory.getLogger(TrackingFileController.class);

    private final TrackingFileService service;
    private final SourceReturnPushService pushService;
    private final SourceReturnDerivationRunner sourceReturnDerivations;

    TrackingFileController(
            TrackingFileService service,
            SourceReturnPushService pushService,
            SourceReturnDerivationRunner sourceReturnDerivations) {
        this.service = service;
        this.pushService = pushService;
        this.sourceReturnDerivations = sourceReturnDerivations;
    }

    @PostMapping(path = "/api/v1/fulfillment-exports/{export_id}/tracking-imports", consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> upload(
            @PathVariable("export_id") String exportId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "import_mode", defaultValue = "NEW") String importMode,
            @RequestParam(value = "parent_import_batch_id", required = false) String parentBatchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) throws IOException {
        TrackingFileService.TrackingUploadResult uploaded = service.upload(
                WriteCommands.parseIdentifier(exportId), file.getBytes(), file.getOriginalFilename(), importMode,
                parentBatchId == null ? null : WriteCommands.parseIdentifier(parentBatchId),
                WriteCommands.requireIdempotencyKey(idempotencyKey), WriteCommands.writeContext(operator));
        Map<String, Object> result = uploaded.body();
        // upload 返回即表示 Tracking 事实已提交；回填派生失败只留任务重试，不改写 201。
        try {
            sourceReturnDerivations.runDue(uploaded.sourceReturnTaskIds());
            long batchId = Long.parseLong(result.get("id").toString());
            result.put(
                    "generated_source_return_export_ids",
                    service.generatedSourceReturnIdsForTrackingBatch(batchId));
        } catch (RuntimeException exception) {
            log.warn(
                    "Source return derivation fast path deferred after tracking batch commit ({})",
                    exception.getClass().getSimpleName());
            log.debug("Tracking upload source return fast path failed", exception);
        }
        return ResponseEntity.status(201).body(result);
    }

    @GetMapping("/api/v1/tracking-imports/{batch_id}")
    Map<String, Object> get(@PathVariable("batch_id") String batchId) {
        return service.get(WriteCommands.parseIdentifier(batchId));
    }

    @GetMapping("/api/v1/import-batches/{batch_id}/source-return-exports")
    List<Map<String, Object>> sourceReturns(@PathVariable("batch_id") String batchId) {
        return service.listSourceReturns(WriteCommands.parseIdentifier(batchId));
    }

    @GetMapping("/api/v1/source-return-exports/{export_id}/file")
    ResponseEntity<byte[]> sourceReturn(
            @PathVariable("export_id") String exportId,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        ProviderFileService.FileDownload file = service.downloadSourceReturn(
                WriteCommands.parseIdentifier(exportId), WriteCommands.writeContext(operator));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    /**
     * 来源回填文件在线推送（票 11，人工触发；彩食鲜/聚福宝）。
     *
     * <p>幂等取舍（A1，契约 §3.2/§10.3）：推送会真实调用外部平台（不可重放），Idempotency-Key
     * 仅做格式校验（≥8 字符）防重复点击；重复推送由 DB 幂等闸门承担（push_status 状态机，
     * 见 {@link SourceReturnPushService}）。
     */
    @PostMapping("/api/v1/source-return-exports/{export_id}/push")
    Map<String, Object> push(
            @PathVariable("export_id") String exportId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        WriteCommands.requireIdempotencyKey(idempotencyKey);
        return pushService.push(
                WriteCommands.parseIdentifier(exportId), WriteCommands.writeContext(operator));
    }
}
