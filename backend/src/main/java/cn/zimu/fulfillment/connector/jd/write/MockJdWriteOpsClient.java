package cn.zimu.fulfillment.connector.jd.write;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地客户端。写操作风险高（采购/退货/销毁等不可逆），
 * 因此 Mock 模式同样审计每次 seam 调用（与 {@link MockJdWriteOpsClient} 之外
 * 的既有 Mock 客户端不同——既有 Mock 不审计，本写 seam 按「审计所有调用」要求补齐）。
 *
 * <p>写模式门闩与真实客户端一致：{@code app.jd.write-mode} 非 ON 时拒绝一切写调用。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdWriteOpsClient implements JdWriteOpsService {

    private final AuditLogService auditLogService;
    private final String writeMode;

    public MockJdWriteOpsClient(
            AuditLogService auditLogService, @Value("${app.jd.write-mode:OFF}") String writeMode) {
        this.auditLogService = auditLogService;
        this.writeMode = writeMode;
    }

    @Override
    public JdResult customerCreate(Map<String, Object> request) {
        return success("customerCreate", request, Map.of("customerNo", "MOCK-CUSTOMER-001"));
    }

    @Override
    public JdResult goodsCreate(Map<String, Object> request) {
        return success("goodsCreate", request, Map.of("goodsNo", "MOCK-GOODS-001"));
    }

    @Override
    public JdResult goodsUpdateBySellerGoodsSign(Map<String, Object> request) {
        return success("goodsUpdateBySellerGoodsSign", request, Map.of("goodsNo", "MOCK-GOODS-001", "status", "UPDATED"));
    }

    @Override
    public JdResult supplierCreate(Map<String, Object> request) {
        return success("supplierCreate", request, Map.of("supplierNo", "MOCK-SUPPLIER-001"));
    }

    @Override
    public JdResult shopCreate(Map<String, Object> request) {
        return success("shopCreate", request, Map.of("shopNo", "MOCK-SHOP-001"));
    }

    @Override
    public JdResult shopGoodsCreate(Map<String, Object> request) {
        return success("shopGoodsCreate", request, Map.of("shopGoodsNo", "MOCK-SHOP-GOODS-001"));
    }

    @Override
    public JdResult serialnumberCreate(Map<String, Object> request) {
        return success("serialnumberCreate", request, Map.of("serialRuleNo", "MOCK-SERIAL-RULE-001"));
    }

    @Override
    public JdResult processedCreate(Map<String, Object> request) {
        return success("processedCreate", request, Map.of("formulaNo", "MOCK-FORMULA-001"));
    }

    @Override
    public JdResult logicalinventoryfactorCreate(Map<String, Object> request) {
        return success("logicalinventoryfactorCreate", request, Map.of("logicalStockConfigNo", "MOCK-LSC-001"));
    }

    @Override
    public JdResult boxandserialnumberTransport(Map<String, Object> request) {
        return success("boxandserialnumberTransport", request, Map.of("transportNo", "MOCK-TRANSPORT-001", "status", "TRANSPORTED"));
    }

    @Override
    public JdResult orderAdjustmentCreate(Map<String, Object> request) {
        return success("orderAdjustmentCreate", request, Map.of("adjustmentNo", "MOCK-ADJ-001", "status", "CREATED"));
    }

    @Override
    public JdResult orderDestroyCreate(Map<String, Object> request) {
        return success("orderDestroyCreate", request, Map.of("destroyNo", "MOCK-UL-001", "status", "CREATED"));
    }

    @Override
    public JdResult orderOperateCommandModify(Map<String, Object> request) {
        return success("orderOperateCommandModify", request, Map.of("commandNo", "MOCK-CMD-001", "status", "MODIFIED"));
    }

    @Override
    public JdResult orderProcessedCreate(Map<String, Object> request) {
        return success("orderProcessedCreate", request, Map.of("processOrderNo", "MOCK-PROCESS-001", "status", "CREATED"));
    }

    @Override
    public JdResult orderPurchaseCreate(Map<String, Object> request) {
        return success("orderPurchaseCreate", request, Map.of("purchaseNo", "MOCK-PO-001", "status", "CREATED"));
    }

    @Override
    public JdResult orderPurchaseClose(Map<String, Object> request) {
        return success("orderPurchaseClose", request, Map.of("purchaseNo", "MOCK-PO-001", "status", "CLOSED"));
    }

    @Override
    public JdResult orderReturntosupplierCreate(Map<String, Object> request) {
        return success("orderReturntosupplierCreate", request, Map.of("rtsOrderNo", "MOCK-RTS-001", "status", "CREATED"));
    }

    @Override
    public JdResult orderReturntowarehouseCreate(Map<String, Object> request) {
        return success("orderReturntowarehouseCreate", request, Map.of("rtwOrderNo", "MOCK-RTW-001", "status", "CREATED"));
    }

    @Override
    public JdResult orderSoCreate(Map<String, Object> request) {
        return success("orderSoCreate", request, Map.of(
                "deliveryNo", "MOCK-DELIVERY-001",
                "erpDeliveryNo", value(request, "erpDeliveryNo", "MOCK-ERP-DELIVERY-001"),
                "status", "CREATED"));
    }

    @Override
    public JdResult stockShopstockfixedSet(Map<String, Object> request) {
        return success("stockShopstockfixedSet", request, Map.of("shopNo", "MOCK-SHOP-001", "fixedQuantity", 100));
    }

    private JdResult success(String operation, Map<String, Object> request, Object data) {
        Instant startedAt = Instant.now();
        JdResult result;
        if (!writeEnabled()) {
            result = new JdResult(false, "WRITE_MODE_DISABLED", "写模式未启用", null, null);
        } else {
            Map<String, Object> stableData = new LinkedHashMap<>();
            stableData.put("operation", operation);
            stableData.put("request", request == null ? Map.of() : Map.copyOf(request));
            stableData.put("response", data);
            result = new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-" + operation, stableData);
        }
        audit(operation, request, result, startedAt);
        return result;
    }

    private boolean writeEnabled() {
        return "ON".equalsIgnoreCase(writeMode == null ? "" : writeMode.trim());
    }

    private Object value(Map<String, Object> request, String key, Object fallback) {
        return request == null ? fallback : request.getOrDefault(key, fallback);
    }

    private void audit(String operation, Map<String, Object> request, JdResult result, Instant startedAt) {
        RequestContext context = RequestContext.current();
        auditLogService.record(new AuditLogService.AuditCommand()
                .requestId(context == null ? result.requestId() : context.getRequestId())
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getOperator() == null ? "jd-client" : context.getOperator())
                .actorType(AuditActorType.SYSTEM)
                .service("jd.isc")
                .operation(operation)
                .requestPayload(request == null ? Map.of() : request)
                .responsePayload(result)
                .httpStatus(result.success() ? 200 : 502)
                .businessCode(result.businessCode())
                .latencyMs((int) Duration.between(startedAt, Instant.now()).toMillis()));
    }
}
