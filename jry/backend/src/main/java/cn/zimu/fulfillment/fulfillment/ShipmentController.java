package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shipments")
@Validated
public class ShipmentController {

    private final FulfillmentReadService service;
    public ShipmentController(FulfillmentReadService service) { this.service = service; }

    @GetMapping
    public PageResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "provider_id", required = false) String providerId,
            @RequestParam(name = "shipment_status", required = false) String status) {
        return service.shipments(page, size, FulfillmentController.start(from), FulfillmentController.next(to),
                providerId == null ? null : WriteCommands.parseIdentifier(providerId), status);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return service.shipment(WriteCommands.parseIdentifier(id));
    }
}
