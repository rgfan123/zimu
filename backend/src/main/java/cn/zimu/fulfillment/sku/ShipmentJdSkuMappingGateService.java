package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.JdStockUnitConverter;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 以 Shipment 为业务边界的京东 SKU 映射门禁。 */
@Service
public class ShipmentJdSkuMappingGateService {

    private static final String SCOPE = "shipment.jd_sku_mapping.check";
    private static final String BLOCK_REASON = "JD_SKU_MAPPING_BLOCKED";
    private static final String EVENT_TYPE = "JD_SKU_MAPPING_CHECKED";

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final JdGoodsReadOnlyVerifier goodsVerifier;
    private final ReviewCaseRepository reviewCases;
    private final OrderEventService events;
    private final ObjectMapper objectMapper;

    public ShipmentJdSkuMappingGateService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuditLogService audits,
            JdGoodsReadOnlyVerifier goodsVerifier,
            ReviewCaseRepository reviewCases,
            OrderEventService events,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audits = audits;
        this.goodsVerifier = goodsVerifier;
        this.reviewCases = reviewCases;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * 门禁分为无锁快照、无事务京东只读查询、事务内重锁持久化三阶段。
     * 持久化前必须验证整个业务快照未变，不会用旧的京东事实写新状态。
     */
    public IdempotentResult<Map<String, Object>> check(
            long shipmentId, String idempotencyKey, CommandContext context) {
        PreparedGate prepared = loadGate(shipmentId, false);
        validateGate(prepared);
        String preparedFingerprint = localFingerprint(prepared);
        return idempotency.executeWithReadOnlyExternalWork(
                SCOPE,
                idempotencyKey,
                Map.of(
                        "shipment_id", shipmentId,
                        "local_gate_fingerprint", preparedFingerprint),
                200,
                () -> queryRemoteFacts(prepared),
                remoteFacts -> persist(prepared, remoteFacts, context));
    }

    private Map<String, JdGoodsReadOnlyVerifier.Verification> queryRemoteFacts(PreparedGate prepared) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD goods verification must start outside a database transaction");
        }
        Map<String, JdGoodsReadOnlyVerifier.Verification> facts = new LinkedHashMap<>();
        for (PreparedItem item : prepared.items()) {
            for (PreparedSku sku : item.skus()) {
                MappingRow mapping = sku.mapping();
                if (mapping != null && hasText(mapping.goodsNo()) && !facts.containsKey(mapping.goodsNo())) {
                    facts.put(mapping.goodsNo(), goodsVerifier.verify(mapping.goodsNo()));
                }
            }
        }
        return facts;
    }

    private Map<String, Object> persist(
            PreparedGate prepared,
            Map<String, JdGoodsReadOnlyVerifier.Verification> remoteFacts,
            CommandContext context) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD SKU mapping gate persistence requires a database transaction");
        }
        PreparedGate current = loadGate(prepared.shipment().id(), true);
        validateGate(current);
        if (!prepared.equals(current)) {
            throw BusinessException.conflict(
                    "JD_SKU_MAPPING_CHANGED_DURING_CHECK",
                    "发货批次或 SKU 映射在京东查询期间已变更，未写入本次结果，请重试");
        }
        return evaluateAndPersist(current, remoteFacts, context);
    }

    private Map<String, Object> evaluateAndPersist(
            PreparedGate gate,
            Map<String, JdGoodsReadOnlyVerifier.Verification> remoteFacts,
            CommandContext context) {
        ShipmentContext shipment = gate.shipment();
        String checkRunNo = "JD-SKU-CHK-" + token();
        List<Map<String, Object>> itemResults = new ArrayList<>();
        List<Map<String, Object>> blockingItems = new ArrayList<>();
        int checkedMappings = 0;
        int blockingIssues = 0;
        int warnings = 0;
        for (PreparedItem item : gate.items()) {
            List<Map<String, Object>> checks = new ArrayList<>();
            for (PreparedSku sku : item.skus()) {
                JdGoodsReadOnlyVerifier.Verification facts = sku.mapping() == null
                        ? null
                        : remoteFacts.get(sku.mapping().goodsNo());
                Map<String, Object> check = checkSku(shipment.providerId(), sku, facts);
                checks.add(check);
                checkedMappings++;
                List<Map<String, Object>> issues = maps(check.get("issues"));
                List<Map<String, Object>> checkWarnings = maps(check.get("warnings"));
                blockingIssues += issues.size();
                warnings += checkWarnings.size();
                if (!issues.isEmpty()) {
                    blockingItems.add(affectedItem(item.item(), sku.subject(), sku.mapping(), issues));
                }
            }
            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("shipment_item_id", String.valueOf(item.item().shipmentItemId()));
            itemResult.put("fulfillment_id", String.valueOf(item.item().fulfillmentId()));
            itemResult.put("order_line_id", String.valueOf(item.item().orderLineId()));
            itemResult.put("line_no", item.item().lineNo());
            itemResult.put("sku_checks", checks);
            itemResults.add(itemResult);
        }

        boolean passed = blockingIssues == 0;
        ReviewCase reviewCase = reconcileBlockingCase(
                shipment, checkRunNo, blockingItems, passed, context.operator());
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("shipment_id", String.valueOf(shipment.id()));
        eventPayload.put("check_run_no", checkRunNo);
        eventPayload.put("gate_status", passed ? "PASSED" : "BLOCKED");
        eventPayload.put("checked_mapping_count", checkedMappings);
        eventPayload.put("blocking_issue_count", blockingIssues);
        eventPayload.put("warning_count", warnings);
        if (reviewCase != null) eventPayload.put("review_case_id", String.valueOf(reviewCase.getId()));
        events.append(
                shipment.orderId(), EVENT_TYPE, null, null, shipment.id(), null,
                DataScope.BUSINESS, eventPayload, context.operator());

        Map<String, Object> response = new LinkedHashMap<>(eventPayload);
        response.put("local_gate_fingerprint", localFingerprint(gate));
        response.put("shipment_items", itemResults);
        response.put("affected_shipment_items", blockingItems);
        response.put("maintenance_action", gateMaintenance(shipment.id()));
        if (reviewCase != null) {
            response.put("review_case", Map.of(
                    "id", String.valueOf(reviewCase.getId()),
                    "case_no", reviewCase.getCaseNo(),
                    "status", reviewCase.getStatus().name(),
                    "reason_code", reviewCase.getReasonCode()));
        }
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(shipment.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("ShipmentJdSkuMappingGateService").operation(SCOPE)
                .requestPayload(Map.of("shipment_id", String.valueOf(shipment.id())))
                .responsePayload(Map.of(
                        "check_run_no", checkRunNo,
                        "gate_status", passed ? "PASSED" : "BLOCKED",
                        "checked_mapping_count", checkedMappings,
                        "blocking_issue_count", blockingIssues,
                        "warning_count", warnings))
                .httpStatus(200)
                .businessCode(passed ? "JD_SKU_MAPPING_GATE_PASSED" : "JD_SKU_MAPPING_GATE_BLOCKED"));
        return response;
    }

    /**
     * 在调用方的完成事务中重锁并核对 SKU/ProviderSku 本地门禁输入。该指纹只用于并发
     * fencing，不代表京东远端商品事实仍然有效，也不能作为客户端 capability。
     */
    public void requireLocalFingerprintCurrent(long shipmentId, String expectedFingerprint) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD SKU local gate fingerprint check requires a database transaction");
        }
        PreparedGate current = loadGate(shipmentId, true);
        validateGate(current);
        if (!localFingerprint(current).equals(expectedFingerprint)) {
            throw BusinessException.conflict(
                    "JD_STOCK_LOCAL_GATE_CHANGED_DURING_CHECK",
                    "SKU 或京东商品映射在库存查询期间已变更，本次结果未写入，请重新执行门禁");
        }
    }

    private String localFingerprint(PreparedGate gate) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("shipment_id", gate.shipment().id());
        root.put("shipment_version", gate.shipment().shipmentVersion());
        root.put("provider_id", gate.shipment().providerId());
        root.put("provider_type", gate.shipment().providerType());
        root.put("shipment_status", gate.shipment().shipmentStatus());
        List<Map<String, Object>> items = new ArrayList<>();
        for (PreparedItem preparedItem : gate.items()) {
            ShipmentItemRow item = preparedItem.item();
            Map<String, Object> itemValue = new LinkedHashMap<>();
            itemValue.put("shipment_item_id", item.shipmentItemId());
            itemValue.put("fulfillment_id", item.fulfillmentId());
            itemValue.put("instructed_quantity", canonicalDecimal(item.instructedQuantity()));
            itemValue.put("shipment_item_updated_at", item.updatedAt().toString());
            itemValue.put("order_line_id", item.orderLineId());
            itemValue.put("line_no", item.lineNo());
            itemValue.put("line_type", item.lineType());
            itemValue.put("order_line_updated_at", item.orderLineUpdatedAt().toString());
            List<Map<String, Object>> skus = new ArrayList<>();
            for (PreparedSku preparedSku : preparedItem.skus()) {
                SkuSubject subject = preparedSku.subject();
                MappingRow mapping = preparedSku.mapping();
                Map<String, Object> sku = new LinkedHashMap<>();
                sku.put("component_id", subject.componentId());
                sku.put("component_no", subject.componentNo());
                sku.put("sku_id", subject.skuId());
                sku.put("sku_active", subject.skuActive());
                sku.put("sku_version", subject.skuVersion());
                sku.put("unit", subject.unit());
                sku.put("quantity", canonicalDecimal(subject.quantity()));
                if (mapping == null) {
                    sku.put("mapping", null);
                } else {
                    Map<String, Object> mappingValue = new LinkedHashMap<>();
                    mappingValue.put("id", mapping.id());
                    mappingValue.put("active", mapping.active());
                    mappingValue.put("version", mapping.mappingVersion());
                    mappingValue.put("goods_no", mapping.goodsNo());
                    mappingValue.put("merchant_sku_code", mapping.merchantSkuCode());
                    mappingValue.put(
                            "jd_pieces_per_unit",
                            text(mapping.externalCodes().get(JdStockUnitConverter.FACTOR_CONFIG_KEY)));
                    sku.put("mapping", mappingValue);
                }
                skus.add(sku);
            }
            itemValue.put("skus", skus);
            items.add(itemValue);
        }
        root.put("items", items);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(root)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot fingerprint JD SKU local gate", exception);
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private PreparedGate loadGate(long shipmentId, boolean lock) {
        ShipmentContext shipment = loadShipment(shipmentId, lock);
        List<ItemSeed> seeds = loadItemSeeds(shipmentId, lock);
        List<PreparedItem> items = new ArrayList<>();
        for (ItemSeed seed : seeds) {
            ShipmentItemRow item = seed.item();
            List<SkuSubject> subjects = "CUSTOM_BUNDLE".equals(item.lineType())
                    ? loadComponentSubjects(item, lock)
                    : List.of(singleSubject(item, seed.skuId(), lock));
            List<PreparedSku> skus = new ArrayList<>();
            for (SkuSubject subject : subjects) {
                MappingRow mapping = subject.skuId() == null
                        ? null
                        : loadMapping(shipment.providerId(), subject.skuId(), lock);
                skus.add(new PreparedSku(subject, mapping));
            }
            items.add(new PreparedItem(item, List.copyOf(skus)));
        }
        return new PreparedGate(shipment, List.copyOf(items));
    }

    private ShipmentContext loadShipment(long shipmentId, boolean lock) {
        String sql = """
                SELECT s.id, s.order_id, s.fulfillment_provider_id, fp.provider_type,
                       s.shipment_status, s.lock_version
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE s.id=?
                """ + (lock ? " FOR UPDATE OF s" : "");
        ShipmentContext shipment = jdbc.query(sql, resultSet -> {
            if (!resultSet.next()) return null;
            return new ShipmentContext(
                    resultSet.getLong("id"),
                    resultSet.getLong("order_id"),
                    resultSet.getLong("fulfillment_provider_id"),
                    resultSet.getString("provider_type"),
                    resultSet.getString("shipment_status"),
                    resultSet.getLong("lock_version"));
        }, shipmentId);
        if (shipment == null) throw BusinessException.notFound("BUSINESS 发货批次不存在");
        return shipment;
    }

    private List<ItemSeed> loadItemSeeds(long shipmentId, boolean lock) {
        // COALESCE(sk.unit, ol.unit_snapshot)：与 ShipmentJdOutboundPreparer.lockContext() 的
        // 货品单位口径同源（同一文件的注释原文：「来源表格缺单位列时 ol.unit_snapshot 是占位符，
        // 不可直接外发」）。此前本查询直接读裸 unit_snapshot，2026-08-27 review_cases#41/#42
        // 实测复算命中：order_line 5 的 unit_snapshot 是占位符「来源数量单位」，SKU 48 的真实
        // 单位其实是「件」，门禁却因为看到「来源数量单位」≠「件」而误判为非件单位、
        // 要求本不需要的显式京东件数换算，把一条映射完好的行错判成 UNIT_CONVERSION_MISSING。
        String sql = """
                SELECT si.id shipment_item_id, si.fulfillment_id, si.instructed_quantity, si.updated_at,
                       ol.id order_line_id, ol.line_no, ol.line_type, ol.sku_id,
                       ol.sku_code_snapshot, ol.product_name_snapshot,
                       COALESCE(sk.unit, ol.unit_snapshot) unit_snapshot,
                       ol.updated_at order_line_updated_at
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                LEFT JOIN app.skus sk ON sk.id=ol.sku_id
                WHERE si.shipment_id=?
                ORDER BY si.id
                """ + (lock ? " FOR UPDATE OF si, ol" : "");
        return jdbc.query(
                sql,
                (rs, rowNum) -> new ItemSeed(
                        new ShipmentItemRow(
                                rs.getLong("shipment_item_id"),
                                rs.getLong("fulfillment_id"),
                                rs.getBigDecimal("instructed_quantity"),
                                rs.getTimestamp("updated_at").toInstant(),
                                rs.getLong("order_line_id"),
                                rs.getInt("line_no"),
                                rs.getString("line_type"),
                                rs.getString("sku_code_snapshot"),
                                rs.getString("product_name_snapshot"),
                                rs.getString("unit_snapshot"),
                                rs.getTimestamp("order_line_updated_at").toInstant()),
                        rs.getObject("sku_id", Long.class)),
                shipmentId);
    }

    private SkuSubject singleSubject(ShipmentItemRow item, Long skuId, boolean lock) {
        SkuState sku = loadSkuState(skuId, lock);
        return new SkuSubject(
                skuId,
                item.skuCode(),
                item.productName(),
                item.unit(),
                item.instructedQuantity(),
                null,
                null,
                sku != null && sku.active(),
                sku == null ? null : sku.lockVersion());
    }

    private SkuState loadSkuState(Long skuId, boolean lock) {
        if (skuId == null) return null;
        String sql = "SELECT active, lock_version FROM app.skus WHERE id=?"
                + (lock ? " FOR UPDATE" : "");
        List<SkuState> states = jdbc.query(
                sql,
                (rs, rowNum) -> new SkuState(rs.getBoolean("active"), rs.getLong("lock_version")),
                skuId);
        return states.isEmpty() ? null : states.getFirst();
    }

    private List<SkuSubject> loadComponentSubjects(ShipmentItemRow item, boolean lock) {
        // 同 loadItemSeeds 的 COALESCE 修复：礼包组件单位一样可能只是来源占位符。
        String sql = """
                SELECT c.id component_id, c.component_no, c.sku_id, sku.sku_code,
                       c.product_name_snapshot, COALESCE(sku.unit, c.unit_snapshot) unit_snapshot,
                       c.quantity_per_bundle, sku.active, sku.lock_version
                FROM app.order_line_components c
                JOIN app.skus sku ON sku.id=c.sku_id
                WHERE c.order_line_id=?
                ORDER BY c.component_no
                """ + (lock ? " FOR UPDATE OF c, sku" : "");
        return jdbc.query(
                sql,
                (rs, rowNum) -> new SkuSubject(
                        rs.getLong("sku_id"),
                        rs.getString("sku_code"),
                        rs.getString("product_name_snapshot"),
                        rs.getString("unit_snapshot"),
                        item.instructedQuantity().multiply(rs.getBigDecimal("quantity_per_bundle")),
                        rs.getLong("component_id"),
                        rs.getInt("component_no"),
                        rs.getBoolean("active"),
                        rs.getLong("lock_version")),
                item.orderLineId());
    }

    private MappingRow loadMapping(long providerId, long skuId, boolean lock) {
        String sql = """
                SELECT id, provider_sku_code, merchant_sku_code, external_codes::text external_codes,
                       active, lock_version
                FROM app.provider_skus
                WHERE fulfillment_provider_id=? AND sku_id=?
                """ + (lock ? " FOR UPDATE" : "");
        List<MappingRow> rows = jdbc.query(
                sql,
                (rs, rowNum) -> new MappingRow(
                        rs.getLong("id"),
                        rs.getString("provider_sku_code"),
                        rs.getString("merchant_sku_code"),
                        jsonMap(rs.getString("external_codes")),
                        rs.getBoolean("active"),
                        rs.getLong("lock_version")),
                providerId,
                skuId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateGate(PreparedGate gate) {
        if (!"JD_WAREHOUSE".equals(gate.shipment().providerType())) {
            throw BusinessException.unprocessable(
                    "JD_SKU_MAPPING_PROVIDER_UNSUPPORTED", "仅京东云仓 Shipment 可执行京东 SKU 映射门禁");
        }
        if (gate.items().isEmpty()) {
            throw BusinessException.unprocessable(
                    "JD_SKU_MAPPING_SHIPMENT_EMPTY", "Shipment 没有可核对的 ShipmentItem");
        }
    }

    private Map<String, Object> checkSku(
            long providerId,
            PreparedSku prepared,
            JdGoodsReadOnlyVerifier.Verification facts) {
        SkuSubject subject = prepared.subject();
        MappingRow mapping = prepared.mapping();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sku_id", subject.skuId() == null ? null : String.valueOf(subject.skuId()));
        result.put("sku_code", subject.skuCode());
        result.put("product_name", subject.productName());
        result.put("unit", subject.unit());
        result.put("source_quantity", subject.quantity() == null
                ? null
                : subject.quantity().stripTrailingZeros().toPlainString());
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        result.put("maintenance_action", mappingMaintenance(providerId, subject.skuId(), mapping));
        if (subject.skuId() == null) {
            issues.add(issue("INTERNAL_SKU_MISSING", "ShipmentItem 未关联内部 SKU"));
            return finishCheck(result, issues, warnings);
        }
        if (!subject.skuActive()) issues.add(issue("INTERNAL_SKU_INACTIVE", "内部 SKU 已停用"));
        if (mapping == null) {
            issues.add(issue("MAPPING_MISSING", "内部 SKU 未配置京东履约方商品映射", "provider_sku_mapping"));
            return finishCheck(result, issues, warnings);
        }
        result.put("mapping_id", String.valueOf(mapping.id()));
        result.put("goods_no", mapping.goodsNo());
        if (!mapping.active()) issues.add(issue("MAPPING_INACTIVE", "京东履约方商品映射已停用"));
        if (!hasText(mapping.goodsNo())) {
            issues.add(issue("GOODS_NO_MISSING", "京东 goodsNo 为空", "provider_skus.provider_sku_code"));
        }

        BigDecimal factor = null;
        String conversionSource = null;
        if (!mapping.externalCodes().containsKey(JdStockUnitConverter.FACTOR_CONFIG_KEY)) {
            if (JdStockUnitConverter.PIECES_UNIT.equals(subject.unit())) {
                factor = BigDecimal.ONE;
                conversionSource = "skus.unit=件 (deterministic factor 1)";
            } else {
                issues.add(issue(
                        "UNIT_CONVERSION_MISSING", "非‘件’单位必须配置显式京东件数换算",
                        "provider_skus.external_codes.jd_pieces_per_unit"));
            }
        } else {
            factor = JdStockUnitConverter.explicitFactorOrNull(mapping.externalCodes());
            conversionSource = "provider_skus.external_codes.jd_pieces_per_unit";
            if (factor == null) {
                issues.add(issue(
                        "UNIT_CONVERSION_INVALID", "京东件数换算必须是正数",
                        "provider_skus.external_codes.jd_pieces_per_unit"));
            }
        }
        if (conversionSource != null) result.put("unit_conversion_source", conversionSource);
        if (factor != null) {
            result.put("pieces_per_unit", factor.stripTrailingZeros().toPlainString());
            BigDecimal exact = JdStockUnitConverter.exactPiecesOrNull(subject.quantity(), factor);
            if (exact == null) {
                issues.add(issue(
                        "NON_INTEGRAL_QUANTITY", "数量与换算系数无法得到精确正整数件数，系统不取整",
                        "provider_skus.external_codes.jd_pieces_per_unit"));
            } else {
                result.put("exact_plan_quantity", exact.toPlainString());
            }
        }

        if (hasText(mapping.goodsNo())) {
            applyRemoteFacts(subject, mapping, facts, result, issues, warnings);
        }
        return finishCheck(result, issues, warnings);
    }

    private void applyRemoteFacts(
            SkuSubject subject,
            MappingRow mapping,
            JdGoodsReadOnlyVerifier.Verification facts,
            Map<String, Object> result,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> warnings) {
        if (facts == null || !facts.querySucceeded()) {
            String code = facts == null || !hasText(facts.businessCode())
                    ? "NO_RESPONSE"
                    : facts.businessCode();
            issues.add(issue("JD_GOODS_QUERY_FAILED", "京东商品只读查询失败（" + code + "）"));
            return;
        }
        if (!facts.found()) {
            issues.add(issue("JD_GOODS_NOT_FOUND", "京东未查到映射 goodsNo 对应的商品"));
            return;
        }
        if (hasText(facts.requestId())) result.put("jd_goods_query_request_id", facts.requestId());
        if (!mapping.goodsNo().equals(facts.goodsNo())) {
            issues.add(issue("GOODS_NO_CONFLICT", "京东返回 goodsNo 与映射关键标识不一致"));
        }
        if (hasText(mapping.merchantSkuCode()) && hasText(facts.erpGoodsNo())
                && !mapping.merchantSkuCode().equals(facts.erpGoodsNo())) {
            issues.add(issue("ERP_GOODS_NO_CONFLICT", "京东返回 erpGoodsNo 与映射关键标识不一致"));
        }
        // enableFlag 官方语义（快照 2026-08-11，docs/research/jdl-api-367/json/1610-queryGoodsInfo.json）：
        // 「启用标志，1：未启用，2：启用」。京东 ISC 惯例同为 1 否 / 2 是（同文档 storeSaleFlag、afterSaleFlag）。
        // 官方未定义 0；未文档化取值走告警而非阻断（fail-open），真正的硬关卡是 queryStock 库存校验。
        if (facts.enableFlag() == null) {
            issues.add(issue("GOODS_STATUS_MISSING", "京东商品响应缺少可用状态"));
        } else if (facts.enableFlag() == 1) {
            issues.add(issue("GOODS_DISABLED", "京东商品当前未启用（enableFlag=" + facts.enableFlag() + "）"));
        } else if (facts.enableFlag() != 2) {
            warnings.add(issue("GOODS_STATUS_UNKNOWN",
                    "京东返回未文档化的启用标志（enableFlag=" + facts.enableFlag()
                            + "），官方仅定义 1=未启用/2=启用；仅提示，不阻断"));
        }
        if (hasText(facts.goodsName()) && !nameMatches(subject, mapping, facts.goodsName())) {
            warnings.add(issue("NAME_MISMATCH", "京东展示名称与系统/映射展示名称不一致；仅提示，不自动改写"));
        }
    }

    private ReviewCase reconcileBlockingCase(
            ShipmentContext shipment,
            String checkRunNo,
            List<Map<String, Object>> blockingItems,
            boolean passed,
            String operator) {
        ReviewCase current = reviewCases.findShipmentCaseForUpdate(
                BLOCK_REASON, shipment.id(), ReviewCaseStatus.OPEN).orElse(null);
        if (passed) {
            if (current == null) return null;
            current.setStatus(ReviewCaseStatus.RESOLVED);
            current.setResolution(Map.of(
                    "resolution_type", "JD_SKU_MAPPING_RECHECK_PASSED",
                    "check_run_no", checkRunNo));
            current.setResolvedBy(operator);
            current.setResolvedAt(Instant.now());
            return reviewCases.saveAndFlush(current);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shipment_id", String.valueOf(shipment.id()));
        detail.put("check_run_no", checkRunNo);
        detail.put("affected_shipment_items", blockingItems);
        detail.put("maintenance_action", gateMaintenance(shipment.id()));
        if (current == null) {
            current = new ReviewCase();
            current.setCaseNo("RC-JD-SKU-" + token());
            current.setCaseType("JD_SKU_MAPPING");
            current.setStatus(ReviewCaseStatus.OPEN);
            current.setResponsibleTeam("SKU_OPS");
            current.setReasonCode(BLOCK_REASON);
            current.setOrderId(shipment.orderId());
            current.setShipmentId(shipment.id());
        }
        current.setDetail(detail);
        return reviewCases.saveAndFlush(current);
    }

    private Map<String, Object> affectedItem(
            ShipmentItemRow item, SkuSubject subject, MappingRow mapping, List<Map<String, Object>> issues) {
        Map<String, Object> affected = new LinkedHashMap<>();
        affected.put("shipment_item_id", String.valueOf(item.shipmentItemId()));
        affected.put("fulfillment_id", String.valueOf(item.fulfillmentId()));
        affected.put("order_line_id", String.valueOf(item.orderLineId()));
        affected.put("line_no", item.lineNo());
        if (subject.componentNo() != null) affected.put("component_no", subject.componentNo());
        affected.put("sku_id", subject.skuId() == null ? null : String.valueOf(subject.skuId()));
        affected.put("sku_code", subject.skuCode());
        // product_name/goods_no：阻断明细全量透传（2026-08-27）。此前只带 sku_code，运营在
        // JD_STOCK_BLOCKED 抽屉里看不到商品名/京东编码，只能凭 sku_code 去主数据页反查。
        affected.put("product_name", subject.productName());
        affected.put("goods_no", mapping == null ? null : mapping.goodsNo());
        affected.put("issues", issues);
        return affected;
    }

    private Map<String, Object> gateMaintenance(long shipmentId) {
        return Map.of(
                "action", "OPEN_SKU_MAPPING",
                "route", "/product/sku-mappings",
                "api", "/api/v1/provider-sku-mappings",
                "rerun_api", "/api/v1/shipments/" + shipmentId + "/jd-sku-mapping-check");
    }

    private Map<String, Object> mappingMaintenance(long providerId, Long skuId, MappingRow mapping) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("action", "OPEN_SKU_MAPPING");
        target.put("route", "/product/sku-mappings");
        target.put("api", mapping == null
                ? "/api/v1/provider-sku-mappings"
                : "/api/v1/provider-sku-mappings/" + mapping.id());
        target.put("provider_id", String.valueOf(providerId));
        if (skuId != null) target.put("sku_id", String.valueOf(skuId));
        return target;
    }

    private Map<String, Object> finishCheck(
            Map<String, Object> result,
            List<Map<String, Object>> issues,
            List<Map<String, Object>> warnings) {
        result.put("status", issues.isEmpty() ? "PASS" : "BLOCKED");
        result.put("issues", issues);
        result.put("warnings", warnings);
        return result;
    }

    private Map<String, Object> issue(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    /**
     * 带「缺什么字段」标注的问题项：仅用于确有具体本地字段可指的配置类问题（映射缺失/
     * goodsNo 缺失/京东件数换算缺失或非法）。远端事实类问题（京东商品未查到/已停用等）
     * 不是「本地字段缺了」，不套用本重载——2026-08-27 阻断明细透传需求：运营看到
     * blocker 不能只有一句通用文案，要能直接定位该去改哪个字段（案例：review_cases#41/#42）。
     */
    private Map<String, Object> issue(String code, String message, String missingField) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("message", message);
        value.put("missing_field", missingField);
        return value;
    }

    private boolean nameMatches(SkuSubject subject, MappingRow mapping, String remoteName) {
        String normalizedRemote = normalize(remoteName);
        for (String candidate : List.of(
                subject.productName() == null ? "" : subject.productName(),
                text(mapping.externalCodes().get("provider_sku_name")) == null
                        ? ""
                        : text(mapping.externalCodes().get("provider_sku_name")))) {
            String normalized = normalize(candidate);
            if (!normalized.isEmpty()
                    && (normalizedRemote.equals(normalized)
                            || normalizedRemote.contains(normalized)
                            || normalized.contains(normalizedRemote))) return true;
        }
        return false;
    }

    private Map<String, Object> jsonMap(String json) {
        if (!hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "");
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private record PreparedGate(ShipmentContext shipment, List<PreparedItem> items) {}

    private record PreparedItem(ShipmentItemRow item, List<PreparedSku> skus) {}

    private record PreparedSku(SkuSubject subject, MappingRow mapping) {}

    private record ShipmentContext(
            long id,
            long orderId,
            long providerId,
            String providerType,
            String shipmentStatus,
            long shipmentVersion) {}

    private record ItemSeed(ShipmentItemRow item, Long skuId) {}

    private record ShipmentItemRow(
            long shipmentItemId,
            long fulfillmentId,
            BigDecimal instructedQuantity,
            Instant updatedAt,
            long orderLineId,
            int lineNo,
            String lineType,
            String skuCode,
            String productName,
            String unit,
            Instant orderLineUpdatedAt) {}

    private record SkuState(boolean active, long lockVersion) {}

    private record SkuSubject(
            Long skuId,
            String skuCode,
            String productName,
            String unit,
            BigDecimal quantity,
            Long componentId,
            Integer componentNo,
            boolean skuActive,
            Long skuVersion) {}

    private record MappingRow(
            long id,
            String goodsNo,
            String merchantSkuCode,
            Map<String, Object> externalCodes,
            boolean active,
            long mappingVersion) {}
}
