package cn.zimu.fulfillment.connector.jd;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoOwnerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderCancelV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderTraceQueryV2LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockQueryV1LopRequest;
import com.lop.open.api.sdk.response.AbstractResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC LOP 客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdWarehouseClient implements JDWarehouseService {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private static final String PIN = "pin";
    private static final String OWNER_NO = "ownerNo";

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdWarehouseClient(
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            @Value("${app.jd.server-url:}") String serverUrl,
            @Value("${app.jd.app-key:}") String appKey,
            @Value("${app.jd.app-secret:}") String appSecret,
            @Value("${app.jd.access-token:}") String accessToken,
            @Value("${app.jd.pin:}") String pin,
            @Value("${app.jd.owner-no:}") String ownerNo) {
        this.sdkMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        this.auditLogService = auditLogService;
        this.serverUrl = serverUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.accessToken = accessToken;
        this.pin = pin;
        this.ownerNo = ownerNo;
    }

    @Override
    public JdResult queryOwners(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoOwnerQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService
                                .queryOwnerInfo.OwnerQueryRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService
                        .queryOwnerInfo.OwnerQueryRequest.class));
        return execute("queryOwners", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryWarehouses(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService
                                .queryWarehouseInfo.WarehouseQueryRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService
                        .queryWarehouseInfo.WarehouseQueryRequest.class));
        return execute("queryWarehouses", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryProducts(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                                .queryGoodsInfo.GoodsInfoQueryRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService
                        .queryGoodsInfo.GoodsInfoQueryRequest.class));
        return execute("queryProducts", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryStock(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                                .queryStock.StockQueryRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService
                        .queryStock.StockQueryRequest.class));
        return execute("queryStock", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult createOutboundOrder(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliveryCreateV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                                .addSoOrder.SoCreateOrderRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                        .addSoOrder.SoCreateOrderRequest.class));
        return execute("createOutboundOrder", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryOutboundOrder(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliveryQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                                .querySoOrder.SoQueryRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService
                        .querySoOrder.SoQueryRequest.class));
        return execute("queryOutboundOrder", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult cancelOutboundOrder(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderCancelV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService
                                .cancelOrder.OrderCancelRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService
                        .cancelOrder.OrderCancelRequest.class));
        return execute("cancelOutboundOrder", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryTracking(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderTraceQueryV2LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command,
                        com.lop.open.api.sdk.domain.IntegratedSupplyChain.OpenOrderTraceService
                                .commonQueryOrderTrace.CommonOrderTraceRequest.class),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.OpenOrderTraceService
                        .commonQueryOrderTrace.CommonOrderTraceRequest.class));
        return execute("queryTracking", command, request, response -> response.getResult());
    }

    private <T extends AbstractResponse> JdResult execute(
            String operation,
            Map<String, Object> command,
            DomainAbstractRequest<T> request,
            Function<T, Object> envelopeExtractor) {
        Instant startedAt = Instant.now();
        JdResult result;
        if (!configured()) {
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
        // 部分 DTO 没有 ownerNo 字段（如 SoCreateOrderRequest）或没有 pin 字段
        // （如 CommonOrderTraceRequest）；只注入目标 DTO 真实支持的默认值，
        // 避免严格 mapper 下未知属性转换失败。
        if (pin != null && !pin.isBlank() && supports(dtoType, PIN)) {
            request.putIfAbsent(PIN, pin);
        }
        if (ownerNo != null && !ownerNo.isBlank() && supports(dtoType, OWNER_NO)) {
            request.putIfAbsent(OWNER_NO, ownerNo);
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
                .requestId(boundedText(
                        context == null ? result.requestId() : context.getRequestId(), 128))
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getOperator() == null ? "jd-client" : context.getOperator())
                .actorType(AuditActorType.SYSTEM)
                .service("jd.isc")
                .operation(operation)
                .requestPayload(auditRequest(operation, command))
                .responsePayload(auditResponse(operation, result))
                .httpStatus(result.success() ? 200 : 502)
                .businessCode(boundedText(result.businessCode(), 64))
                .latencyMs((int) Duration.between(startedAt, Instant.now()).toMillis()));
    }

    private Object auditRequest(String operation, Map<String, Object> command) {
        Map<String, Object> values = command == null ? Map.of() : command;
        Map<String, Object> summary = new LinkedHashMap<>();
        if (!"queryOutboundOrder".equals(operation)) {
            summary.put("owner_no", boundedText(values.get("ownerNo"), 64));
            summary.put("warehouse_no", boundedText(values.get("warehouseNo"), 128));
            summary.put("erp_delivery_no", boundedText(values.get("erpDeliveryNo"), 64));
            summary.put("delivery_no", boundedText(values.get("deliveryNo"), 64));
            summary.put("item_count", firstCollectionSize(
                    values.get("cargoInfos"), values.get("deliveryItemList"), values.get("goodsList")));
            summary.put("field_count", Math.min(values.size(), 256));
            return summary;
        }
        summary.put("erp_delivery_no", boundedText(values.get("erpDeliveryNo"), 64));
        summary.put("delivery_no", boundedText(values.get("deliveryNo"), 64));
        summary.put("sales_platform_delivery_no", boundedText(values.get("salesPlatformDeliveryNo"), 64));
        summary.put("delivery_item_flag", exactFlag(values.get("deliveryItemFlag")));
        summary.put("delivery_status_flag", exactFlag(values.get("deliveryStatusFlag")));
        return summary;
    }

    /** querySoOrder 响应可含收件人、账号与自由文本；审计只保留定长引用和计数。 */
    private Object auditResponse(String operation, JdResult result) {
        if (!"queryOutboundOrder".equals(operation)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("success", result.success());
            summary.put("business_code", boundedText(result.businessCode(), 64));
            summary.put("request_id", boundedText(result.requestId(), 128));
            summary.put("data_item_count", collectionSize(result.data()));
            summary.put("data_field_count", result.data() instanceof Map<?, ?> values
                    ? Math.min(values.size(), 256)
                    : 0);
            return summary;
        }
        Map<?, ?> data = result.data() instanceof Map<?, ?> values ? values : Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("business_code", boundedText(result.businessCode(), 64));
        summary.put("request_id", boundedText(result.requestId(), 128));
        summary.put("erp_delivery_no", boundedText(data.get("erpDeliveryNo"), 64));
        summary.put("delivery_no", boundedText(data.get("deliveryNo"), 64));
        summary.put("warehouse_no", boundedText(data.get("warehouseNo"), 128));
        summary.put("status", boundedText(data.get("status"), 32));
        summary.put("delivery_item_count", collectionSize(data.get("deliveryItemList")));
        summary.put("delivery_status_count", collectionSize(data.get("deliveryStatusList")));
        summary.put("split_delivery_count", splitCount(data.get("splitDeliveryNos")));
        return summary;
    }

    private Integer exactFlag(Object value) {
        if (value instanceof Number number) {
            int flag = number.intValue();
            return flag == 0 || flag == 1 ? flag : null;
        }
        return null;
    }

    private int collectionSize(Object value) {
        return value instanceof Collection<?> collection ? collection.size() : 0;
    }

    private int firstCollectionSize(Object... values) {
        for (Object value : values) {
            if (value instanceof Collection<?> collection) {
                return Math.min(collection.size(), 10_000);
            }
        }
        return 0;
    }

    private int splitCount(Object value) {
        String splitNos = boundedText(value, 4096);
        if (splitNos == null) return 0;
        int count = 0;
        for (String token : splitNos.split(",", -1)) {
            if (!token.isBlank()) count++;
        }
        return count;
    }

    private String boundedText(Object value, int maxLength) {
        if (!(value instanceof String raw)) return null;
        String result = raw.trim();
        if (result.isEmpty()
                || result.length() > maxLength
                || result.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return result;
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
