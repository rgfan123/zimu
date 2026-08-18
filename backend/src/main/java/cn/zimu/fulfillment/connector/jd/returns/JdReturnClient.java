package cn.zimu.fulfillment.connector.jd.returns;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntosupplierQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopRequest;
import com.lop.open.api.sdk.response.AbstractResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC 退货退供查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdReturnClient implements JDReturnService {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdReturnClient(
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
    public JdResult queryRtwOrderList(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderList.RtwOpenQueryRequest.class));
        return execute("queryRtwOrderList", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryRtwOrderDetail(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntowarehouseQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderDetail.RtwOpenQueryRequest.class));
        return execute("queryRtwOrderDetail", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryReturnToSupplier(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntosupplierQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command),
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService
                        .queryReturnToSupplier.RtsOpenQueryRequest.class));
        return execute("queryReturnToSupplier", command, request, response -> response.getResponse());
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

    private Map<String, Object> withDefaults(Map<String, Object> command) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (command != null) {
            request.putAll(command);
        }
        if (pin != null && !pin.isBlank()) {
            request.putIfAbsent("pin", pin);
        }
        if (ownerNo != null && !ownerNo.isBlank()) {
            request.putIfAbsent("ownerNo", ownerNo);
        }
        return request;
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
