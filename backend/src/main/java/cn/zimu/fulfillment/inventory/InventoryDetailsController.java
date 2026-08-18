package cn.zimu.fulfillment.inventory;

import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/details")
@Validated
public class InventoryDetailsController {

    private final InventoryDetailsService service;

    public InventoryDetailsController(InventoryDetailsService service) {
        this.service = service;
    }

    @GetMapping
    public InventoryDetailsResponse details(
            @RequestParam(name = "provider_id") String providerId,
            @RequestParam(name = "sku_id") String skuId,
            @RequestParam(name = "warehouse_code", required = false)
                    @Pattern(regexp = "[^\\s]{1,128}") String warehouseCode) {
        return service.details(
                WriteCommands.parseIdentifier(providerId),
                WriteCommands.parseIdentifier(skuId),
                warehouseCode);
    }
}
