package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class SourceImportController {

    private final SourceImportService service;

    SourceImportController(SourceImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/import-batches/source-orders", consumes = "multipart/form-data")
    ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("import_mode") String importMode,
            @RequestParam(value = "parent_import_batch_id", required = false) String parentBatchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) throws IOException {
        String key = WriteCommands.requireIdempotencyKey(idempotencyKey);
        Long parent = parentBatchId == null ? null : WriteCommands.parseIdentifier(parentBatchId);
        return ResponseEntity.status(201).body(service.upload(
                file.getBytes(), file.getOriginalFilename(), importMode, parent, key, WriteCommands.writeContext(operator)));
    }

    @GetMapping("/api/v1/import-batches/{batch_id}")
    Map<String, Object> get(@PathVariable("batch_id") String batchId) {
        return service.get(WriteCommands.parseIdentifier(batchId));
    }

    @PostMapping("/api/v1/import-batches/{batch_id}/confirm")
    ResponseEntity<?> confirm(
            @PathVariable("batch_id") String batchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        long parsedBatchId = WriteCommands.parseIdentifier(batchId);
        CommandContext context = WriteCommands.writeContext(operator);
        IdempotentResult<Map<String, Object>> confirmed = service.confirm(
                parsedBatchId, WriteCommands.requireIdempotencyKey(idempotencyKey), context);
        // jd-real-sdk-switch 05：仅在首次执行时（确认事务已提交）对 SDK 路由的京东发货批次自动触发建单；
        // 幂等重放返回首次结果，不再发起新的外部调用。单个 shipment 的 submit 幂等键稳定，
        // 失败留痕（SYNC_FAILED/告警/复核）不阻断批次确认。
        if (!confirmed.replayed()
                && confirmed.result().get("outbound_routing") instanceof Map<?, ?> route
                && route.get("jd_sdk_shipment_ids") instanceof List<?> shipmentIds
                && !shipmentIds.isEmpty()) {
            service.submitJdOutboundsForBatch(parsedBatchId, context);
        }
        return WriteCommands.respond(confirmed);
    }

    /** 对批次内京东发货批次批量触发 SDK 建单；已提交的跳过，失败项可安全重试（幂等键稳定）。 */
    @PostMapping("/api/v1/import-batches/{batch_id}/jd-outbound-submit")
    Map<String, Object> submitJdOutbounds(
            @PathVariable("batch_id") String batchId,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return service.submitJdOutboundsForBatch(
                WriteCommands.parseIdentifier(batchId),
                WriteCommands.writeContext(operator));
    }

    @GetMapping("/api/v1/import-batches/{batch_id}/rows")
    PageResponse<Map<String, Object>> rows(
            @PathVariable("batch_id") String batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return service.rows(WriteCommands.parseIdentifier(batchId), page, size, status);
    }
}
