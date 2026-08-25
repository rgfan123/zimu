package cn.zimu.fulfillment.connector.jd.basicinfo;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService.getSellerInfo.JdlOpenRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService.queryCustomer.CustomerQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGISService.queryWarehouseCoverages.WarehouseQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryGoodsInfo.GoodsInfoQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryGoodsLevelCategories.GoodsCategoriesRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryShopGoodsInfo.ShopGoodsInfoQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformShopService.queryShopInfo.ShopInfoQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService.query.SupplierQueryRequest;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodscategoryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSellerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopgoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderWarehousecoveragesQueryV1LopRequest;
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

/** 真实京东 ISC LOP 基础信息查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdBasicInfoClient implements JDBasicInfoService {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdBasicInfoClient(
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            @Value("${app.jd.server-url:}") String serverUrl,
            @Value("${app.jd.app-key:}") String appKey,
            @Value("${app.jd.app-secret:}") String appSecret,
            @Value("${app.jd.access-token:}") String accessToken,
            @Value("${app.jd.pin:}") String pin,
            @Value("${app.jd.owner-no:}") String ownerNo) {
        // 全局 ObjectMapper 是 SNAKE_CASE，会丢 SDK DTO 的驼峰字段，这里必须独立一份。
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
    public JdResult queryCustomers(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoCustomerQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, CustomerQueryRequest.class),
                CustomerQueryRequest.class));
        return execute("queryCustomers", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySellers(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSellerQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, JdlOpenRequest.class),
                JdlOpenRequest.class));
        return execute("querySellers", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShops(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, ShopInfoQueryRequest.class),
                ShopInfoQueryRequest.class));
        return execute("queryShops", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShopGoods(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopgoodsQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, ShopGoodsInfoQueryRequest.class),
                ShopGoodsInfoQueryRequest.class));
        return execute("queryShopGoods", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySuppliers(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, SupplierQueryRequest.class),
                SupplierQueryRequest.class));
        return execute("querySuppliers", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryGoodsCategories(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodscategoryQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, GoodsCategoriesRequest.class),
                GoodsCategoriesRequest.class));
        return execute("queryGoodsCategories", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryWarehouseCoverages(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderWarehousecoveragesQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, WarehouseQueryRequest.class),
                WarehouseQueryRequest.class));
        return execute("queryWarehouseCoverages", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryGoodsInfo(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, GoodsInfoQueryRequest.class),
                GoodsInfoQueryRequest.class));
        return execute("queryGoodsInfo", command, request, response -> response.getResponse());
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
        // 供应商查询的 JdlApiListResponseBase 用 setRequestID（大写 D），LOWER_CAMEL_CASE 序列化为 requestID。
        String requestId = text(values.get("requestId"));
        if (requestId == null) {
            requestId = text(values.get("requestID"));
        }
        return new JdResult(
                success,
                businessCode == null ? "EMPTY_RESPONSE_CODE" : businessCode,
                message,
                requestId,
                values.get("data"));
    }

    private Map<String, Object> withDefaults(Map<String, Object> command, Class<?> dtoType) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (command != null) {
            request.putAll(command);
        }
        // 部分 DTO 没有 ownerNo 字段（如 JdlOpenRequest、GoodsCategoriesRequest）；只注入目标 DTO 真实支持的默认值，
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
