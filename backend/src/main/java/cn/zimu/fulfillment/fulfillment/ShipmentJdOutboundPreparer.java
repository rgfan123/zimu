package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.fulfillment.JdShipmentSubmissionPlan.Blocker;
import cn.zimu.fulfillment.fulfillment.JdShipmentSubmissionPlan.StockDemand;
import cn.zimu.fulfillment.fulfillment.JdShipmentSubmissionPlan.Validation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 京东出库单构造单元：一个 Shipment 及其全部 ShipmentItems 聚合为唯一一张京东出库单请求
 * （addSoOrder payload），产出不可变 {@link JdShipmentSubmissionPlan}。
 *
 * <p>预览 HTTP、库存判定和真实建单都必须消费本单元产出的这一份计划，不得另建
 * SKU/数量/地址映射逻辑。本单元只做加锁读取与请求构造：不触发京东写操作、不写审计、
 * 不编排业务事务（独立事务由调用方/编排单元决定），因此出库编排与库存校验都可以
 * 单向依赖它，双方不再互相依赖。
 */
@Service
public class ShipmentJdOutboundPreparer {

    /** 出库编排与库存判定共用的建单资格/集成状态常量。 */
    static final String READY_TO_EXPORT = "READY_TO_EXPORT";
    static final String WAITING_PROVIDER = "WAITING_PROVIDER";
    static final String FULFILLING = "FULFILLING";
    static final String JD_WAREHOUSE = "JD_WAREHOUSE";
    static final String SYNC_STATUS_SUBMITTING = "SUBMITTING";
    static final String SYNC_STATUS_SUBMITTED = "SUBMITTED";
    static final String SYNC_STATUS_SYNC_FAILED = "SYNC_FAILED";
    static final Set<String> UNCERTAIN_EXTERNAL_RESULTS = Set.of(
            "SDK_CALL_FAILED", "EMPTY_RESPONSE_CODE", "UNKNOWN", "RECONCILIATION_REQUIRED",
            // 外层成功但内层缺失时 normalize 可能仅保留这些成功码，仍属部分响应。
            "0", "200", "1000", "10000", "SUCCESS");

    private static final String PASS = "PASS";
    private static final String BLOCKED = "BLOCKED";
    private static final String SOURCE_PROVIDER_CONFIG = "fulfillment_providers.config.";

    /**
     * fulfillment_providers.config JSONB 配置键；京东标识一律来自履约方配置，缺失时阻断建单
     * （spec: identifiers come from the selected FulfillmentProvider configuration, never hard-coded）。
     */
    static final String CONFIG_SOURCE_NO = "sourceNo";
    static final String CONFIG_WAREHOUSE_NO = "warehouseNo";
    static final String CONFIG_ERP_SHOP_NO = "erpShopNo";
    static final String CONFIG_SHOP_NO = "shopNo";
    static final String CONFIG_OWNER_NO = "ownerNo";
    static final String CONFIG_SALES_PLATFORM_SOURCE = "salesPlatformSource";
    static final String CONFIG_PIN = "pin";
    static final String CONFIG_CARRIER_NO = "carrierNo";
    static final String CONFIG_TOWN_REQUIRED = "townRequired";
    static final String CONFIG_CUSTOMER_CODE = "customerCode";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public ShipmentJdOutboundPreparer(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 构建不带审计、不触发京东写操作的内部提交计划。后续库存判定只消费此 seam，
     * 不得复制本服务的数量换算或请求映射。查询期间锁定 Shipment 事实，避免请求组装内部漂移。
     */
    @Transactional
    JdShipmentSubmissionPlan plan(long shipmentId) {
        return plan(lockContext(shipmentId));
    }

    /** 在独立事务中重建并锁定同一提交计划；用于提交意图与库存复查前的事实重建。 */
    JdShipmentSubmissionPlan planInNewTransaction(long shipmentId) {
        JdShipmentSubmissionPlan plan =
                requiresNew.execute(status -> plan(lockContext(shipmentId)));
        if (plan == null) {
            throw new IllegalStateException("JD outbound planning transaction returned no plan");
        }
        return plan;
    }

    /** 预览、库存与提交共用的唯一请求构建器；调用者只能消费其确定性结果。 */
    private JdShipmentSubmissionPlan plan(Context state) {
        Map<String, Object> request = new LinkedHashMap<>();
        List<Validation> validations = new ArrayList<>();
        List<Blocker> blockers = new ArrayList<>();

        validateShipmentEligibility(state, validations, blockers);
        putRequiredConfig(request, "sourceNo", state, CONFIG_SOURCE_NO, "sourceNo", false, validations, blockers);
        request.put("erpDeliveryNo", state.outboundOrderNo());
        pass(validations, "erpDeliveryNo", "shipments.outbound_order_no");
        putRequiredConfig(request, "warehouseNo", state, CONFIG_WAREHOUSE_NO, "warehouseNo", false, validations, blockers);

        // 订单类型留空（模板：订单类型不传，京东默认 B2C=1），不再显式下发。
        validations.add(new Validation(
                "orderType", "OMITTED", "JD sales-outbound policy", "订单类型留空，京东默认 B2C=1"));
        request.put("orderMark", "0".repeat(50));
        pass(validations, "orderMark", "non-COD outbound policy (50 zero bits)");
        putRequiredConfig(request, "pin", state, CONFIG_PIN, "pin", true, validations, blockers);

        Map<String, Object> channelInfo = new LinkedHashMap<>();
        putRequiredConfig(channelInfo, "erpShopNo", state, CONFIG_ERP_SHOP_NO,
                "channelInfo.erpShopNo", false, validations, blockers);
        // 销售平台订单号留空（真实建单 2026-08-18 模板：来源 6 不传平台单号），
        // 不再把彩食鲜来源单号填入 channelInfo.salesPlatformDeliveryNo。
        validations.add(new Validation(
                "channelInfo.salesPlatformDeliveryNo", "OMITTED", "sales-platform template",
                "销售平台来源非京东平台，销售平台订单号留空"));
        putRequiredConfig(channelInfo, "salesPlatformSource", state, CONFIG_SALES_PLATFORM_SOURCE,
                "channelInfo.salesPlatformSource", false, validations, blockers);
        request.put("channelInfo", channelInfo);

        Map<String, Object> customerInfo = new LinkedHashMap<>();
        putCustomerCode(customerInfo, state, validations, blockers);
        putRequiredConfig(customerInfo, "ownerNo", state, CONFIG_OWNER_NO,
                "customerInfo.ownerNo", false, validations, blockers);
        putRequiredConfig(customerInfo, "shopNo", state, CONFIG_SHOP_NO,
                "customerInfo.shopNo", false, validations, blockers);
        request.put("customerInfo", customerInfo);

        Boolean townRequired = requiredTownPolicy(state, validations, blockers);
        Map<String, Object> receiverInfo = receiverPreview(state, townRequired, validations, blockers);
        request.put("receiverInfo", receiverInfo);

        Map<String, Object> carrierInfo = new LinkedHashMap<>();
        putRequiredConfig(carrierInfo, "carrierNo", state, CONFIG_CARRIER_NO,
                "carrierInfo.carrierNo", false, validations, blockers);
        request.put("carrierInfo", carrierInfo);

        List<Map<String, Object>> cargos = new ArrayList<>();
        for (Item item : state.items()) {
            // SINGLE 行/礼包组件的展开与数量换算由共享 JdCargoPlanner 一处裁决：
            // 建单预览/提交与行投影（ImportRowJdCargoProjectionService）同序同量。
            List<JdCargoPlanner.ComponentCandidate> components = state.componentsByOrderLine()
                    .getOrDefault(item.orderLineId(), List.of()).stream()
                    .map(component -> new JdCargoPlanner.ComponentCandidate(
                            component.componentNo(), component.skuId(), component.productName(),
                            component.unit(), component.quantityPerBundle()))
                    .toList();
            for (JdCargoPlanner.CargoCandidate candidate : JdCargoPlanner.expand(
                    new JdCargoPlanner.LineCandidate(
                            item.lineType(), item.lineNo(), item.skuId(), item.productName(),
                            item.unit(), item.instructedQuantity(), components))) {
                cargos.add(cargoPreview(
                        state, candidate.skuId(), candidate.orderLine(), candidate.goodsName(),
                        candidate.unit(), candidate.systemQuantity(), candidate.quantitySource(),
                        cargos.size(), validations, blockers));
            }
        }
        if (cargos.isEmpty()) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_CARGO_EMPTY", "cargoInfos",
                    "shipment_items", "shipment_items",
                    "发货批次没有可建出库单的商品明细");
        }
        request.put("cargoInfos", cargos);
        List<StockDemand> stockDemands = cargos.stream()
                .filter(cargo -> cargo.get("skuId") instanceof Number
                        && cargo.get("goodsNo") != null
                        && cargo.get("planQuantity") instanceof Number)
                .map(cargo -> new StockDemand(
                        ((Number) cargo.get("skuId")).longValue(),
                        String.valueOf(cargo.get("goodsNo")),
                        ((Number) cargo.get("planQuantity")).intValue()))
                .toList();
        cargos.forEach(cargo -> cargo.remove("skuId"));
        return new JdShipmentSubmissionPlan(
                state.id(),
                state.shipmentVersion(),
                state.orderId(),
                state.providerId(),
                state.providerType(),
                state.outboundOrderNo(),
                state.jdOutbound() == null
                        ? null
                        : new JdShipmentSubmissionPlan.PriorSubmission(
                                state.jdOutbound().syncStatus(),
                                state.jdOutbound().requestHash(),
                                state.jdOutbound().retryCount(),
                                state.jdOutbound().lastErrorCode(),
                                state.jdOutbound().clientMode()),
                state.items().stream()
                        .map(item -> new JdShipmentSubmissionPlan.OrderLineState(
                                item.orderLineId(), item.processingStage()))
                        .toList(),
                request,
                sha256(json(request)),
                stockDemands,
                validations,
                blockers,
                state.receiverAddress());
    }

    private void validateShipmentEligibility(
            Context state, List<Validation> validations, List<Blocker> blockers) {
        if (JD_WAREHOUSE.equals(state.providerType())) {
            pass(validations, "shipment.provider", "fulfillment_providers.provider_type");
        } else {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_PROVIDER_UNSUPPORTED", "shipment.provider",
                    "fulfillment_providers.provider_type", "fulfillment provider master data",
                    "仅京东云仓（JD_WAREHOUSE）发货批次可提交京东出库单");
        }
        if (ShipmentStatus.acceptsOutboundSubmit(state.shipmentStatus())) {
            pass(validations, "shipment.status", "shipments.shipment_status");
        } else {
            block(
                    blockers, validations, 409, "JD_SHIPMENT_OUTBOUND_SHIPMENT_STATUS_INVALID", "shipment.status",
                    "shipments.shipment_status", "shipment lifecycle",
                    "发货批次状态必须是 CREATED 才能提交京东出库单（当前 " + state.shipmentStatus() + "）");
        }
        for (Item item : state.items()) {
            String path = "shipment.items[" + item.lineNo() + "].processingStage";
            if (READY_TO_EXPORT.equals(item.processingStage()) || WAITING_PROVIDER.equals(item.processingStage())) {
                pass(validations, path, "order_lines.processing_stage");
            } else {
                block(
                        blockers, validations, 409, "JD_SHIPMENT_OUTBOUND_STAGE_INVALID", path,
                        "order_lines.processing_stage", "order-line workflow",
                        "订单行必须处于 READY_TO_EXPORT 或 WAITING_PROVIDER 阶段（当前 "
                                + item.processingStage() + "）");
            }
        }
        if (state.jdOutbound() != null && SYNC_STATUS_SUBMITTED.equals(state.jdOutbound().syncStatus())) {
            block(
                    blockers, validations, 409, "JD_SHIPMENT_OUTBOUND_ALREADY_SUBMITTED", "shipment.jdOutbound",
                    "shipment_jd_outbounds.sync_status", "existing JD outbound integration record",
                    "该发货批次已提交京东出库单，禁止重复提交");
        }
    }

    private Map<String, Object> receiverPreview(
            Context state, Boolean townRequired, List<Validation> validations, List<Blocker> blockers) {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("name", state.receiverName());
        pass(validations, "receiverInfo.name", "shipments.receiver_name_snapshot");
        receiver.put("mobile", state.receiverPhone());
        pass(validations, "receiverInfo.mobile", "shipments.receiver_phone_snapshot");

        boolean confirmed = state.jdReceiverConfirmed();
        putConfirmedAddress(receiver, "province", state.jdReceiverProvince(), true, confirmed,
                "jd_receiver_province", validations, blockers);
        putConfirmedAddress(receiver, "city", state.jdReceiverCity(), true, confirmed,
                "jd_receiver_city", validations, blockers);
        putConfirmedAddress(receiver, "county", state.jdReceiverCounty(), true, confirmed,
                "jd_receiver_county", validations, blockers);
        putConfirmedAddress(receiver, "town", state.jdReceiverTown(), Boolean.TRUE.equals(townRequired), confirmed,
                "jd_receiver_town", validations, blockers);
        putConfirmedAddress(receiver, "detailAddress", state.jdReceiverDetailAddress(), true, confirmed,
                "jd_receiver_detail_address", validations, blockers);
        return receiver;
    }

    private void putConfirmedAddress(
            Map<String, Object> target,
            String requestKey,
            String value,
            boolean required,
            boolean confirmed,
            String column,
            List<Validation> validations,
            List<Blocker> blockers) {
        String path = "receiverInfo." + requestKey;
        String source = "shipments." + column + " (operator confirmed)";
        if (confirmed && hasText(value)) {
            target.put(requestKey, value);
            pass(validations, path, source);
        } else if (required) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED", path,
                    source, "shipment JD receiver address confirmation",
                    "京东结构化收货地址未经人工确认或缺少必填层级；系统不从自由文本猜测");
        } else {
            validations.add(new Validation(path, "OMITTED", source, "京东未要求时乡镇可留空"));
        }
    }

    private Map<String, Object> cargoPreview(
            Context state,
            Long skuId,
            String orderLine,
            String goodsName,
            String unit,
            BigDecimal quantity,
            String quantitySource,
            int cargoIndex,
            List<Validation> validations,
            List<Blocker> blockers) {
        String base = "cargoInfos[" + cargoIndex + "]";
        Map<String, Object> cargo = new LinkedHashMap<>();
        cargo.put("orderLine", orderLine);
        pass(validations, base + ".orderLine", "order_lines.line_no / order_line_components.component_no");
        cargo.put("goodsName", goodsName);
        pass(validations, base + ".goodsName", "order_lines/product component confirmed snapshot");
        cargo.put("unit", unit);
        pass(validations, base + ".unit", "order_lines/product component confirmed unit snapshot");
        cargo.put("goodsLevel", "100");
        pass(validations, base + ".goodsLevel", "JD salable-good policy (100)");

        // 与原始行投影（ImportRowJdCargoProjectionService jd_cargos）共用同一纯裁决单元
        // JdCargoPlanner：映射解析/单位系数政策/精确正整数 planQuantity 一处裁决，建单
        // 预览/提交与确认明细的数量口径永不漂移；失败码/消息与 blocker 完全一致。
        JdCargoPlanner.Result planned = JdCargoPlanner.plan(
                skuId, orderLine, goodsName, unit, quantity, quantitySource, base,
                skuId == null ? null : state.goodsBySku().get(skuId));
        if (planned instanceof JdCargoPlanner.Failure failure) {
            block(
                    blockers, validations, failure.httpStatus(), failure.code(), failure.path(),
                    failure.source(), failure.correctionTarget(), failure.message());
            return cargo;
        }
        JdCargoPlanner.Cargo plannedCargo = (JdCargoPlanner.Cargo) planned;
        cargo.put("goodsNo", plannedCargo.goodsNo());
        // Internal-only binding removed before the public/submission payload is frozen.
        cargo.put("skuId", plannedCargo.skuId());
        pass(validations, base + ".goodsNo", "provider_skus.provider_sku_code");
        if (hasText(plannedCargo.merchantSkuCode())) {
            cargo.put("erpGoodsNo", plannedCargo.merchantSkuCode());
            pass(validations, base + ".erpGoodsNo", "provider_skus.merchant_sku_code");
        } else {
            validations.add(new Validation(
                    base + ".erpGoodsNo", "OMITTED", "provider_skus.merchant_sku_code", "可选商家 SKU 编码未配置"));
        }
        cargo.put("planQuantity", plannedCargo.planQuantity());
        pass(validations, base + ".planQuantity", quantitySource);
        return cargo;
    }

    /**
     * 京东客户编码按订单客户取值（jd-real-sdk-switch 02）：来自客户档案而非履约方配置，
     * 缺失时给出指向该客户的明确阻塞，不回落到任何默认值。
     */
    private void putCustomerCode(
            Map<String, Object> target,
            Context state,
            List<Validation> validations,
            List<Blocker> blockers) {
        // 青龙业主号（010K 开头）按事业部维护，京东 addSoOrder 裁决（2026-08-18 真实建单
        // 2157：customerInfo.customerCode 必须命中基础资料已维护的青龙业主号）——
        // 优先取履约方配置 customerCode，客户档案 jd_customer_code 仅为历史回退源。
        String path = "customerInfo.customerCode";
        String configCode = configValue(state.config(), CONFIG_CUSTOMER_CODE, null);
        if (hasText(configCode)) {
            target.put("customerCode", configCode);
            pass(validations, path, SOURCE_PROVIDER_CONFIG + CONFIG_CUSTOMER_CODE);
        } else if (hasText(state.jdCustomerCode())) {
            target.put("customerCode", state.jdCustomerCode());
            pass(validations, path, "customers.profile.jd_customer_code (customer archive, deprecated)");
        } else {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING", path,
                    SOURCE_PROVIDER_CONFIG + CONFIG_CUSTOMER_CODE,
                    "fulfillment provider configuration",
                    "履约方配置缺少青龙业主号 customerCode（或客户档案 jd_customer_code 回退值），请先补齐");
        }
    }

    private void putRequiredConfig(
            Map<String, Object> target,
            String requestKey,
            Context state,
            String configKey,
            String path,
            boolean secret,
            List<Validation> validations,
            List<Blocker> blockers) {
        String value = configValue(state.config(), configKey, null);
        String source = SOURCE_PROVIDER_CONFIG + configKey;
        if (hasText(value)) {
            target.put(requestKey, value);
            pass(validations, path, secret ? source + " (secret value hidden)" : source);
        } else {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING", path, source,
                    "fulfillment provider configuration",
                    "履约方配置缺少京东标识 " + configKey + "，请先补齐后再建单");
        }
    }

    /**
     * 乡镇是否必填必须是履约方明确配置的布尔政策；缺失或形状错误时 fail closed，
     * 不能用地址文本、行政区名称或默认值猜测京东当前要求。
     */
    private Boolean requiredTownPolicy(
            Context state, List<Validation> validations, List<Blocker> blockers) {
        String path = "receiverInfo.townPolicy";
        String source = SOURCE_PROVIDER_CONFIG + CONFIG_TOWN_REQUIRED;
        Object raw = state.config().get(CONFIG_TOWN_REQUIRED);
        if (raw instanceof Boolean required) {
            pass(validations, path, source);
            return required;
        }
        String code = raw == null
                ? "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING"
                : "JD_SHIPMENT_OUTBOUND_CONFIG_INVALID";
        block(
                blockers, validations, 422, code, path, source,
                "fulfillment provider address policy",
                raw == null
                        ? "履约方配置缺少显式乡镇必填策略 townRequired；系统不猜测京东要求"
                        : "履约方乡镇必填策略 townRequired 必须是 JSON 布尔值");
        return null;
    }

    /** 供审计/编排单元把预览 blocker 呈现为稳定 JSON 结构。 */
    static Map<String, Object> blockerMap(Blocker blocker) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", blocker.code());
        result.put("path", blocker.path());
        result.put("source", blocker.source());
        result.put("correction_target", blocker.correctionTarget());
        result.put("message", blocker.message());
        return result;
    }

    private void pass(List<Validation> validations, String path, String source) {
        validations.add(new Validation(path, PASS, source, null));
    }

    private void block(
            List<Blocker> blockers,
            List<Validation> validations,
            int httpStatus,
            String code,
            String path,
            String source,
            String correctionTarget,
            String message) {
        validations.add(new Validation(path, BLOCKED, source, message));
        blockers.add(new Blocker(httpStatus, code, path, source, correctionTarget, message));
    }

    private String configValue(Map<String, Object> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /**
     * 以 NO KEY UPDATE 串行同一 Shipment 的预览/提交，但保持与失败留痕 REQUIRES_NEW 所需的
     * 外键 KEY SHARE 兼容，避免同一请求在两个事务间自锁。再锁全部 ShipmentItem 的 Fulfillment/OrderLine。
     */
    private Context lockContext(long shipmentId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD outbound preview lock requires an active database transaction");
        }
        Context value = jdbc.query(
                """
                SELECT s.id, s.shipment_no, s.outbound_order_no, s.shipment_status, s.shipment_sequence,
                       s.order_id, s.fulfillment_provider_id, s.lock_version,
                       o.source_ref,
                       s.receiver_name_snapshot AS receiver_name,
                       s.receiver_phone_snapshot AS receiver_phone,
                       s.receiver_address_snapshot AS receiver_address,
                       s.jd_receiver_province, s.jd_receiver_city, s.jd_receiver_county,
                       s.jd_receiver_town, s.jd_receiver_detail_address,
                       (s.jd_receiver_confirmed_at IS NOT NULL) AS jd_receiver_confirmed,
                       fp.provider_type, fp.config::text AS config,
                       c.customer_code AS order_customer_code,
                       c.customer_name AS order_customer_name,
                       c.profile->>'jd_customer_code' AS jd_customer_code,
                       j.sync_status jd_sync_status, j.request_hash jd_request_hash,
                       j.retry_count jd_retry_count, j.last_error_code jd_last_error_code,
                       j.client_mode jd_client_mode
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                LEFT JOIN app.customers c ON c.id=o.customer_id
                LEFT JOIN app.shipment_jd_outbounds j ON j.shipment_id=s.id
                WHERE s.id=? FOR NO KEY UPDATE OF s, o
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    List<Item> items = jdbc.query(
                            """
                            SELECT si.fulfillment_id, si.instructed_quantity,
                                   ol.id order_line_id, ol.line_type, ol.sku_id, ol.line_no,
                                   ol.product_name_snapshot, ol.processing_stage,
                                   -- 京东货品行的 unit 描述的是 planQuantity 的计量单位，
                                   -- 而 planQuantity 已换算为京东件数，故以内部 SKU 单位为准；
                                   -- 来源表格缺单位列时 ol.unit_snapshot 是占位符，不可直接外发。
                                   COALESCE(sk.unit, ol.unit_snapshot) unit_snapshot
                            FROM app.shipment_items si
                            JOIN app.fulfillments f ON f.id=si.fulfillment_id
                            JOIN app.order_lines ol ON ol.id=f.order_line_id
                            LEFT JOIN app.skus sk ON sk.id=ol.sku_id
                            WHERE si.shipment_id=? ORDER BY si.id
                            FOR UPDATE OF f, ol
                            """,
                            (resultSet, rowNum) -> new Item(
                                    resultSet.getLong("fulfillment_id"),
                                    resultSet.getBigDecimal("instructed_quantity"),
                                    resultSet.getLong("order_line_id"),
                                    resultSet.getString("line_type"),
                                    resultSet.getObject("sku_id", Long.class),
                                    resultSet.getInt("line_no"),
                                    resultSet.getString("product_name_snapshot"),
                                    resultSet.getString("unit_snapshot"),
                                    resultSet.getString("processing_stage")),
                            shipmentId);
                    Map<Long, List<Component>> componentsByOrderLine = loadComponents(shipmentId);
                    Map<Long, JdCargoPlanner.Goods> goodsBySku = loadGoods(
                            rs.getLong("fulfillment_provider_id"), shipmentId);
                    return new Context(
                            rs.getLong("id"), rs.getString("shipment_no"), rs.getString("outbound_order_no"),
                            rs.getString("shipment_status"), rs.getInt("shipment_sequence"),
                            rs.getLong("order_id"), rs.getLong("fulfillment_provider_id"),
                            rs.getLong("lock_version"),
                            rs.getString("source_ref"), rs.getString("receiver_name"),
                            rs.getString("receiver_phone"), rs.getString("receiver_address"),
                            rs.getString("jd_receiver_province"), rs.getString("jd_receiver_city"),
                            rs.getString("jd_receiver_county"), rs.getString("jd_receiver_town"),
                            rs.getString("jd_receiver_detail_address"), rs.getBoolean("jd_receiver_confirmed"),
                            rs.getString("provider_type"), parseJsonMap(rs.getString("config")),
                            rs.getString("order_customer_code"), rs.getString("order_customer_name"),
                            rs.getString("jd_customer_code"),
                            rs.getString("jd_sync_status") == null
                                    ? null
                                    : new JdOutbound(
                                            rs.getString("jd_sync_status"),
                                            rs.getString("jd_request_hash"),
                                            rs.getInt("jd_retry_count"),
                                            rs.getString("jd_last_error_code"),
                                            rs.getString("jd_client_mode")),
                            List.copyOf(items), componentsByOrderLine, goodsBySku);
                },
                shipmentId);
        if (value == null) {
            throw BusinessException.notFound("BUSINESS 发货批次不存在");
        }
        return value;
    }

    /** 一次读取并锁定本 Shipment 的组件行，后续构建期间不再回表查询。 */
    private Map<Long, List<Component>> loadComponents(long shipmentId) {
        List<Component> components = jdbc.query(
                """
                SELECT c.order_line_id, c.component_no, c.sku_id, c.quantity_per_bundle,
                       c.product_name_snapshot,
                       COALESCE(sk.unit, c.unit_snapshot) unit_snapshot
                FROM app.order_line_components c
                JOIN app.order_lines ol ON ol.id=c.order_line_id
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.shipment_items si ON si.fulfillment_id=f.id
                LEFT JOIN app.skus sk ON sk.id=c.sku_id
                WHERE si.shipment_id=?
                ORDER BY c.order_line_id, c.component_no
                FOR SHARE OF c
                """,
                (rs, rowNum) -> new Component(
                        rs.getLong("order_line_id"),
                        rs.getInt("component_no"),
                        rs.getLong("sku_id"),
                        rs.getBigDecimal("quantity_per_bundle"),
                        rs.getString("product_name_snapshot"),
                        rs.getString("unit_snapshot")),
                shipmentId);
        Map<Long, List<Component>> grouped = new HashMap<>();
        for (Component component : components) {
            grouped.computeIfAbsent(component.orderLineId(), ignored -> new ArrayList<>()).add(component);
        }
        grouped.replaceAll((ignored, rows) -> List.copyOf(rows));
        return Map.copyOf(grouped);
    }

    /** 将本 Shipment 引用的履约方映射一次性加载到快照；active 门禁由共享 JdCargoPlanner 统一裁决。 */
    private Map<Long, JdCargoPlanner.Goods> loadGoods(long providerId, long shipmentId) {
        List<GoodsRow> rows = jdbc.query(
                """
                SELECT ps.sku_id, ps.provider_sku_code, ps.merchant_sku_code,
                       ps.external_codes::text AS external_codes, ps.active
                FROM app.provider_skus ps
                WHERE ps.fulfillment_provider_id=?
                  AND ps.sku_id IN (
                      SELECT ol.sku_id
                      FROM app.shipment_items si
                      JOIN app.fulfillments f ON f.id=si.fulfillment_id
                      JOIN app.order_lines ol ON ol.id=f.order_line_id
                      WHERE si.shipment_id=? AND ol.sku_id IS NOT NULL
                      UNION
                      SELECT c.sku_id
                      FROM app.shipment_items si
                      JOIN app.fulfillments f ON f.id=si.fulfillment_id
                      JOIN app.order_line_components c ON c.order_line_id=f.order_line_id
                      WHERE si.shipment_id=?
                  )
                ORDER BY ps.sku_id
                FOR SHARE OF ps
                """,
                (rs, rowNum) -> new GoodsRow(
                        rs.getLong("sku_id"),
                        new JdCargoPlanner.Goods(
                                rs.getString("provider_sku_code"),
                                rs.getString("merchant_sku_code"),
                                parseJsonMap(rs.getString("external_codes")),
                                rs.getBoolean("active"))),
                providerId, shipmentId, shipmentId);
        Map<Long, JdCargoPlanner.Goods> bySku = new HashMap<>();
        for (GoodsRow row : rows) {
            bySku.put(row.skuId(), row.goods());
        }
        return Map.copyOf(bySku);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("履约方配置 JSON 无法解析", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 稳定请求哈希/幂等键散列；对外保持原实现。 */
    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String requiredText(String value) {
        return value == null ? null : value.trim();
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Item(
            long fulfillmentId,
            BigDecimal instructedQuantity,
            long orderLineId,
            String lineType,
            Long skuId,
            int lineNo,
            String productName,
            String unit,
            String processingStage) {
    }

    private record Component(
            long orderLineId,
            int componentNo,
            long skuId,
            BigDecimal quantityPerBundle,
            String productName,
            String unit) {
    }

    private record JdOutbound(
            String syncStatus,
            String requestHash,
            int retryCount,
            String lastErrorCode,
            String clientMode) {
    }

    private record Context(
            long id,
            String shipmentNo,
            String outboundOrderNo,
            String shipmentStatus,
            int shipmentSequence,
            long orderId,
            long providerId,
            long shipmentVersion,
            String sourceRef,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String jdReceiverProvince,
            String jdReceiverCity,
            String jdReceiverCounty,
            String jdReceiverTown,
            String jdReceiverDetailAddress,
            boolean jdReceiverConfirmed,
            String providerType,
            Map<String, Object> config,
            String orderCustomerCode,
            String orderCustomerName,
            String jdCustomerCode,
            JdOutbound jdOutbound,
            List<Item> items,
            Map<Long, List<Component>> componentsByOrderLine,
            Map<Long, JdCargoPlanner.Goods> goodsBySku) {
    }

    /** provider_skus 行（含 sku_id 键）与共享 {@link JdCargoPlanner.Goods} 事实的装载记录。 */
    private record GoodsRow(long skuId, JdCargoPlanner.Goods goods) {
    }
}
