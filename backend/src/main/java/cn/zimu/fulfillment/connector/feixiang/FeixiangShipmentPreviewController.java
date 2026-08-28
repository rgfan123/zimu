package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.sync.SourceSyncBlocker;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.connector.sync.SourceSyncFactsReader;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞象在线回传的 dry-run 预览入口（<b>只读</b>）。
 *
 * <p>「先有 dry-run、人可以先核对请求体」这条要求的落点。它做三件事：读内部事实、
 * 只读拉取平台当前详情、把<b>将要发出的完整 form 报文</b>原样交给操作员。
 * 它<b>不</b>写 shipment_syncs、<b>不</b>登记幂等、<b>不</b>发任何写请求，因此可以
 * 在写门闩仍是 OFF / DRY_RUN（即 onlinePush 仍为假、企微人工上传路径原样在跑）时反复执行。
 *
 * <p>之所以要单独一个入口而不是复用 {@code /source-sync/check}：check 的返回契约里没有
 * 报文这一项，而演练必须让人看到<b>逐字节</b>的那一串——它与真写发出的字符串来自
 * {@link FeixiangShipmentPlanner} 的同一次构造。
 *
 * <p>返回体不含收货人任何字段：报文本身只有商品行 ID、运单号与承运商代码。</p>
 */
@RestController
@RequestMapping("/api/v1/shipments")
public class FeixiangShipmentPreviewController {

    private final SourceSyncFactsReader facts;
    private final FeixiangConnector connector;
    private final FeixiangShipmentWriteGate writeGate;

    public FeixiangShipmentPreviewController(
            SourceSyncFactsReader facts, FeixiangConnector connector, FeixiangShipmentWriteGate writeGate) {
        this.facts = facts;
        this.connector = connector;
        this.writeGate = writeGate;
    }

    @GetMapping("/{id}/source-sync/feixiang-preview")
    public ResponseEntity<Map<String, Object>> preview(
            @PathVariable String id, @RequestHeader("X-Operator") String operator) {
        CommandContext context = WriteCommands.writeContext(operator);
        requireAuthenticatedOperator(context);
        long shipmentId = WriteCommands.parseIdentifier(id);
        SourceSyncFactsReader.Loaded loaded = facts.load(shipmentId);
        if (loaded.facts().sourceChannel() != SourceChannel.FEIXIANG) {
            throw BusinessException.badRequest(
                    "FEIXIANG_CHANNEL_MISMATCH", "该 Shipment 不属于飞象渠道");
        }
        FeixiangShipmentPlanner.WritePlan plan =
                connector.previewShipmentWrite(shipmentResult(loaded.facts()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body(loaded, plan));
    }

    private Map<String, Object> body(
            SourceSyncFactsReader.Loaded loaded, FeixiangShipmentPlanner.WritePlan plan) {
        FeixiangShipmentRequest request = plan.request();
        return Map.of(
                "write_mode", writeGate.mode().name(),
                "would_emit_http", writeGate.inspectExternalWrite().allowed(),
                "local_blockers", loaded.blockers().stream().map(SourceSyncBlocker::code).toList(),
                "platform_available", plan.available(),
                "platform_state", nullToEmpty(plan.platformState()),
                "business_code", nullToEmpty(plan.businessCode()),
                "message", nullToEmpty(plan.message()),
                "order_son_id", nullToEmpty(plan.orderSonId()),
                "express_code", nullToEmpty(plan.expressCode()),
                "request", request == null
                        ? Map.<String, Object>of("buildable", false)
                        : Map.of(
                                "buildable", true,
                                "path", "/order/ajaxSendOrderProduct",
                                "content_type", "application/x-www-form-urlencoded",
                                "order_product_ids", request.orderProductIds(),
                                "sn", request.trackingNumber(),
                                "express_code", request.expressCode(),
                                "form_body", request.formBody(),
                                "effect_hash", nullToEmpty(plan.effectHash())));
    }

    private static SourceShipmentResult shipmentResult(SourceSyncFacts facts) {
        return new SourceShipmentResult(
                facts.sourceChannel(), facts.sourceRef(), facts.sourceLineRef(),
                facts.internalShippedQuantity(), facts.shippedSourceQuantity(), "SHIPPED",
                facts.carrierOutputValue(), facts.trackingNumber(), null,
                facts.receiverName(), facts.receiverPhone(), facts.receiverAddress(),
                facts.shipmentId(), SourceShipmentArtifact.empty());
    }

    /** 与来源回传执行入口同一道身份门闩：必须是服务端已认证且身份一致的人工操作员。 */
    private static void requireAuthenticatedOperator(CommandContext context) {
        if (context != null
                && context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())) {
            return;
        }
        throw BusinessException.forbidden(
                "SOURCE_SYNC_OPERATOR_UNAUTHORIZED", "飞象回传报文预览必须由服务端已认证的人工操作员执行");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
