package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
@RequestMapping("/api/v1/fulfillments")
@Validated
public class FulfillmentController {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final FulfillmentReadService service;
    private final ContinuationExportService continuationExports;

    public FulfillmentController(
            FulfillmentReadService service, ContinuationExportService continuationExports) {
        this.service = service;
        this.continuationExports = continuationExports;
    }

    @GetMapping
    public PageResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "provider_id", required = false) String providerId,
            @RequestParam(name = "shipping_progress", required = false) String progress,
            @RequestParam(required = false) String outcome) {
        return service.fulfillments(page, size, start(from), next(to),
                providerId == null ? null : WriteCommands.parseIdentifier(providerId), progress, outcome);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.fulfillment(WriteCommands.parseIdentifier(id));
    }

    @PostMapping("/{id}/continuation-exports")
    public ResponseEntity<?> continuationExport(
            @PathVariable String id,
            @Valid @RequestBody ContinuationExportCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(continuationExports.create(
                WriteCommands.parseIdentifier(id),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    public static Instant start(LocalDate value) { return value == null ? null : value.atStartOfDay(SHANGHAI).toInstant(); }
    public static Instant next(LocalDate value) { return value == null ? null : value.plusDays(1).atStartOfDay(SHANGHAI).toInstant(); }
}
