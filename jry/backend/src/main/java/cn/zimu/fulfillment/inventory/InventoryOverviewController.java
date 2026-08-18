package cn.zimu.fulfillment.inventory;

import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory/overview")
@Validated
public class InventoryOverviewController {

    private final InventoryOverviewService service;

    public InventoryOverviewController(InventoryOverviewService service) {
        this.service = service;
    }

    @GetMapping
    public InventoryOverviewResponse overview(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "provider_id", required = false) String providerId,
            @RequestParam(name = "sku_id", required = false) String skuId,
            @RequestParam(name = "warehouse_code", required = false)
                    @Pattern(regexp = "[^\\s]{1,128}") String warehouseCode) {
        return service.overview(
                page,
                size,
                providerId == null ? null : WriteCommands.parseIdentifier(providerId),
                skuId == null ? null : WriteCommands.parseIdentifier(skuId),
                warehouseCode);
    }
}
