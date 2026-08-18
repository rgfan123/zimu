package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.web.WriteCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shipment 级京东实时库存判定入口；只读京东库存，不预占也不创建出库单。 */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentJdStockCheckController {

    private final ShipmentJdStockCheckService service;

    public ShipmentJdStockCheckController(ShipmentJdStockCheckService service) {
        this.service = service;
    }

    @PostMapping("/{id}/jd-stock-check")
    public ResponseEntity<?> check(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.check(
                WriteCommands.parseIdentifier(id),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
