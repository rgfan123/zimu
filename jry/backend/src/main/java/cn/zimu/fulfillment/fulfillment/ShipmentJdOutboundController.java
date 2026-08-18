package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东云仓建出库单入口（Shipment 边界）：业务确认发货时对一个已分组的发货批次调 addSoOrder，
 * 同批次全部 ShipmentItems 聚合为一张京东出库单。与既有写命令一致：
 * Idempotency-Key + X-Operator 必填；提交时还要求后端复验通过的网关登录主体，
 * 幂等重放返回首次结果。
 */
@RestController
@RequestMapping("/api/v1/shipments")
@Validated
public class ShipmentJdOutboundController {

    private final ShipmentJdOutboundService service;

    public ShipmentJdOutboundController(ShipmentJdOutboundService service) {
        this.service = service;
    }

    /**
     * 查看当前 Shipment 将发送给京东的完整请求与逐字段来源/校验结果。
     * 该入口只读业务事实并写审计，绝不调用 JD 写客户端。
     */
    @GetMapping("/{id}/jd-so-order-preview")
    public Map<String, Object> preview(
            @PathVariable String id,
            @RequestHeader("X-Operator") String operator) {
        return service.preview(
                WriteCommands.parseIdentifier(id),
                WriteCommands.writeContext(operator));
    }

    /** 人工确认京东建单所需的结构化地址；自由文本地址不在此自动解析。 */
    @PutMapping("/{id}/jd-receiver-address")
    public ResponseEntity<?> confirmReceiverAddress(
            @PathVariable String id,
            @Valid @RequestBody ShipmentJdReceiverAddressCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.confirmReceiverAddress(
                WriteCommands.parseIdentifier(id),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    /** 批量确认多个发货批次的结构化收货地址；逐条与单条入口同一用例。 */
    @PostMapping("/jd-receiver-address-batch")
    public ResponseEntity<?> confirmReceiverAddresses(
            @Valid @RequestBody ShipmentJdReceiverAddressBatchCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.confirmReceiverAddresses(
                body.items(),
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }

    /** 只读返回京东结构化地址候选；候选必须经人工确认后才参与建单。 */
    @GetMapping("/jd-receiver-address-candidates")
    public java.util.List<Map<String, Object>> receiverAddressCandidates(
            @RequestParam(name = "import_batch_id", required = false) Long importBatchId,
            @RequestParam(name = "only_missing", defaultValue = "true") boolean onlyMissing) {
        return service.receiverAddressCandidates(importBatchId, onlyMissing);
    }

    @PostMapping("/{id}/jd-so-order")
    public ResponseEntity<?> submit(
            @PathVariable String id,
            @Valid @RequestBody(required = false) ShipmentJdOutboundCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.submit(
                WriteCommands.parseIdentifier(id),
                body == null ? new ShipmentJdOutboundCommand() : body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                WriteCommands.writeContext(operator)));
    }
}
