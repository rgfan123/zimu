package cn.zimu.fulfillment.connector.jufubao;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ConnectionTestResult;
import cn.zimu.fulfillment.connector.ConnectorRuntime;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JufubaoConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(JufubaoConnector.class);
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final SourceImportService sourceImportService;
    private final JufubaoPullClient pullClient;
    private final JufubaoOrderTransform transform;
    private final JufubaoShipmentGateway shipmentGateway;
    private final JufubaoShipmentAttemptStore attemptStore;

    @Autowired
    public JufubaoConnector(
            SourceImportService sourceImportService,
            JufubaoPullClient pullClient,
            JufubaoOrderTransform transform,
            JufubaoShipmentGateway shipmentGateway,
            JufubaoShipmentAttemptStore attemptStore) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.transform = transform;
        this.shipmentGateway = shipmentGateway;
        this.attemptStore = attemptStore;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.JUFUBAO;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(true, true, true, true, false);
    }

    @Override
    protected LoginProbe loginProbe() {
        JufubaoPullClient.LoginResult login = pullClient.login();
        return new LoginProbe(login.ok(), login.businessCode(), login.message());
    }

    @Override
    public ConnectionTestResult testConnection(ConnectorRuntime runtime) {
        if (runtime == null) {
            return new ConnectionTestResult(
                    false,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    0,
                    "JUFUBAO_RUNTIME_REQUIRED",
                    "聚福宝 Connector 运行配置缺失");
        }
        // 读写 HTTP 适配器共用同一会话；一次登录探测同时验证两端的鉴权前置。
        return super.testConnection(runtime);
    }

    @Override
    public PullResult pullOrders(PullCursor cursor) {
        SourceChannel channel = channel();
        try {
            JufubaoPullClient.LoginResult login = pullClient.login();
            if (!login.ok()) {
                return failed(channel, login.businessCode(), login.message());
            }
            long[] range = epochRange(cursor);
            List<Map<String, Object>> orders = pullClient.pullOrders(range[0], range[1]);
            if (orders.isEmpty()) {
                return PullResult.empty(channel, null);
            }
            List<StructuredOrderRow> rows = transform.toRows(orders);
            String batchNo = "PULL-JUFUBAO-" + LocalDateTime.now(SHANGHAI).format(BATCH_NO);
            Map<String, Object> batch = sourceImportService.importStructured(
                    SourceChannel.JUFUBAO, rows, batchNo, commandContext());
            int accepted = acceptedCount(batch);
            String message = "已拉取聚福宝待发货订单 " + orders.size()
                    + " 单；因收货人契约未验证，批次 " + batch.get("batch_no") + " 已进入人工复核";
            log.info("聚福宝拉取完成: batchNo={}, pulled={}, accepted={}", batch.get("batch_no"), orders.size(), accepted);
            return success(channel, accepted, message, batch);
        } catch (BusinessException exception) {
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (JufubaoPullClient.PullTransportException exception) {
            log.warn("聚福宝拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("聚福宝拉取失败，未记录响应体或凭据");
            return failed(channel, "PLATFORM_PULL_ERROR", "聚福宝拉取请求失败");
        }
    }

    @Override
    public SourceSyncResult pushShipmentResult(SourceShipmentResult result) {
        SourceSyncResult invalid = validate(result);
        if (invalid != null) {
            return invalid;
        }
        JufubaoShipmentAttemptStore.ShipmentAttemptPayload payload =
                new JufubaoShipmentAttemptStore.ShipmentAttemptPayload(
                        result.sourceRef(),
                        result.sourceLineRef(),
                        result.actualShippedQuantity(),
                        result.carrierOutputValue(),
                        result.firstTrackingNo());
        JufubaoShipmentAttemptStore.ClaimResult claim;
        try {
            claim = attemptStore.claim(payload);
        } catch (RuntimeException exception) {
            return SourceSyncResult.failed(
                    "JUFUBAO_IDEMPOTENCY_UNAVAILABLE",
                    "聚福宝发货幂等门禁不可用，未提交外部写请求");
        }
        if (claim.decision() == JufubaoShipmentAttemptStore.Decision.REPLAY
                || claim.decision() == JufubaoShipmentAttemptStore.Decision.RECONCILIATION_REQUIRED) {
            return claim.replay() == null
                    ? reconciliationRequired("聚福宝发货结果需要人工对账", null)
                    : claim.replay();
        }
        if (claim.decision() == JufubaoShipmentAttemptStore.Decision.CONFLICT) {
            return SourceSyncResult.failed(
                    "JUFUBAO_IDEMPOTENCY_CONFLICT",
                    "同一聚福宝拆单号和快递单号已被不同发货请求使用");
        }
        if (claim.decision() == JufubaoShipmentAttemptStore.Decision.IN_PROGRESS) {
            return SourceSyncResult.failed(
                    "JUFUBAO_PUSH_IN_PROGRESS",
                    "同一聚福宝拆单号和快递单号正在处理，未重复提交");
        }
        return executeClaimed(result, claim.ownerToken());
    }

    private SourceSyncResult executeClaimed(SourceShipmentResult result, String ownerToken) {
        SourceSyncResult outcome = pushOnce(result, ownerToken);
        String subOrderId = result.sourceLineRef().trim();
        String trackingNo = result.firstTrackingNo().trim();
        try {
            if (outcome.success()) {
                attemptStore.completeSuccess(subOrderId, trackingNo, ownerToken, outcome);
            } else if ("RECONCILIATION_REQUIRED".equals(outcome.businessCode())) {
                attemptStore.completeUnknown(subOrderId, trackingNo, ownerToken, outcome);
            } else {
                attemptStore.release(
                        subOrderId, trackingNo, ownerToken, outcome.businessCode(), outcome.message());
            }
            return outcome;
        } catch (RuntimeException exception) {
            if (!outcome.success() && !"RECONCILIATION_REQUIRED".equals(outcome.businessCode())) {
                return SourceSyncResult.failed(
                        "JUFUBAO_IDEMPOTENCY_UNAVAILABLE",
                        "聚福宝发货未成功，且幂等结果未能安全归档；未标记为平台结果未知");
            }
            return reconciliationRequired(
                    "聚福宝外部写结果未能完整归档，需要人工对账",
                    outcome.platformRef());
        }
    }

    private SourceSyncResult pushOnce(SourceShipmentResult result, String ownerToken) {
        String subOrderId = result.sourceLineRef().trim();
        boolean externalWriteStarted = false;
        try {
            JufubaoShipmentGateway.OrderState before = shipmentGateway.findOrder(subOrderId);
            if (!before.presentInNoDelivery() || !"NO_DELIVERY".equals(before.status())) {
                return SourceSyncResult.failed(
                        "JUFUBAO_ORDER_NOT_SHIPPABLE",
                        "聚福宝订单当前不在待发货状态，未提交物流");
            }

            List<ObjectNode> products = sanitizedProducts(shipmentGateway.shipmentDetail(subOrderId));
            if (products.isEmpty()) {
                return SourceSyncResult.failed("JUFUBAO_SHIPMENT_DETAIL_INVALID", "聚福宝发货详情缺少可发商品");
            }
            BigDecimal allowedQuantity;
            try {
                allowedQuantity = products.stream()
                        .map(this::allowedQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            } catch (IllegalArgumentException exception) {
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_DETAIL_INVALID",
                        "聚福宝发货详情的可发数量缺失或非正整数");
            }
            if (allowedQuantity.compareTo(result.actualShippedQuantity()) != 0) {
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_QUANTITY_MISMATCH",
                        "系统实发数量与聚福宝可发数量不一致，未提交物流");
            }

            JufubaoShipmentGateway.CarrierOption carrier = carrier(result.carrierOutputValue());
            if (carrier == null) {
                return SourceSyncResult.failed("JUFUBAO_CARRIER_UNMAPPED", "物流公司未在聚福宝字典中找到，未提交物流");
            }

            shipmentGateway.prepareWrite();
            attemptStore.markEffectStarted(
                    subOrderId, result.firstTrackingNo().trim(), ownerToken);
            externalWriteStarted = true;
            JufubaoShipmentGateway.SubmitResult submitted = shipmentGateway.submit(
                    new JufubaoShipmentGateway.ShipmentCommand(
                            subOrderId, products, carrier.value(), result.firstTrackingNo().trim()));
            if (submitted.outcome() == JufubaoShipmentGateway.SubmitResult.Outcome.REJECTED) {
                String code = safeBusinessCode(submitted.businessCode());
                return SourceSyncResult.failed(
                        code,
                        "聚福宝拒绝发货请求（业务码：" + code + "）",
                        submitted.platformRef());
            }
            if (submitted.outcome() == JufubaoShipmentGateway.SubmitResult.Outcome.UNKNOWN) {
                return reconciliationRequired("聚福宝发货响应无法确认", submitted.platformRef());
            }

            JufubaoShipmentGateway.OrderState after = shipmentGateway.findOrder(subOrderId);
            if (after.presentInNoDelivery()) {
                return reconciliationRequired(
                        "聚福宝已受理，但订单仍在待发货列表",
                        submitted.platformRef());
            }
            return SourceSyncResult.ok(submitted.platformRef());
        } catch (RuntimeException exception) {
            if (externalWriteStarted) {
                return reconciliationRequired("聚福宝发货调用结果未知，请到平台核对", null);
            }
            return SourceSyncResult.failed(
                    "JUFUBAO_PLATFORM_UNAVAILABLE",
                    "聚福宝发货前查询失败，尚未提交外部写请求");
        }
    }

    private SourceSyncResult validate(SourceShipmentResult result) {
        if (result == null || result.channel() != SourceChannel.JUFUBAO) {
            return SourceSyncResult.failed("JUFUBAO_CHANNEL_MISMATCH", "发货结果不属于聚福宝渠道");
        }
        if (isBlank(result.sourceLineRef())) {
            return SourceSyncResult.failed("JUFUBAO_SUB_ORDER_REQUIRED", "聚福宝拆单号不能为空");
        }
        if (result.actualShippedQuantity() == null
                || result.actualShippedQuantity().stripTrailingZeros().scale() > 0
                || result.actualShippedQuantity().signum() <= 0) {
            return SourceSyncResult.failed("JUFUBAO_QUANTITY_INVALID", "聚福宝实发数量必须为正整数");
        }
        if (!("SHIPPED".equals(result.outcome()) || "PARTIAL".equals(result.outcome()))) {
            return SourceSyncResult.failed(
                    "JUFUBAO_OUTCOME_NOT_SHIPPABLE",
                    "只有已发货或部分发货的履约结果可以回传聚福宝");
        }
        if (isBlank(result.carrierOutputValue())) {
            return SourceSyncResult.failed("JUFUBAO_CARRIER_REQUIRED", "聚福宝物流公司不能为空");
        }
        if (isBlank(result.firstTrackingNo())) {
            return SourceSyncResult.failed("JUFUBAO_TRACKING_REQUIRED", "聚福宝快递单号不能为空");
        }
        return null;
    }

    /** 起点含当日 00:00，终点含 until 全天（Asia/Shanghai）。 */
    private long[] epochRange(PullCursor cursor) {
        LocalDate begin = beginDate(cursor);
        LocalDate end = endDate(cursor);
        return new long[] {
            begin.atStartOfDay(SHANGHAI).toEpochSecond(),
            end.plusDays(1).atStartOfDay(SHANGHAI).toEpochSecond()
        };
    }

    private List<ObjectNode> sanitizedProducts(JufubaoShipmentGateway.ShipmentDetail detail) {
        if (detail == null) {
            return List.of();
        }
        List<ObjectNode> products = new ArrayList<>();
        for (ObjectNode product : detail.products()) {
            ObjectNode copy = product.deepCopy();
            removeBrowserOnlyFields(copy);
            products.add(copy);
        }
        return List.copyOf(products);
    }

    private BigDecimal allowedQuantity(ObjectNode product) {
        JsonNode value = product.path("allow_send_num");
        BigDecimal quantity;
        try {
            quantity = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid allow_send_num", exception);
        }
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("invalid allow_send_num");
        }
        return quantity;
    }

    private void removeBrowserOnlyFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> browserOnly = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().startsWith("fd-")) {
                    browserOnly.add(field.getKey());
                } else {
                    removeBrowserOnlyFields(field.getValue());
                }
            }
            object.remove(browserOnly);
        } else if (node.isArray()) {
            node.forEach(this::removeBrowserOnlyFields);
        }
    }

    private JufubaoShipmentGateway.CarrierOption carrier(String configuredValue) {
        String value = configuredValue.trim();
        return shipmentGateway.carrierOptions().stream()
                .filter(option -> option.label().equals(value) || Integer.toString(option.value()).equals(value))
                .findFirst()
                .orElse(null);
    }

    private SourceSyncResult reconciliationRequired(String detail, String platformRef) {
        return SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED",
                safeMessage(detail, "聚福宝发货结果未知") + "；禁止盲目重提，请到平台核对",
                platformRef);
    }

    private static String safeMessage(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static String safeBusinessCode(String value) {
        if (isBlank(value) || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            return "JUFUBAO_PLATFORM_REJECTED";
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
