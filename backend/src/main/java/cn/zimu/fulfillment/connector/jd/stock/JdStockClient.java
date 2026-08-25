package cn.zimu.fulfillment.connector.jd.stock;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryBatchChange.BatchChangeQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryCheckStock.CheckStockQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryGoodsLevelChange.LevelChangeQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStockQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryWarehouseStockMergeByWarehouse.StockSummaryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryWarehouseStockSnapshot.StockSnapshotRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.searchShopStockFlow.ShopStockFlowQueryRequest;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockBatchchangeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockFlowShopstockQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockLevelchangeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockmergeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStocksnapshotQueryV1LopRequest;
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

/** 真实京东 ISC 库存查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdStockClient implements JDStockService {

    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdStockClient(
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
    public JdResult queryStockSnapshot(Map<String, Object> command) {
        var request = new IntegratedsupplychainStocksnapshotQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, StockSnapshotRequest.class),
                StockSnapshotRequest.class));
        return execute("queryStockSnapshot", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryStockSummary(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockmergeQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, StockSummaryRequest.class),
                StockSummaryRequest.class));
        return execute("queryStockSummary", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryBatchChange(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockBatchchangeQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, BatchChangeQueryRequest.class),
                BatchChangeQueryRequest.class));
        return execute("queryBatchChange", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryGoodsLevelChange(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockLevelchangeQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, LevelChangeQueryRequest.class),
                LevelChangeQueryRequest.class));
        return execute("queryGoodsLevelChange", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShelfLifeGoods(Map<String, Object> command) {
        // 官方 SDK 中「效期商品」请求体复用的是盘点（CheckStock）DTO。
        var request = new IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, CheckStockQueryRequest.class),
                CheckStockQueryRequest.class));
        return execute("queryShelfLifeGoods", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShelfLifeInventory(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, ShelfLifeGoodsStockQueryRequest.class),
                ShelfLifeGoodsStockQueryRequest.class));
        return execute("queryShelfLifeInventory", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult searchShopStockFlow(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockFlowShopstockQueryV1LopRequest();
        request.setRequest(sdkMapper.convertValue(withDefaults(command, ShopStockFlowQueryRequest.class),
                ShopStockFlowQueryRequest.class));
        return execute("searchShopStockFlow", command, request, response -> response.getResponse());
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
        // 库存快照/汇总 DTO 没有 pin 字段；只注入目标 DTO 真实支持的默认值，避免未知属性转换失败。
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
