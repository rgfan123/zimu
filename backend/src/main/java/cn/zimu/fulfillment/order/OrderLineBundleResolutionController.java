package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.web.WriteCommands;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 礼包行就地解析入口（映射补配之后把待复核礼包行按档案 BOM 展开）。 */
@RestController
public class OrderLineBundleResolutionController {

    /** 目标礼包显式传入并与映射比对——不让服务端替人挑礼包。 */
    public record ResolveBundleCommand(
            @JsonProperty("bundle_id") @NotBlank @Pattern(regexp = "^[1-9][0-9]*$") String bundleId) {}

    private final OrderLineBundleResolutionService service;

    public OrderLineBundleResolutionController(OrderLineBundleResolutionService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/order-lines/{order_line_id}/resolve-bundle")
    public ResponseEntity<?> resolveBundle(
            @PathVariable("order_line_id") String orderLineId,
            @Valid @RequestBody ResolveBundleCommand command,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.resolveBundle(
                WriteCommands.parseIdentifier(orderLineId),
                WriteCommands.parseIdentifier(command.bundleId()),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
