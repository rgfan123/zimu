package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.web.WriteCommands;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运营人员按 Shipment 手动触发京东运单回填；无请求体，只使用已落盘商户引用。 */
@RestController
@RequestMapping("/api/v1/shipments")
@Validated
public class ShipmentJdTrackingBackfillController {

    private final ShipmentJdTrackingBackfillService service;

    public ShipmentJdTrackingBackfillController(ShipmentJdTrackingBackfillService service) {
        this.service = service;
    }

    @PostMapping("/{id}/jd-tracking-backfill")
    public ResponseEntity<?> backfill(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.backfill(
                WriteCommands.parseIdentifier(id),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
