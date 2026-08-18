package cn.zimu.fulfillment.procurement;

import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/procurement/tickets")
public class InternalProcurementController {

    private final ProcurementService service;
    public InternalProcurementController(ProcurementService service) { this.service = service; }

    @PostMapping("/{id}/receipts")
    public ResponseEntity<?> receipt(
            @PathVariable String id,
            @Valid @RequestBody ProcurementReceiptInput body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.receipt(WriteCommands.parseIdentifier(id), body,
                WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
}
