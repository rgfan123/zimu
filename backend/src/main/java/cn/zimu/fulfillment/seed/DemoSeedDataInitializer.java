package cn.zimu.fulfillment.seed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Docker Demo 的确定性业务数据集。
 *
 * <p>只有显式启用且 BUSINESS 订单表为空时才播种。整批写入受 PostgreSQL advisory lock 和单事务保护，
 * 因此重复启动不会覆盖或复制用户数据。30 天分布使用固定随机种子；绝对日期可在验收时固定，默认以
 * Asia/Shanghai 当天作为窗口终点。
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "app.seed", name = "demo-enabled", havingValue = "true")
public class DemoSeedDataInitializer implements ApplicationRunner {

    static final long RANDOM_SEED = 20260812L;
    static final int DAILY_ORDER_COUNT = 30 * 4;
    static final int FRESH_ORDER_COUNT = 3;

    private static final Logger log = LoggerFactory.getLogger(DemoSeedDataInitializer.class);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long ADVISORY_LOCK_KEY = 0x5A494D5553454544L;
    private static final String SHA256 = "9c56cc51b374c3ba189210d5b6d4bf57790d351c96c47c02190ecf1e430635ab";
    private static final List<String> CHANNELS = List.of("CAISHIXIAN", "JUFUBAO", "FEIXIANG", "WECOM");
    private static final List<Scenario> DAILY_SCENARIOS = List.of(
            Scenario.SYNCED,
            Scenario.SHIPPED,
            Scenario.OUT_OF_STOCK,
            Scenario.PROCUREMENT_PENDING,
            Scenario.FULFILLMENT_EXCEPTION,
            Scenario.SYNC_FAILED);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String configuredReferenceDate;

    public DemoSeedDataInitializer(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${app.seed.reference-date:}") String configuredReferenceDate) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.configuredReferenceDate = configuredReferenceDate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, ADVISORY_LOCK_KEY);
        Long existingOrders = jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE data_scope = 'BUSINESS'", Long.class);
        if (existingOrders != null && existingOrders > 0) {
            log.info("demo seed skipped: BUSINESS orders already exist ({})", existingOrders);
            return;
        }

        LocalDate referenceDate = referenceDate();
        SeedCatalog catalog = seedCatalog();
        Map<String, Long> importBatches = seedImportBatches(referenceDate);
        SplittableRandom random = new SplittableRandom(RANDOM_SEED);

        int ordinal = 1;
        for (int dayIndex = 0; dayIndex < 30; dayIndex++) {
            LocalDate businessDate = referenceDate.minusDays(29L - dayIndex);
            for (int channelIndex = 0; channelIndex < CHANNELS.size(); channelIndex++) {
                String channel = CHANNELS.get(channelIndex);
                Scenario scenario = DAILY_SCENARIOS.get((ordinal - 1) % DAILY_SCENARIOS.size());
                SkuSeed sku = scenario.requiresJdInventory()
                        ? catalog.jdSkus().get(random.nextInt(catalog.jdSkus().size()))
                        : catalog.allSkus().get(random.nextInt(catalog.allSkus().size()));
                int quantity = random.nextInt(1, 9);
                OffsetDateTime createdAt = businessDate
                        .atTime(8 + channelIndex * 2, 15 + random.nextInt(30))
                        .atZone(SHANGHAI)
                        .toOffsetDateTime();
                seedOrder(new OrderSeed(
                        ordinal,
                        "SEED-ORD-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + channel,
                        "SEED-" + channel + "-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE),
                        channel,
                        scenario,
                        sku,
                        quantity,
                        createdAt,
                        catalog.customerId(),
                        importBatches.get(channel)));
                ordinal++;
            }
        }

        seedOrder(freshOrder(ordinal++, "SEED-FRESH-RECEIVED", Scenario.RECEIVED, catalog.jdSkus().get(0), referenceDate, catalog));
        seedOrder(freshOrder(ordinal++, "SEED-FRESH-PROCUREMENT", Scenario.PROCUREMENT_PENDING, catalog.jdSkus().get(0), referenceDate, catalog));
        seedOrder(freshOrder(ordinal, "SEED-FRESH-EXCEPTION", Scenario.FULFILLMENT_EXCEPTION, catalog.allSkus().get(1), referenceDate, catalog));
        seedBootstrapAudit(referenceDate);

        log.info(
                "deterministic demo seed ready: referenceDate={}, dailyOrders={}, freshOrders={}, randomSeed={}",
                referenceDate,
                DAILY_ORDER_COUNT,
                FRESH_ORDER_COUNT,
                RANDOM_SEED);
    }

    private OrderSeed freshOrder(
            int ordinal,
            String sourceRef,
            Scenario scenario,
            SkuSeed sku,
            LocalDate referenceDate,
            SeedCatalog catalog) {
        int minute = switch (scenario) {
            case RECEIVED -> 5;
            case PROCUREMENT_PENDING -> 15;
            default -> 25;
        };
        return new OrderSeed(
                ordinal,
                "SEED-ORD-FRESH-" + ordinal,
                sourceRef,
                "WECOM",
                scenario,
                sku,
                scenario == Scenario.PROCUREMENT_PENDING ? 6 : 2,
                referenceDate.atTime(11, minute).atZone(SHANGHAI).toOffsetDateTime(),
                catalog.customerId(),
                null);
    }

    private void seedOrder(OrderSeed seed) {
        Long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind, source_version,
                     source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address, remark, evidence_refs, created_at, updated_at)
                VALUES (?, 'BUSINESS', ?, ?, 'PROVIDED', 'seed-v1', ?, ?, ?, 'MONTHLY', ?,
                        ?, ?, ?, ?, '[]'::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class,
                seed.orderNo(),
                seed.channel(),
                seed.sourceRef(),
                seed.importBatchId(),
                seed.customerId(),
                seed.scenario().orderStatus,
                seed.createdAt(),
                "演示客户-" + seed.channel(),
                "1380000" + String.format("%04d", seed.ordinal() % 10_000),
                "上海市演示路 " + seed.ordinal() + " 号",
                "固定种子场景: " + seed.scenario().name(),
                seed.createdAt(),
                seed.createdAt());

        String exceptionCode = seed.scenario().lineExceptionCode();
        String exceptionReason = exceptionCode == null ? null : seed.scenario().displayName;
        Long lineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                     product_name_snapshot, sku_code_snapshot, specification_snapshot, unit_snapshot,
                     source_quantity_snapshot, mapping_multiplier_snapshot, requested_quantity,
                     processing_stage, exception_code, exception_reason, created_at, updated_at)
                VALUES (?, 1, 'SINGLE', ?, ?, ?, ?, ?, ?, ?, 1.000, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                orderId,
                seed.sku().id(),
                seed.sku().providerId(),
                seed.sku().productName(),
                seed.sku().skuCode(),
                seed.sku().specification(),
                seed.sku().unit(),
                BigDecimal.valueOf(seed.quantity()),
                BigDecimal.valueOf(seed.quantity()),
                seed.scenario().processingStage,
                exceptionCode,
                exceptionReason,
                seed.createdAt(),
                seed.createdAt());

        appendEvent(orderId, 1, "ORDER_RECEIVED", seed.createdAt(), Map.of("source_ref", seed.sourceRef()));
        int eventSequence = 2;
        if (seed.scenario() != Scenario.RECEIVED) {
            appendEvent(orderId, eventSequence++, "SKU_MAPPED", seed.createdAt().plusMinutes(2), Map.of("line_count", 1));
        }

        Long fulfillmentId = null;
        if (seed.scenario() != Scenario.RECEIVED) {
            String fulfillmentExceptionCode = seed.scenario() == Scenario.FULFILLMENT_EXCEPTION
                    ? "PROVIDER_REJECTED"
                    : null;
            fulfillmentId = jdbc.queryForObject(
                    """
                    INSERT INTO app.fulfillments
                        (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity,
                         exception_code, exception_reason, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    "SEED-FUL-" + seed.ordinal(),
                    lineId,
                    seed.sku().providerId(),
                    BigDecimal.valueOf(seed.quantity()),
                    fulfillmentExceptionCode,
                    fulfillmentExceptionCode == null ? null : "履约方拒绝演示订单",
                    seed.createdAt().plusMinutes(5),
                    seed.createdAt().plusMinutes(5));
        }

        if (seed.scenario().hasShipment()) {
            ShipmentSeed shipment = seedShipment(seed, orderId, fulfillmentId);
            appendEvent(orderId, eventSequence++, "SHIPMENT_CREATED", shipment.shippedAt(), Map.of("shipment_no", shipment.shipmentNo()));
            if (seed.scenario().hasTracking()) {
                seedTracking(seed, shipment);
                appendEvent(orderId, eventSequence++, "TRACKING_RECEIVED", shipment.shippedAt().plusHours(1), Map.of("shipment_no", shipment.shipmentNo()));
            }
            if (seed.scenario() == Scenario.SYNCED) {
                appendEvent(orderId, eventSequence, "SOURCE_SYNCED", shipment.shippedAt().plusHours(2), Map.of("source_channel", seed.channel()));
            }
        } else if (seed.scenario().requiresProcurement()) {
            Long ticketId = seedProcurement(seed, fulfillmentId);
            appendEvent(orderId, eventSequence++, "PROCUREMENT_REQUESTED", seed.createdAt().plusMinutes(15), Map.of("ticket_id", ticketId));
            seedAlert(seed, orderId, lineId, fulfillmentId, "OUT_OF_STOCK", "库存不足，等待采购处理", "YELLOW");
        } else if (seed.scenario() == Scenario.FULFILLMENT_EXCEPTION) {
            appendEvent(orderId, eventSequence, "MANUAL_INTERVENTION_REQUIRED", seed.createdAt().plusMinutes(15), Map.of("reason", "PROVIDER_REJECTED"));
            seedReview(seed, orderId, lineId, fulfillmentId, null, "FULFILLMENT_EXCEPTION");
            seedAlert(seed, orderId, lineId, fulfillmentId, "FULFILLMENT_EXCEPTION", "履约异常，需要人工介入", "RED");
        }
        if (seed.scenario() == Scenario.SYNC_FAILED) {
            seedReview(seed, orderId, lineId, fulfillmentId, null, "SYNC_FAILED");
        }

        jdbc.update(
                """
                INSERT INTO app.order_versions
                    (order_id, version_no, source_version, change_reason, triggered_by, snapshot, created_at)
                VALUES (?, 1, 'seed-v1', '确定性演示数据初始化', 'seed-runner', ?::jsonb, ?)
                """,
                orderId,
                json(Map.of(
                        "order_no", seed.orderNo(),
                        "source_channel", seed.channel(),
                        "order_status", seed.scenario().orderStatus,
                        "scenario", seed.scenario().name())),
                seed.createdAt());
    }

    private ShipmentSeed seedShipment(OrderSeed seed, Long orderId, Long fulfillmentId) {
        OffsetDateTime shippedAt = seed.createdAt().plusHours(2);
        String shipmentNo = "SEED-SHIP-" + seed.ordinal();
        String outboundOrderNo = seed.createdAt().format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%06d", seed.ordinal());
        Long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, outbound_order_no, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at, created_at, updated_at)
                SELECT ?, o.id, ?, ?, 1, o.receiver_name, o.receiver_phone, o.receiver_address,
                       'SHIPPED', ?, ?, ?
                FROM app.orders o WHERE o.id = ?
                RETURNING id
                """,
                Long.class,
                shipmentNo,
                seed.sku().providerId(),
                outboundOrderNo,
                shippedAt,
                seed.createdAt().plusHours(1),
                shippedAt,
                orderId);
        jdbc.update(
                """
                INSERT INTO app.shipment_items
                    (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                shipmentId,
                fulfillmentId,
                BigDecimal.valueOf(seed.quantity()),
                BigDecimal.valueOf(seed.quantity()),
                seed.createdAt().plusHours(1),
                shippedAt);
        jdbc.update(
                "UPDATE app.fulfillments SET outcome='FULLY_FULFILLED', updated_at=? WHERE id=?",
                shippedAt,
                fulfillmentId);
        return new ShipmentSeed(shipmentId, shipmentNo, shippedAt);
    }

    private void seedTracking(OrderSeed seed, ShipmentSeed shipment) {
        String trackingNumber = "JD" + seed.createdAt().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + String.format("%06d", seed.ordinal());
        OffsetDateTime receivedAt = shipment.shippedAt().plusHours(1);
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number,
                     received_at, raw_payload, created_at)
                VALUES (?, 'JD', '京东物流', ?, ?, '{"source":"deterministic-seed"}'::jsonb, ?)
                """,
                shipment.id(),
                trackingNumber,
                receivedAt,
                receivedAt);
        String syncStatus = seed.scenario() == Scenario.SYNCED ? "SYNCED" : "SYNC_FAILED";
        jdbc.update(
                """
                INSERT INTO app.shipment_syncs
                    (shipment_id, source_channel, sync_status, attempt_count,
                     last_error_code, last_error_message, synced_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                shipment.id(),
                seed.channel(),
                syncStatus,
                syncStatus.equals("SYNCED") ? 1 : 3,
                syncStatus.equals("SYNCED") ? null : "SOURCE_TIMEOUT",
                syncStatus.equals("SYNCED") ? null : "来源平台回传超时",
                syncStatus.equals("SYNCED") ? receivedAt.plusHours(1) : null,
                receivedAt,
                receivedAt.plusHours(1));
    }

    private Long seedProcurement(OrderSeed seed, Long fulfillmentId) {
        Long ticketId = jdbc.queryForObject(
                """
                INSERT INTO app.procurement_tickets
                    (ticket_no, fulfillment_id, procurement_status, priority, delivery_address,
                     required_delivery_time, remark, created_by, created_at, updated_at)
                VALUES (?, ?, 'PENDING', ?, '上海市演示采购收货点', ?, ?, 'seed-runner', ?, ?)
                RETURNING id
                """,
                Long.class,
                "SEED-PT-" + seed.ordinal(),
                fulfillmentId,
                seed.scenario() == Scenario.PROCUREMENT_PENDING ? "HIGH" : "NORMAL",
                seed.createdAt().plusDays(2),
                seed.scenario().displayName,
                seed.createdAt().plusMinutes(10),
                seed.createdAt().plusMinutes(10));
        jdbc.update(
                """
                INSERT INTO app.procurement_ticket_items
                    (procurement_ticket_id, sku_id, requested_quantity, unit_snapshot, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                ticketId,
                seed.sku().id(),
                BigDecimal.valueOf(seed.quantity()),
                seed.sku().unit(),
                seed.createdAt().plusMinutes(10),
                seed.createdAt().plusMinutes(10));
        return ticketId;
    }

    private void seedReview(
            OrderSeed seed,
            Long orderId,
            Long lineId,
            Long fulfillmentId,
            Long shipmentId,
            String reasonCode) {
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, order_line_id, fulfillment_id, shipment_id, detail, created_at, updated_at)
                VALUES (?, 'OPERATION_REVIEW', 'OPEN', '履约运营', ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                "SEED-RC-" + seed.ordinal(),
                reasonCode,
                orderId,
                lineId,
                fulfillmentId,
                shipmentId,
                json(Map.of("scenario", seed.scenario().name(), "message", seed.scenario().displayName)),
                seed.createdAt().plusMinutes(20),
                seed.createdAt().plusMinutes(20));
    }

    private void seedAlert(
            OrderSeed seed,
            Long orderId,
            Long lineId,
            Long fulfillmentId,
            String type,
            String message,
            String severity) {
        jdbc.update(
                """
                INSERT INTO app.operational_alerts
                    (alert_no, alert_type, severity, status, order_id, order_line_id, fulfillment_id,
                     message, detail, created_at, updated_at)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                "SEED-ALERT-" + seed.ordinal(),
                type,
                severity,
                orderId,
                lineId,
                fulfillmentId,
                message,
                json(Map.of("scenario", seed.scenario().name())),
                seed.createdAt().plusMinutes(20),
                seed.createdAt().plusMinutes(20));
    }

    private void appendEvent(
            Long orderId, int sequenceNo, String eventType, OffsetDateTime createdAt, Map<String, Object> payload) {
        jdbc.update(
                """
                INSERT INTO app.order_events
                    (order_id, sequence_no, event_type_code, data_scope, payload, operator, created_at)
                VALUES (?, ?, ?, 'BUSINESS', ?::jsonb, 'seed-runner', ?)
                """,
                orderId,
                sequenceNo,
                eventType,
                json(payload),
                createdAt);
    }

    private SeedCatalog seedCatalog() {
        Long customerId = jdbc.queryForObject(
                "SELECT id FROM app.customers WHERE customer_code='CUST-WECOM-0001'", Long.class);
        Long jdProviderId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        Long tpProviderId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='TP'", Long.class);

        long meatCategory = ensureCategory("CAT-MEAT", "肉类");
        long dairyCategory = ensureCategory("CAT-DAIRY", "乳制品");
        long vegetableCategory = ensureCategory("CAT-VEGETABLE", "蔬菜");
        long lambProduct = ensureProduct("子牧羊小腿", meatCategory, "500g/盒");
        long yogurtProduct = ensureProduct("草原酸奶", dairyCategory, "12杯/箱");
        long vegetableProduct = ensureProduct("有机时蔬组合", vegetableCategory, "5kg/箱");

        List<SkuSeed> jdSkus = List.of(
                ensureSku(lambProduct, "子牧羊小腿", jdProviderId, "500g/盒", "盒"),
                ensureSku(vegetableProduct, "有机时蔬组合", jdProviderId, "5kg/箱", "箱"));
        List<SkuSeed> allSkus = new ArrayList<>(jdSkus);
        allSkus.add(ensureSku(lambProduct, "子牧羊小腿", tpProviderId, "标准箱", "箱"));
        allSkus.add(ensureSku(yogurtProduct, "草原酸奶", tpProviderId, "12杯/箱", "箱"));
        return new SeedCatalog(customerId, jdSkus, List.copyOf(allSkus));
    }

    private long ensureCategory(String code, String name) {
        jdbc.update(
                "INSERT INTO app.categories(category_code, category_name) VALUES (?, ?) ON CONFLICT (category_code) DO NOTHING",
                code,
                name);
        return jdbc.queryForObject("SELECT id FROM app.categories WHERE category_code=?", Long.class, code);
    }

    private long ensureProduct(String name, long categoryId, String description) {
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM app.products WHERE product_name=? AND category_id=? ORDER BY id LIMIT 1",
                Long.class,
                name,
                categoryId);
        if (!existing.isEmpty()) return existing.getFirst();
        return jdbc.queryForObject(
                """
                INSERT INTO app.products(product_code, product_name, category_id, description)
                VALUES ('PROD-' || lpad(nextval('app.product_code_seq')::text, 6, '0'), ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                name,
                categoryId,
                description);
    }

    private SkuSeed ensureSku(long productId, String productName, long providerId, String specification, String unit) {
        List<SkuSeed> existing = jdbc.query(
                """
                SELECT id, sku_code FROM app.skus
                WHERE product_id=? AND fulfillment_provider_id=? AND specification=? AND unit=?
                """,
                (rs, rowNum) -> new SkuSeed(
                        rs.getLong("id"), productName, rs.getString("sku_code"), specification, unit, providerId),
                productId,
                providerId,
                specification,
                unit);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus(product_id, fulfillment_provider_id, specification, unit)
                VALUES (?, ?, ?, ?) RETURNING id, sku_code
                """,
                (rs, rowNum) -> new SkuSeed(
                        rs.getLong("id"), productName, rs.getString("sku_code"), specification, unit, providerId),
                productId,
                providerId,
                specification,
                unit);
    }

    private Map<String, Long> seedImportBatches(LocalDate referenceDate) {
        Map<String, Long> result = new java.util.HashMap<>();
        OffsetDateTime receivedAt = referenceDate.minusDays(29).atStartOfDay(SHANGHAI).toOffsetDateTime();
        for (String channel : CHANNELS) {
            if (channel.equals("WECOM")) {
                continue;
            }
            Long id = jdbc.queryForObject(
                    """
                    INSERT INTO app.import_batches
                        (batch_no, batch_type, source_channel, template_family, template_version,
                         template_fingerprint, original_file_name, content_sha256, file_ref,
                         status, uploaded_by, received_at, processed_at, created_at)
                    VALUES (?, 'SOURCE_ORDER', ?, ?, 'seed-v1', ?, ?, ?, ?, 'COMPLETED',
                            'seed-runner', ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    "SEED-IMPORT-" + channel,
                    channel,
                    channel.toLowerCase() + "-seed",
                    "seed-fingerprint-" + channel.toLowerCase(),
                    channel.toLowerCase() + "-30d-seed.xlsx",
                    SHA256,
                    "seed://" + channel.toLowerCase() + "/30d",
                    receivedAt,
                    receivedAt.plusMinutes(1),
                    receivedAt);
            result.put(channel, id);
        }
        return result;
    }

    private void seedBootstrapAudit(LocalDate referenceDate) {
        OffsetDateTime createdAt = referenceDate.atTime(11, 59).atZone(SHANGHAI).toOffsetDateTime();
        jdbc.update(
                """
                INSERT INTO app.audit_logs
                    (data_scope, request_id, trace_id, operator, actor_type, service, operation,
                     response_payload, http_status, business_code, latency_ms, created_at)
                VALUES ('BUSINESS', 'seed-demo-dataset-v1', 'seed-demo-dataset-v1', 'seed-runner',
                        'SYSTEM', 'seed', 'seed.demo-dataset', ?::jsonb, 201, 'DEMO_DATASET_SEEDED', 0, ?)
                """,
                json(Map.of(
                        "reference_date", referenceDate.toString(),
                        "random_seed", RANDOM_SEED,
                        "daily_orders", DAILY_ORDER_COUNT,
                        "fresh_orders", FRESH_ORDER_COUNT)),
                createdAt);
    }

    private LocalDate referenceDate() {
        return configuredReferenceDate == null || configuredReferenceDate.isBlank()
                ? LocalDate.now(SHANGHAI)
                : LocalDate.parse(configuredReferenceDate);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("demo seed JSON serialization failed", ex);
        }
    }

    private enum Scenario {
        RECEIVED("RECEIVED", "READY_TO_EXPORT", "刚进入系统"),
        SYNCED("SYNCED", "COMPLETED", "已发货并完成来源回传"),
        SHIPPED("SHIPPED", "WAITING_PROVIDER", "已发货，等待运单"),
        OUT_OF_STOCK("OUT_OF_STOCK", "PROCUREMENT_IN_PROGRESS", "库存不足"),
        PROCUREMENT_PENDING("PROCUREMENT_PENDING", "PROCUREMENT_IN_PROGRESS", "采购待处理"),
        FULFILLMENT_EXCEPTION("FULFILLMENT_EXCEPTION", "EXCEPTION", "履约异常"),
        SYNC_FAILED("SYNC_FAILED", "EXCEPTION", "来源回传失败");

        private final String orderStatus;
        private final String processingStage;
        private final String displayName;

        Scenario(String orderStatus, String processingStage, String displayName) {
            this.orderStatus = orderStatus;
            this.processingStage = processingStage;
            this.displayName = displayName;
        }

        boolean requiresJdInventory() {
            return this == OUT_OF_STOCK || this == PROCUREMENT_PENDING;
        }

        boolean requiresProcurement() {
            return this == OUT_OF_STOCK || this == PROCUREMENT_PENDING;
        }

        boolean hasShipment() {
            return this == SYNCED || this == SHIPPED || this == SYNC_FAILED;
        }

        boolean hasTracking() {
            return this == SYNCED || this == SYNC_FAILED;
        }

        String lineExceptionCode() {
            return switch (this) {
                case FULFILLMENT_EXCEPTION -> "PROVIDER_REJECTED";
                case SYNC_FAILED -> "SOURCE_SYNC_FAILED";
                default -> null;
            };
        }
    }

    private record SeedCatalog(Long customerId, List<SkuSeed> jdSkus, List<SkuSeed> allSkus) {}

    private record SkuSeed(
            long id, String productName, String skuCode, String specification, String unit, long providerId) {}

    private record OrderSeed(
            int ordinal,
            String orderNo,
            String sourceRef,
            String channel,
            Scenario scenario,
            SkuSeed sku,
            int quantity,
            OffsetDateTime createdAt,
            Long customerId,
            Long importBatchId) {}

    private record ShipmentSeed(Long id, String shipmentNo, OffsetDateTime shippedAt) {}
}
