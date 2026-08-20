package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 来源归因纠正公共写入口。 */
@RestController
class SourceAttributionController {

    private final SourceAttributionService service;

    SourceAttributionController(SourceAttributionService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/import-batches/{batch_id}/source-attribution-corrections")
    ResponseEntity<?> correct(
            @PathVariable("batch_id") String batchId,
            @Valid @RequestBody SourceAttributionCorrectionWrite body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        return WriteCommands.respond(service.correct(
                WriteCommands.parseIdentifier(batchId),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
