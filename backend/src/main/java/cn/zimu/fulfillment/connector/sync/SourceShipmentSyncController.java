package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 授权人工的 Shipment 来源回传入口；批量入口仍逐 Shipment 独立执行。 */
@RestController
@RequestMapping("/api/v1/shipments")
@Validated
public class SourceShipmentSyncController {

    private final SourceShipmentSyncService service;

    public SourceShipmentSyncController(SourceShipmentSyncService service) {
        this.service = service;
    }

    @GetMapping("/{id}/source-sync/check")
    public ResponseEntity<SourceSyncCheck> check(
            @PathVariable String id,
            @RequestHeader("X-Operator") String operator) {
        CommandContext context = WriteCommands.writeContext(operator);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.check(WriteCommands.parseIdentifier(id), context, AuditActorType.HUMAN));
    }

    @PostMapping("/{id}/source-sync/execute")
    public ResponseEntity<?> execute(
            @PathVariable String id,
            @Valid @RequestBody SourceSyncExecuteCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.execute(
                WriteCommands.parseIdentifier(id), body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    @PostMapping("/source-sync/batch-execute")
    public ResponseEntity<SourceSyncBatchOutcome> executeBatch(
            @Valid @RequestBody SourceSyncBatchExecuteCommand body,
            @RequestHeader("X-Operator") String operator) {
        return ResponseEntity.ok(service.executeBatch(body, WriteCommands.writeContext(operator)));
    }

    @PostMapping("/{id}/source-sync/reconcile")
    public ResponseEntity<?> reconcile(
            @PathVariable String id,
            @Valid @RequestBody SourceSyncReconcileCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.reconcile(
                WriteCommands.parseIdentifier(id), body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
