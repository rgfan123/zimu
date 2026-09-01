package cn.zimu.fulfillment.connector.jd.write;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.JdErpDeliveryNoNamespace;
import cn.zimu.fulfillment.fulfillment.JdSalesOutboundWriter;
import cn.zimu.fulfillment.fulfillment.PreparedJdSalesOutbound;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoBoxandserialnumberTransportV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsUpdateBySellerGoodsSignV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoLogicalinventoryfactorCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoProcessedCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSerialnumberCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopGoodsCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderAdjustmentCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDestroyCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderOperateCommandModifyV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderProcessedCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseCloseV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseCreateV2LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntosupplierCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShopstockfixedSetV1LopRequest;
import com.lop.open.api.sdk.response.AbstractResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 京东 ISC 写接口真实客户端。领域 DTO 仅存在于这一防腐层；
 * 写模式门闩在本客户端内部（构造时注入），HTTP 层门闩为双保险。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdWriteOpsClient implements JdWriteOpsService, JdSalesOutboundWriter {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;
    private final String writeMode;

    public JdWriteOpsClient(
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            @Value("${app.jd.server-url:}") String serverUrl,
            @Value("${app.jd.app-key:}") String appKey,
            @Value("${app.jd.app-secret:}") String appSecret,
            @Value("${app.jd.access-token:}") String accessToken,
            @Value("${app.jd.pin:}") String pin,
            @Value("${app.jd.owner-no:}") String ownerNo,
            @Value("${app.jd.write-mode:OFF}") String writeMode) {
        this.sdkMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        this.auditLogService = auditLogService;
        this.serverUrl = serverUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.accessToken = accessToken;
        this.pin = pin;
        this.ownerNo = ownerNo;
        this.writeMode = writeMode;
    }

    @Override
    public JdResult customerCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoCustomerCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                        .addOrUpdateCustomerInfo.CustomerInfoAddOrUpdateRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                        .addOrUpdateCustomerInfo.CustomerInfoAddOrUpdateRequest.class));
        return execute("customerCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult goodsCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .saveGoodsInfo.GoodsInfoSaveRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .saveGoodsInfo.GoodsInfoSaveRequest.class));
        return execute("goodsCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult goodsUpdateBySellerGoodsSign(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsUpdateBySellerGoodsSignV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .updateGoodsInfoBySellerGoodsSign.GoodsInfoSaveRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .updateGoodsInfoBySellerGoodsSign.GoodsInfoSaveRequest.class));
        return execute("goodsUpdateBySellerGoodsSign", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult supplierCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSupplierCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService
                        .upsert.SupplierModelRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService
                        .upsert.SupplierModelRequest.class));
        return execute("supplierCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult shopCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformShopService
                        .saveShopInfo.ShopInfoSaveRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformShopService
                        .saveShopInfo.ShopInfoSaveRequest.class));
        return execute("shopCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult shopGoodsCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopGoodsCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .saveShopGoodsInfo.ShopGoodsInfoSaveRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .saveShopGoodsInfo.ShopGoodsInfoSaveRequest.class));
        return execute("shopGoodsCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult serialnumberCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSerialnumberCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                        .transportGoodsSerialNumberRule.GoodsSerialAddRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                        .transportGoodsSerialNumberRule.GoodsSerialAddRequest.class));
        return execute("serialnumberCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult processedCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoProcessedCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .addGoodsFormula.GoodsFormulaSaveRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .addGoodsFormula.GoodsFormulaSaveRequest.class));
        return execute("processedCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult logicalinventoryfactorCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoLogicalinventoryfactorCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                        .insertLogicalStockConfig.CustomerLogicalStockConfigRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService
                        .insertLogicalStockConfig.CustomerLogicalStockConfigRequest.class));
        return execute("logicalinventoryfactorCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult boxandserialnumberTransport(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoBoxandserialnumberTransportV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                        .transportBoxAndSerialInfo.BoxAndSerialInfoTransportRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService
                        .transportBoxAndSerialInfo.BoxAndSerialInfoTransportRequest.class));
        return execute("boxandserialnumberTransport", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderAdjustmentCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderAdjustmentCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService
                        .transportInsideOrder.AdjustmentMainRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService
                        .transportInsideOrder.AdjustmentMainRequest.class));
        return execute("orderAdjustmentCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderDestroyCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDestroyCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService
                        .addUlOrder.UlOrderCreateRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService
                        .addUlOrder.UlOrderCreateRequest.class));
        return execute("orderDestroyCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderOperateCommandModify(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderOperateCommandModifyV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                        .updateDeliveryCommand.DeliveryCommandUpdateRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                        .updateDeliveryCommand.DeliveryCommandUpdateRequest.class));
        return execute("orderOperateCommandModify", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderProcessedCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderProcessedCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService
                        .addProcessOrder.ProcessOrderCreateRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService
                        .addProcessOrder.ProcessOrderCreateRequest.class));
        return execute("orderProcessedCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderPurchaseCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderPurchaseCreateV2LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService
                        .addPoOrder.PoCreateRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService
                        .addPoOrder.PoCreateRequest.class));
        return execute("orderPurchaseCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderPurchaseClose(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderPurchaseCloseV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService
                        .closePoOrder.PoCloseRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService
                        .closePoOrder.PoCloseRequest.class));
        return execute("orderPurchaseClose", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderReturntosupplierCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntosupplierCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService
                        .addRtsOrder.RtsAddRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService
                        .addRtsOrder.RtsAddRequest.class));
        return execute("orderReturntosupplierCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderReturntowarehouseCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntowarehouseCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .addRtwOrder.RtwCreateRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .addRtwOrder.RtwCreateRequest.class));
        return execute("orderReturntowarehouseCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult create(PreparedJdSalesOutbound outbound) {
        Map<String, Object> command = outbound.request();
        if (writeEnabled() && !JdErpDeliveryNoNamespace.owns(command)) {
            JdResult rejected = new JdResult(
                    false,
                    JdErpDeliveryNoNamespace.BUSINESS_CODE,
                    JdErpDeliveryNoNamespace.MESSAGE,
                    null,
                    null);
            audit("orderSoCreate", command, rejected, Instant.now());
            return rejected;
        }
        var request = new IntegratedsupplychainOrderDeliveryCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                        .addSoOrder.SoCreateOrderRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                        .addSoOrder.SoCreateOrderRequest.class));
        return execute("orderSoCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult stockShopstockfixedSet(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockShopstockfixedSetV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                        .setShopStockFixed.ShopStockRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                        .setShopStockFixed.ShopStockRequest.class));
        return execute("stockShopstockfixedSet", command, request, response -> response.getResponse());
    }

    private <T extends AbstractResponse> JdResult execute(
            String operation,
            Map<String, Object> command,
            DomainAbstractRequest<T> request,
            Function<T, Object> envelopeExtractor) {
        Instant startedAt = Instant.now();
        JdResult result;
        if (!writeEnabled()) {
            result = new JdResult(false, "WRITE_MODE_DISABLED", "写模式未启用", null, null);
        } else if (!configured()) {
            result = new JdResult(false, "CREDENTIALS_REQUIRED",
                    "JD REAL client requires server-url, app-key, app-secret and access-token",
                    null, null);
        } else {
            try {
                T response = new JdlClient(serverUrl, appKey, appSecret, accessToken).execute(request);
                result = normalize(response, envelopeExtractor.apply(response));
            } catch (Exception exception) {
                result = new JdResult(false, "SDK_CALL_FAILED", safeMessage(exception), null, null);
            }
        }
        audit(operation, command, result, startedAt);
        return result;
    }

    private boolean writeEnabled() {
        return "ON".equalsIgnoreCase(writeMode == null ? "" : writeMode.trim());
    }

    private JdResult normalize(AbstractResponse outer, Object envelope) {
        Map<String, Object> values = envelope == null
                ? Map.of()
                : sdkMapper.convertValue(envelope, new TypeReference<>() {});
        String outerCode = text(outer.getCode());
        String innerCode = text(values.get("code"));
        boolean success = SUCCESS_CODES.contains(outerCode) && SUCCESS_CODES.contains(innerCode);
        String businessCode = innerCode == null ? outerCode : innerCode;
        String message = text(values.get("message"));
        if (message == null) {
            message = outer.getMsg();
        }
        return new JdResult(
                success,
                businessCode == null ? "EMPTY_RESPONSE_CODE" : businessCode,
                message,
                text(values.get("requestId")),
                values.get("data"));
    }

    private Map<String, Object> withDefaults(Map<String, Object> command, Class<?> dtoType) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (command != null) {
            request.putAll(command);
        }
        // 部分 DTO 没有 ownerNo 字段（如 SoCreateOrderRequest、AdjustmentMainRequest）或没有 pin 字段
        // （如 BoxAndSerialInfoTransportRequest）；只注入目标 DTO 真实支持的默认值，
        // 避免严格 mapper 下未知属性转换失败。
        if (pin != null && !pin.isBlank() && supports(dtoType, "pin")) {
            request.putIfAbsent("pin", pin);
        }
        if (ownerNo != null && !ownerNo.isBlank() && supports(dtoType, "ownerNo")) {
            request.putIfAbsent("ownerNo", ownerNo);
        }
        return request;
    }

    private boolean supports(Class<?> dtoType, String property) {
        String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Arrays.stream(dtoType.getMethods()).anyMatch(method -> method.getName().equals(setter));
    }

    private void audit(String operation, Map<String, Object> command, JdResult result, Instant startedAt) {
        RequestContext context = RequestContext.current();
        auditLogService.record(new AuditLogService.AuditCommand()
                .requestId(context == null ? result.requestId() : context.getRequestId())
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getOperator() == null ? "jd-client" : context.getOperator())
                .actorType(AuditActorType.SYSTEM)
                .service("jd.isc")
                .operation(operation)
                .requestPayload(command == null ? Map.of() : command)
                .responsePayload(result)
                .httpStatus(result.success() ? 200 : 502)
                .businessCode(result.businessCode())
                .latencyMs((int) Duration.between(startedAt, Instant.now()).toMillis()));
    }

    private boolean configured() {
        return !serverUrl.isBlank() && !appKey.isBlank() && !appSecret.isBlank() && !accessToken.isBlank();
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private String safeMessage(Exception exception) {
        return "京东服务暂时不可用，请稍后重试";
    }
}
