package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.order.dto.OperationalAlertDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/operational-alerts")
@Validated
public class OperationalAlertController {

    private final OperationalAlertService service;

    public OperationalAlertController(OperationalAlertService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<OperationalAlertDto> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) OperationalAlertStatus status,
            @RequestParam(required = false) OperationalAlertSeverity severity) {
        return service.list(page, size, status, severity);
    }

    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<?> acknowledge(
            @PathVariable String alertId,
            @Valid @RequestBody VersionedNoteCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.acknowledge(
                WriteCommands.parseIdentifier(alertId),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
