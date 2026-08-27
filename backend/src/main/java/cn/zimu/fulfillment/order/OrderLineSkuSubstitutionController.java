package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.web.WriteCommands;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单行「换货」入口：京东库存/映射阻断补救——把某订单行指向的 SKU 换成另一个已配置好、
 * 有货的 SKU，而不是干等着人工线下改配置或补库存。
 */
@RestController
public class OrderLineSkuSubstitutionController {

    /** 目标 SKU 显式传入并与履约方/映射逐项比对——不让服务端替人挑替代品。 */
    public record SubstituteSkuCommand(
            @JsonProperty("new_sku_id") @NotBlank @Pattern(regexp = "^[1-9][0-9]*$") String newSkuId,
            @JsonProperty("expected_order_version") @NotNull Long expectedOrderVersion) {}

    private final OrderLineSkuSubstitutionService service;

    public OrderLineSkuSubstitutionController(OrderLineSkuSubstitutionService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/order-lines/{order_line_id}/substitute-sku")
    public ResponseEntity<?> substituteSku(
            @PathVariable("order_line_id") String orderLineId,
            @Valid @RequestBody SubstituteSkuCommand command,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.substituteSku(
                WriteCommands.parseIdentifier(orderLineId),
                WriteCommands.parseIdentifier(command.newSkuId()),
                command.expectedOrderVersion(),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
