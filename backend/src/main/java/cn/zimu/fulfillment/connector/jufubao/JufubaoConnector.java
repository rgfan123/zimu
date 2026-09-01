package cn.zimu.fulfillment.connector.jufubao;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ConnectionTestResult;
import cn.zimu.fulfillment.connector.ConnectorRuntime;
import cn.zimu.fulfillment.connector.ExternalWritePermit;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceReceiverNormalizer;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private static final ObjectMapper EFFECT_HASH_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter BATCH_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final List<String> SHIPMENT_PRODUCT_WRITE_FIELDS = List.of(
            "product_id",
            "sku_id",
            "product_sku_name",
            "buy_num",
            "purchase_price",
            "product_thumb",
            "product_form_data");

    private final SourceImportService sourceImportService;
    private final JufubaoPullClient pullClient;
    private final JufubaoOrderTransform transform;
    private final JufubaoShipmentGateway shipmentGateway;
    private final JufubaoShipmentAttemptStore attemptStore;
    private final boolean allowLegacyUnguardedForTests;

    @Autowired
    public JufubaoConnector(
            SourceImportService sourceImportService,
            JufubaoPullClient pullClient,
            JufubaoOrderTransform transform,
            JufubaoShipmentGateway shipmentGateway,
            JufubaoShipmentAttemptStore attemptStore) {
        this(sourceImportService, pullClient, transform, shipmentGateway, attemptStore, false);
    }

    /** Package-private test seam; production Spring wiring always rejects unguarded writes. */
    JufubaoConnector(
            SourceImportService sourceImportService,
            JufubaoPullClient pullClient,
            JufubaoOrderTransform transform,
            JufubaoShipmentGateway shipmentGateway,
            JufubaoShipmentAttemptStore attemptStore,
            boolean allowLegacyUnguardedForTests) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.transform = transform;
        this.shipmentGateway = shipmentGateway;
        this.attemptStore = attemptStore;
        this.allowLegacyUnguardedForTests = allowLegacyUnguardedForTests;
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
                // 诊断 L1：登录失败早退必须留痕（渠道 + 业务码）；凭据与响应体绝不入日志。
                log.warn("聚福宝登录失败，本次拉取中止: channel={}, businessCode={}", channel, login.businessCode());
                return failed(channel, login.businessCode(), login.message());
            }
            long[] range = epochRange(cursor);
            List<Map<String, Object>> orders = pullClient.pullOrders(range[0], range[1]);
            if (orders.isEmpty()) {
                return PullResult.empty(channel, null);
            }
            List<StructuredOrderRow> rows = new ArrayList<>();
            for (Map<String, Object> order : orders) {
                Object rawSubOrderId = order == null ? null : order.get("sub_order_id");
                String subOrderId = rawSubOrderId == null ? "" : rawSubOrderId.toString().trim();
                JufubaoShipmentGateway.ShipmentDetail detail = null;
                if (!subOrderId.isBlank()) {
                    try {
                        detail = shipmentGateway.shipmentDetail(subOrderId);
                    } catch (RuntimeException exception) {
                        // 单单详情失败只把该行留给人工复核；不复制响应/PII，也不回滚其他订单。
                    }
                }
                rows.add(transform.toRow(order, detail));
            }
            String batchNo = "PULL-JUFUBAO-" + LocalDateTime.now(SHANGHAI).format(BATCH_NO);
            Map<String, Object> batch = sourceImportService.importStructured(
                    SourceChannel.JUFUBAO, rows, batchNo, commandContext());
            int accepted = acceptedCount(batch);
            String message = "已拉取聚福宝待处理订单 " + orders.size()
                    + " 单，并以订单详情收货事实进入导入批次 " + batch.get("batch_no");
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
    public SourcePlatformCheckResult checkShipmentResult(SourceShipmentResult result) {
        if (result == null
                || result.channel() != SourceChannel.JUFUBAO
                || isBlank(result.sourceLineRef())) {
            return SourcePlatformCheckResult.unavailable(
                    channel(),
                    "JUFUBAO_SHIPMENT_LINEAGE_REQUIRED",
                    "聚福宝 Shipment 来源子单血缘不完整");
        }
        try {
            String subOrderId = result.sourceLineRef().trim();
            JufubaoShipmentGateway.OrderState state = shipmentGateway.findOrder(subOrderId);
            JufubaoShipmentGateway.AddressCheck address = shipmentGateway.checkShipmentAddress(subOrderId);
            JufubaoShipmentGateway.ShipmentDetail detail = shipmentGateway.shipmentDetail(subOrderId);
            List<ObjectNode> products = sanitizedProducts(detail);
            long sendable = totalAllowedQuantity(products);
            JufubaoShipmentGateway.CarrierOption mappedCarrier = isBlank(result.carrierOutputValue())
                    ? null : carrier(result.carrierOutputValue());
            boolean carrierMapped = mappedCarrier != null;
            String effectHash = carrierMapped && !products.isEmpty()
                    ? effectHash(products, mappedCarrier)
                    : null;
            JufubaoShipmentGateway.ReceiverSnapshot receiver = detail == null ? null : detail.receiver();
            SourcePlatformCheckResult.AddressStatus addressStatus = !address.known()
                    ? SourcePlatformCheckResult.AddressStatus.UNKNOWN
                    : address.confirmationRequired()
                            ? SourcePlatformCheckResult.AddressStatus.CONFIRMATION_REQUIRED
                            : SourcePlatformCheckResult.AddressStatus.CLEAR;
            return new SourcePlatformCheckResult(
                    true,
                    "OK",
                    "聚福宝 Shipment 当前事实已读取",
                    state.status(),
                    state.presentInNoDelivery() && "NO_RECEIPT".equals(state.status()),
                    addressStatus,
                    receiver == null ? null : receiver.name(),
                    receiver == null ? null : receiver.phone(),
                    receiver == null ? null : receiver.address(),
                    sendable,
                    carrierMapped,
                    effectHash);
        } catch (RuntimeException exception) {
            // 与拉取失败同一纪律：不回显异常正文（可能带响应体、表单字段与凭据）。
            // 但类型与子单号必须留下——2026-08-29 生产上一单在平台已手动发过货，
            // 这里只吐出「读取失败」四个字，分不清是「已发货所以读不到」还是「登录挂了」。
            log.warn(
                    "聚福宝 Shipment 事实读取失败 sub_order_id={} type={}",
                    result.sourceLineRef(),
                    exception.getClass().getSimpleName());
            return SourcePlatformCheckResult.unavailable(
                    channel(),
                    "JUFUBAO_PLATFORM_CHECK_UNAVAILABLE",
                    "聚福宝 Shipment 当前事实读取失败");
        }
    }

    @Override
    public SourceSyncResult pushShipmentResult(SourceShipmentResult result) {
        if (!allowLegacyUnguardedForTests) {
            return SourceSyncResult.failed(
                    "SOURCE_SYNC_EXECUTION_CONTEXT_REQUIRED",
                    "聚福宝在线发货必须通过 Shipment source-sync execute 入口");
        }
        return pushShipmentResult(result, () -> {});
    }

    @Override
    public SourceSyncResult pushShipmentResult(
            SourceShipmentResult result,
            ExternalWritePermit permit) {
        if (permit == null) {
            return SourceSyncResult.failed(
                    "JUFUBAO_WRITE_PERMIT_REQUIRED",
                    "聚福宝发货缺少有效外部写许可，未提交平台请求");
        }
        SourceSyncResult invalid = validate(result);
        if (invalid != null) {
            return invalid;
        }
        JufubaoShipmentAttemptStore.ShipmentAttemptPayload payload =
                new JufubaoShipmentAttemptStore.ShipmentAttemptPayload(
                        result.sourceRef(),
                        result.sourceLineRef(),
                        result.sourceUnitQuantity(),
                        result.carrierOutputValue(),
                        result.firstTrackingNo(),
                        result.expectedPlatformEffectHash());
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
        return executeClaimed(result, claim.ownerToken(), permit);
    }

    @Override
    public boolean releaseShipmentIntent(String platformIntentKey) {
        return attemptStore.releaseReconciledNotAccepted(platformIntentKey);
    }

    private SourceSyncResult executeClaimed(
            SourceShipmentResult result,
            String ownerToken,
            ExternalWritePermit permit) {
        SourceSyncResult outcome = pushOnce(result, ownerToken, permit);
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

    private SourceSyncResult pushOnce(
            SourceShipmentResult result,
            String ownerToken,
            ExternalWritePermit permit) {
        String subOrderId = result.sourceLineRef().trim();
        boolean externalWriteStarted = false;
        try {
            JufubaoShipmentGateway.OrderState before = shipmentGateway.findOrder(subOrderId);
            if (before.presentInNoDelivery() && "NO_RECEIPT".equals(before.status())) {
                shipmentGateway.prepareWrite();
                attemptStore.markEffectStarted(
                        subOrderId, result.firstTrackingNo().trim(), ownerToken);
                attemptStore.verifyWritePermit(
                        subOrderId, result.firstTrackingNo().trim(), ownerToken);
                permit.beforeExternalWrite();
                externalWriteStarted = true;
                JufubaoShipmentGateway.ReceiveResult received = shipmentGateway.receiveOrder(subOrderId);
                if (received.outcome() == JufubaoShipmentGateway.ReceiveResult.Outcome.REJECTED) {
                    String code = safeBusinessCode(received.businessCode());
                    return SourceSyncResult.failed(
                            "JUFUBAO_RECEIVE_REJECTED",
                            "聚福宝拒绝接单请求（业务码：" + code + "）",
                            received.platformRef());
                }
                if (received.outcome() == JufubaoShipmentGateway.ReceiveResult.Outcome.UNKNOWN) {
                    return reconciliationRequired("聚福宝接单响应无法确认", received.platformRef());
                }
                before = shipmentGateway.awaitNoDelivery(subOrderId);
                if (!before.presentInNoDelivery() || !"NO_DELIVERY".equals(before.status())) {
                    return reconciliationRequired(
                            "聚福宝已受理接单，但订单未进入待发货状态",
                            received.platformRef());
                }
            }
            if (!before.presentInNoDelivery() || !"NO_DELIVERY".equals(before.status())) {
                return SourceSyncResult.failed(
                        "JUFUBAO_ORDER_NOT_SHIPPABLE",
                        "聚福宝订单当前不在待发货状态，未提交物流");
            }

            JufubaoShipmentGateway.AddressCheck addressCheck =
                    shipmentGateway.checkShipmentAddress(subOrderId);
            if (!addressCheck.known()) {
                return SourceSyncResult.failed(
                        "JUFUBAO_ADDRESS_CHECK_UNKNOWN",
                        "聚福宝收货地址检查结果未知，未提交物流");
            }
            if (addressCheck.confirmationRequired()) {
                return SourceSyncResult.failed(
                        "JUFUBAO_ADDRESS_CONFIRMATION_REQUIRED",
                        "聚福宝收货地址已变化，需要操作员确认后再发货");
            }

            JufubaoShipmentGateway.ShipmentDetail initialDetail = shipmentGateway.shipmentDetail(subOrderId);
            if (!sameReceiver(result, initialDetail == null ? null : initialDetail.receiver())) {
                return SourceSyncResult.failed(
                        "JUFUBAO_RECEIVER_MISMATCH",
                        "Shipment 与聚福宝当前收货信息不一致，未提交物流");
            }
            List<ObjectNode> products = sanitizedProducts(initialDetail);
            if (products.isEmpty()) {
                return SourceSyncResult.failed("JUFUBAO_SHIPMENT_DETAIL_INVALID", "聚福宝发货详情缺少可发商品");
            }
            long allowedQuantity;
            try {
                allowedQuantity = totalAllowedQuantity(products);
            } catch (IllegalArgumentException exception) {
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_DETAIL_INVALID",
                        "聚福宝发货详情的可发数量缺失或非正整数");
            }
            if (allowedQuantity != result.sourceUnitQuantity()) {
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_QUANTITY_MISMATCH",
                        "聚福宝来源平台发货份数与可发数量不一致，未提交物流");
            }

            JufubaoShipmentGateway.CarrierOption carrier = carrier(result.carrierOutputValue());
            if (carrier == null) {
                return SourceSyncResult.failed("JUFUBAO_CARRIER_UNMAPPED", "物流公司未在聚福宝字典中找到，未提交物流");
            }

            if (!externalWriteStarted) {
                shipmentGateway.prepareWrite();
            }
            JufubaoShipmentGateway.ShipmentDetail latestDetail = shipmentGateway.shipmentDetail(subOrderId);
            if (!sameReceiver(result, latestDetail == null ? null : latestDetail.receiver())) {
                return SourceSyncResult.failed(
                        "JUFUBAO_RECEIVER_MISMATCH",
                        "Shipment 与聚福宝提交前最新收货信息不一致，未提交物流");
            }
            products = sanitizedProducts(latestDetail);
            try {
                allowedQuantity = totalAllowedQuantity(products);
            } catch (IllegalArgumentException exception) {
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_DETAIL_INVALID",
                        "聚福宝提交前最新可发数量缺失或非正整数");
            }
            if (products.isEmpty() || allowedQuantity != result.sourceUnitQuantity()) {
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_QUANTITY_MISMATCH",
                        "聚福宝提交前最新可发数量已变化，未提交物流");
            }
            JufubaoShipmentGateway.ShipmentCommand command =
                    new JufubaoShipmentGateway.ShipmentCommand(
                            subOrderId,
                            submissionProducts(products),
                            carrier.value(),
                            result.firstTrackingNo().trim());
            String latestEffectHash = effectHash(products, carrier);
            if (!isBlank(result.expectedPlatformEffectHash())
                    && !result.expectedPlatformEffectHash().equals(latestEffectHash)) {
                return SourceSyncResult.failed(
                        "JUFUBAO_WRITE_PLAN_CHANGED",
                        "聚福宝可发商品或承运商字典在确认后变化，未提交物流");
            }

            if (!externalWriteStarted) {
                attemptStore.markEffectStarted(
                        subOrderId, result.firstTrackingNo().trim(), ownerToken);
                attemptStore.verifyWritePermit(
                        subOrderId, result.firstTrackingNo().trim(), ownerToken);
                permit.beforeExternalWrite();
                externalWriteStarted = true;
            } else {
                attemptStore.verifyWritePermit(
                        subOrderId, result.firstTrackingNo().trim(), ownerToken);
                permit.beforeExternalWrite();
            }
            JufubaoShipmentGateway.SubmitResult submitted = shipmentGateway.submit(command);
            if (submitted.outcome() == JufubaoShipmentGateway.SubmitResult.Outcome.REJECTED) {
                String code = safeBusinessCode(submitted.businessCode());
                return SourceSyncResult.failed(
                        "JUFUBAO_SHIPMENT_REJECTED",
                        "聚福宝拒绝发货请求（业务码：" + code + "）",
                        submitted.platformRef());
            }
            if (submitted.outcome() == JufubaoShipmentGateway.SubmitResult.Outcome.UNKNOWN) {
                return reconciliationRequired("聚福宝发货响应无法确认", submitted.platformRef());
            }

            JufubaoShipmentGateway.OrderState after = shipmentGateway.awaitNotPending(subOrderId);
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
        if (result.actualShippedQuantity() <= 0) {
            return SourceSyncResult.failed("JUFUBAO_QUANTITY_INVALID", "聚福宝实发数量必须为正整数");
        }
        if (result.sourceUnitQuantity() == null || result.sourceUnitQuantity() <= 0) {
            return SourceSyncResult.failed(
                    "JUFUBAO_SOURCE_QUANTITY_INVALID",
                    "聚福宝来源平台发货份数必须为正整数");
        }
        if (!"SHIPPED".equals(result.outcome())) {
            return SourceSyncResult.failed(
                    "JUFUBAO_OUTCOME_NOT_SHIPPABLE",
                    "P0 只允许完整已发货结果回传聚福宝");
        }
        if (isBlank(result.carrierOutputValue())) {
            return SourceSyncResult.failed("JUFUBAO_CARRIER_REQUIRED", "聚福宝物流公司不能为空");
        }
        if (isBlank(result.firstTrackingNo())) {
            return SourceSyncResult.failed("JUFUBAO_TRACKING_REQUIRED", "聚福宝快递单号不能为空");
        }
        if (isBlank(result.receiverName())
                || isBlank(result.receiverPhone())
                || isBlank(result.receiverAddress())) {
            return SourceSyncResult.failed("JUFUBAO_RECEIVER_REQUIRED", "聚福宝发货缺少 Shipment 收货信息");
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

    private int allowedQuantity(ObjectNode product) {
        JsonNode value = product.path("allow_send_num");
        try {
            return cn.zimu.fulfillment.common.domain.CountQuantity.fromPositiveFileValue(value.asText());
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException exception) {
            throw new IllegalArgumentException("invalid allow_send_num", exception);
        }
    }

    private long totalAllowedQuantity(List<ObjectNode> products) {
        long total = 0;
        for (ObjectNode product : products) {
            total = Math.addExact(total, allowedQuantity(product));
        }
        return total;
    }

    private boolean sameReceiver(
            SourceShipmentResult result,
            JufubaoShipmentGateway.ReceiverSnapshot platform) {
        return platform != null
                && SourceReceiverNormalizer.sameName(result.receiverName(), platform.name())
                && SourceReceiverNormalizer.samePhone(result.receiverPhone(), platform.phone())
                && SourceReceiverNormalizer.sameAddress(result.receiverAddress(), platform.address());
    }

    /**
     * HAR entry 667 的写 DTO 不是 {@code sub-order-info} 的读取 DTO 原样透传：只保留平台
     * 实际提交字段，并把整数型 {@code allow_send_num} 转成字符串型 {@code send_num}。
     */
    private List<ObjectNode> submissionProducts(List<ObjectNode> products) {
        List<ObjectNode> submissions = new ArrayList<>();
        for (ObjectNode product : products) {
            ObjectNode submission = JsonNodeFactory.instance.objectNode();
            for (String field : SHIPMENT_PRODUCT_WRITE_FIELDS) {
                if (product.has(field)) {
                    submission.set(field, product.get(field).deepCopy());
                }
            }
            submission.put("send_num", Integer.toString(allowedQuantity(product)));
            submissions.add(submission);
        }
        return List.copyOf(submissions);
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
        List<JufubaoShipmentGateway.CarrierOption> matches = shipmentGateway.carrierOptions().stream()
                .filter(option -> option.label().equals(value) || Integer.toString(option.value()).equals(value))
                .limit(2)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String effectHash(
            List<ObjectNode> products,
            JufubaoShipmentGateway.CarrierOption carrier) {
        return JufubaoShipmentAttemptStore.payloadHash(
                EFFECT_HASH_MAPPER,
                new ShipmentWriteFingerprint(carrier.value(), submissionProducts(products)));
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

    private record ShipmentWriteFingerprint(int companyId, List<ObjectNode> productList) {}

}
