package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProviderFileController {

    private final ProviderFileService service;
    private final FulfillmentExportWecomService wecomService;

    ProviderFileController(ProviderFileService service, FulfillmentExportWecomService wecomService) {
        this.service = service;
        this.wecomService = wecomService;
    }

    @GetMapping("/api/v1/fulfillment-exports")
    PageResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(value = "provider_id", required = false) String providerId,
            @RequestParam(value = "usage_status", required = false) String usageStatus) {
        return service.list(page, size,
                providerId == null ? null : WriteCommands.parseIdentifier(providerId), usageStatus);
    }

    @GetMapping("/api/v1/fulfillment-exports/{export_id}")
    Map<String, Object> detail(@PathVariable("export_id") String exportId) {
        return service.detail(WriteCommands.parseIdentifier(exportId));
    }

    @GetMapping("/api/v1/fulfillment-exports/{export_id}/file")
    ResponseEntity<byte[]> download(
            @PathVariable("export_id") String exportId,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        ProviderFileService.FileDownload file = service.download(
                WriteCommands.parseIdentifier(exportId), WriteCommands.writeContext(operator));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    /** 人工停止企微自动发送与周期提醒（#84）：认证/幂等/版本/理由；已收齐或已停止幂等 no-op。 */
    @PostMapping("/api/v1/fulfillment-exports/{export_id}/wecom-stop")
    ResponseEntity<?> wecomStop(
            @PathVariable("export_id") String exportId,
            @Valid @RequestBody WecomExportStopCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return WriteCommands.respond(wecomService.stop(
                WriteCommands.parseIdentifier(exportId),
                command,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    /** 人工重发企微文件消息（#84）：只生成新 initial delivery + 任务，HTTP 线程不直接发送。 */
    @PostMapping("/api/v1/fulfillment-exports/{export_id}/wecom-resend")
    ResponseEntity<?> wecomResend(
            @PathVariable("export_id") String exportId,
            @Valid @RequestBody WecomExportResendCommand command,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return WriteCommands.respond(wecomService.resend(
                WriteCommands.parseIdentifier(exportId),
                command,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
