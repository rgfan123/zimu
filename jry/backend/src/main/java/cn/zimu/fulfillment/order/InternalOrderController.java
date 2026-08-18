package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.OrderRevisionInput;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 受信任内部 API：BUSINESS / WECOM 订单创建入口。 */
@RestController
@RequestMapping("/internal/v1/orders")
@Validated
public class InternalOrderController {

    private final OrderCreateService orderCreateService;

    public InternalOrderController(OrderCreateService orderCreateService) {
        this.orderCreateService = orderCreateService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody CanonicalOrderInput input) {
        CommandContext context = WriteCommands.writeContext(operator);
        return WriteCommands.respond(
                orderCreateService.create(input, WriteCommands.requireIdempotencyKey(idempotencyKey), context));
    }

    @PostMapping("/{order_id}/revisions")
    public ResponseEntity<?> revise(
            @PathVariable("order_id") String orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody OrderRevisionInput input) {
        return WriteCommands.respond(orderCreateService.revise(
                WriteCommands.parseIdentifier(orderId), input,
                WriteCommands.requireIdempotencyKey(idempotencyKey), WriteCommands.writeContext(operator)));
    }
}
