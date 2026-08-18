package cn.zimu.fulfillment.connector.jd.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformExceptionService.queryExceptionOrderList.ExceptionOrderQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService.queryInsideOrder.QueryAdjustmentRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.queryPoOrderDetail.PoOpenQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService.queryProcessOrder.ProcessOrderQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.getEclpNoByOutNo.EclpOrderNoRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.queryOrderNosByPage.OrderNosRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformTrajectoryService.queryCityTrack.CityTrackRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService.ulQuery.UlOrderQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.WaybillDeliveryTimeQueryService.queryDeliveryTime.WaybillDeliveryTimeRequest;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderAdjustmentQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderCitytrackQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliverytimeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDestroyQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderExceptionQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderOperateRelationQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderProcessedQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderQueryordernosbypageV1LopRequest;
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

/** 真实京东 ISC 订单查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdOrderClient implements JdOrderService {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdOrderClient(
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
    public JdResult queryOrderNosByPage(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderQueryordernosbypageV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, OrderNosRequest.class),
                OrderNosRequest.class));
        return execute("queryOrderNosByPage", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryAdjustment(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderAdjustmentQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, QueryAdjustmentRequest.class),
                QueryAdjustmentRequest.class));
        return execute("queryAdjustment", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryDestroy(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDestroyQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, UlOrderQueryRequest.class),
                UlOrderQueryRequest.class));
        return execute("queryDestroy", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryException(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderExceptionQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, ExceptionOrderQueryRequest.class),
                ExceptionOrderQueryRequest.class));
        return execute("queryException", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryPurchase(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderPurchaseQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, PoOpenQueryRequest.class),
                PoOpenQueryRequest.class));
        return execute("queryPurchase", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryProcessed(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderProcessedQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, ProcessOrderQueryRequest.class),
                ProcessOrderQueryRequest.class));
        return execute("queryProcessed", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryOperateRelation(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderOperateRelationQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, EclpOrderNoRequest.class),
                EclpOrderNoRequest.class));
        return execute("queryOperateRelation", command, request, response -> response.getResult());
    }

    @Override
    public JdResult queryDeliveryTime(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliverytimeQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, WaybillDeliveryTimeRequest.class),
                WaybillDeliveryTimeRequest.class));
        return execute("queryDeliveryTime", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryCityTrack(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderCitytrackQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, CityTrackRequest.class),
                CityTrackRequest.class));
        return execute("queryCityTrack", command, request, response -> response.getResponse());
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
        // 部分 DTO 没有 ownerNo 字段（如 ProcessOrderQueryRequest、WaybillDeliveryTimeRequest、CityTrackRequest）；
        // 只注入目标 DTO 真实支持的默认值，避免严格 mapper 下未知属性转换失败。
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
