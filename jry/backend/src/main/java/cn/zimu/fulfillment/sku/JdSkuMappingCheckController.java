package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.web.WriteCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 京东云仓 SKU 映射核对的手动触发入口；核对结果按差异分类输出并写入运营告警或审计。 */
@RestController
@RequestMapping("/api/v1")
public class JdSkuMappingCheckController {

    private final JdSkuMappingCheckService globalCheck;
    private final ShipmentJdSkuMappingGateService shipmentGate;

    public JdSkuMappingCheckController(
            JdSkuMappingCheckService globalCheck,
            ShipmentJdSkuMappingGateService shipmentGate) {
        this.globalCheck = globalCheck;
        this.shipmentGate = shipmentGate;
    }

    /** 当前 Shipment 的京东商品映射门禁；只调用京东只读商品查询，不触发建单。 */
    @PostMapping("/shipments/{shipmentId}/jd-sku-mapping-check")
    public ResponseEntity<?> checkShipment(
            @PathVariable String shipmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(shipmentGate.check(
                WriteCommands.parseIdentifier(shipmentId),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    @PostMapping("/jd-sku-mapping/check")
    public ResponseEntity<?> check(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(globalCheck.run(
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
