package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.web.WriteCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 京东出库单取消入口（issue #213 首切片）：仅白名单操作人可用，见服务层语义。 */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentJdOutboundCancelController {

    private final ShipmentJdOutboundCancelService service;

    public ShipmentJdOutboundCancelController(ShipmentJdOutboundCancelService service) {
        this.service = service;
    }

    @PostMapping("/{id}/jd-so-order-cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.cancel(
                WriteCommands.parseIdentifier(id),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
