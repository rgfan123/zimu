package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已确认企业微信订单的显式 Shipment 路由入口；不直接调用任何外部履约方。 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderFulfillmentRoutingController {

    private final OrderFulfillmentRoutingService service;

    OrderFulfillmentRoutingController(OrderFulfillmentRoutingService service) {
        this.service = service;
    }

    @PostMapping("/{order_id}/fulfillment-routing")
    ResponseEntity<?> route(
            @PathVariable("order_id") String orderId,
            @Valid @RequestBody OrderFulfillmentRoutingCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.route(
                WriteCommands.parseIdentifier(orderId),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
