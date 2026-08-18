package cn.zimu.fulfillment.procurement;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.FulfillmentController;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procurement-tickets")
@Validated
public class ProcurementController {

    private final FulfillmentReadService reads;
    private final ProcurementService service;

    public ProcurementController(FulfillmentReadService reads, ProcurementService service) {
        this.reads = reads;
        this.service = service;
    }

    @GetMapping
    public PageResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status) {
        return reads.tickets(page, size, FulfillmentController.start(from), FulfillmentController.next(to), status);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return reads.ticket(WriteCommands.parseIdentifier(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(
            @PathVariable String id,
            @Valid @RequestBody VersionedNoteCommand body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.retry(WriteCommands.parseIdentifier(id), body,
                WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @PostMapping("/{id}/cancel-remaining")
    public ResponseEntity<?> cancel(
            @PathVariable String id,
            @Valid @RequestBody CancelRemainingCommand body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.cancel(WriteCommands.parseIdentifier(id), body,
                WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
}
