package cn.zimu.fulfillment.recon;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.fulfillment.JdOutboundStatus;
import cn.zimu.fulfillment.fulfillment.ShipmentStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 出库信息内外事实并排查询（Ticket 01）：输入系统出库单号 / 京东单号 / 订单号，
 * 收敛到同一笔出库（Shipment），把系统内部事实与京东 querySoOrder 返回按语义对齐，
 * 逐字段判定差异。只读、只标记不处置；每次查询写入既有审计通道。
 *
 * <p>对齐主键是系统出库单号（shipments.outbound_order_no = 京东 erpDeliveryNo）；
 * 京东单号经 shipment_jd_outbounds.jd_delivery_no、订单号经 orders.order_no 反向定位 Shipment。
 *
 * <p>京东侧降级：查询失败/超时/未配置时 {@code jd.status=UNAVAILABLE}，内部事实照常返回；
 * 查询成功但京东无该出库时 {@code jd.status=NOT_FOUND}；两者都与「字段为空」明确区分。
 */
@Service
public class OutboundReconService {

    private static final Logger log = LoggerFactory.getLogger(OutboundReconService.class);

    static final String SCOPE = "outbound.recon.query";
    static final String TYPE_OUTBOUND_ORDER_NO = "OUTBOUND_ORDER_NO";
    static final String TYPE_JD_DELIVERY_NO = "JD_DELIVERY_NO";
    static final String TYPE_ORDER_NO = "ORDER_NO";

    private static final String JD_STATUS_OK = "OK";
    private static final String JD_STATUS_NOT_FOUND = "NOT_FOUND";
    private static final String JD_STATUS_UNAVAILABLE = "UNAVAILABLE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JDWarehouseService jdWarehouse;
    private final AuditLogService audits;
    private final String clientMode;

    public OutboundReconService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            JDWarehouseService jdWarehouse,
            AuditLogService audits,
            @Value("${app.jd.client-mode:MOCK}") String clientMode) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jdWarehouse = jdWarehouse;
        this.audits = audits;
        this.clientMode = "REAL".equalsIgnoreCase(clientMode == null ? "" : clientMode.trim())
                ? "REAL" : "MOCK";
    }

    /**
     * 并排查询入口。内部解析与加载是纯只读（autocommit），京东查询与审计在事务外执行，
     * 避免外部调用占用数据库连接。
     */
    public OutboundReconView query(String type, String value, RequestContext context) {
        String normalizedType = normalizeType(type);
        String normalizedValue = normalizeValue(value);
        try {
            InternalSnapshot internal = resolveAndLoad(normalizedType, normalizedValue);
            OutboundReconView.JdSide jd = queryJd(internal.outboundOrderNo());
            List<OutboundReconView.Comparison> comparisons = buildComparisons(internal, jd);
            int matched = (int) comparisons.stream().filter(row -> "MATCH".equals(row.state())).count();
            int mismatch = (int) comparisons.stream()
                    .filter(row -> !"MATCH".equals(row.state()) && !"EMPTY".equals(row.state()))
                    .count();
            String businessCode = "OUTBOUND_RECON_QUERIED";
            if (JD_STATUS_UNAVAILABLE.equals(jd.status())) {
                businessCode = "OUTBOUND_RECON_JD_UNAVAILABLE";
            } else if (JD_STATUS_NOT_FOUND.equals(jd.status())) {
                businessCode = "OUTBOUND_RECON_JD_NOT_FOUND";
            }
            audit(context, internal, normalizedType, normalizedValue, jd.status(), 200, businessCode, matched, mismatch);
            return new OutboundReconView(
                    new OutboundReconView.Query(normalizedType, normalizedValue),
                    new OutboundReconView.Audit(requestId(context), operator(context)),
                    internal.toView(),
                    jd,
                    comparisons,
                    matched,
                    mismatch);
        } catch (BusinessException exception) {
            auditFailure(context, normalizedType, normalizedValue, exception);
            throw exception;
        }
    }

    /** 输入校验：类型必须是三个检索入口之一，值非空。 */
    private String normalizeType(String type) {
        String candidate = type == null ? "" : type.trim().toUpperCase();
        if (TYPE_OUTBOUND_ORDER_NO.equals(candidate)
                || TYPE_JD_DELIVERY_NO.equals(candidate)
                || TYPE_ORDER_NO.equals(candidate)) {
            return candidate;
        }
        throw BusinessException.unprocessable(
                "OUTBOUND_RECON_QUERY_REQUIRED",
                "查询类型必须是 outbound_order_no / jd_delivery_no / order_no 之一");
    }

    private String normalizeValue(String value) {
        String candidate = value == null ? null : value.trim();
        if (candidate == null || candidate.isEmpty() || candidate.length() > 128) {
            throw BusinessException.unprocessable("OUTBOUND_RECON_QUERY_REQUIRED", "请输入要查询的出库单号/京东单号/订单号");
        }
        return candidate;
    }

    /**
     * 三个检索入口收敛到同一 Shipment；找不到或订单号命中多批发货批次时抛业务异常。
     * 纯只读查询，在 autocommit 下逐条执行即可（对账展示不要求跨查询一致快照），
     * 也不在京东外部调用期间占用数据库连接。
     */
    InternalSnapshot resolveAndLoad(String type, String value) {
        Long shipmentId;
        if (TYPE_OUTBOUND_ORDER_NO.equals(type)) {
            shipmentId = jdbc.query(
                    """
                    SELECT s.id FROM app.shipments s
                    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                    WHERE s.outbound_order_no=?
                    """,
                    rs -> rs.next() ? rs.getLong(1) : null,
                    value);
        } else if (TYPE_JD_DELIVERY_NO.equals(type)) {
            shipmentId = jdbc.query(
                    """
                    SELECT s.id FROM app.shipment_jd_outbounds j
                    JOIN app.shipments s ON s.id=j.shipment_id
                    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                    WHERE j.jd_delivery_no=?
                    """,
                    rs -> rs.next() ? rs.getLong(1) : null,
                    value);
        } else {
            List<Map<String, Object>> matches = jdbc.query(
                    """
                    SELECT s.id AS shipment_id, s.outbound_order_no
                    FROM app.shipments s
                    JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                    WHERE o.order_no=?
                    ORDER BY s.shipment_sequence, s.id
                    """,
                    (rs, rowNum) -> Map.of(
                            "shipment_id", String.valueOf(rs.getLong("shipment_id")),
                            "outbound_order_no", rs.getString("outbound_order_no")),
                    value);
            if (matches.size() > 1) {
                List<String> outboundNos = matches.stream()
                        .map(row -> row.get("outbound_order_no").toString())
                        .toList();
                throw new BusinessException(
                        409,
                        "OUTBOUND_RECON_AMBIGUOUS",
                        "该订单号对应多个发货批次（" + outboundNos.size() + " 个），请改用系统出库单号精确查询",
                        List.of(),
                        Map.of("outbound_order_nos", outboundNos));
            }
            shipmentId = matches.isEmpty() ? null : Long.parseLong(matches.getFirst().get("shipment_id").toString());
        }
        if (shipmentId == null) {
            throw new BusinessException(404, "OUTBOUND_RECON_NOT_FOUND", "系统内部没有这笔出库，请核对查询条件");
        }
        return loadInternal(shipmentId);
    }

    /** 一次性读取 Shipment / Order / Provider / JD 集成 / 明细 / 运单的内部事实快照。 */
    InternalSnapshot loadInternal(long shipmentId) {
        InternalSnapshot snapshot = jdbc.query(
                """
                SELECT s.id AS shipment_id, s.shipment_no, s.outbound_order_no, s.shipment_sequence,
                       s.order_id, s.shipment_status, s.shipped_at, s.created_at, s.updated_at,
                       s.receiver_name_snapshot, s.receiver_phone_snapshot, s.receiver_address_snapshot,
                       o.order_no, o.source_channel, o.source_ref,
                       fp.id AS provider_id, fp.provider_name, fp.provider_type,
                       fp.config::text AS provider_config,
                       j.sync_status, j.jd_delivery_no, j.submitted_at, j.retry_count,
                       j.last_error_code, j.last_error_message, j.client_mode, j.failure_phase,
                       j.submitted_warehouse_no, j.submitted_cargo_snapshot
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                LEFT JOIN app.shipment_jd_outbounds j ON j.shipment_id=s.id
                WHERE s.id=?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    List<Map<String, Object>> items = jdbc.query(
                            """
                            SELECT si.fulfillment_id, ol.id AS order_line_id, ol.line_no,
                                   ol.product_name_snapshot, ol.unit_snapshot,
                                   si.instructed_quantity, si.shipped_quantity,
                                   ps.provider_sku_code, ol.sku_id
                            FROM app.shipment_items si
                            JOIN app.fulfillments f ON f.id=si.fulfillment_id
                            JOIN app.order_lines ol ON ol.id=f.order_line_id
                            LEFT JOIN app.provider_skus ps
                                   ON ps.sku_id=ol.sku_id AND ps.fulfillment_provider_id=?
                            WHERE si.shipment_id=?
                            ORDER BY si.id
                            """,
                            (resultSet, rowNum) -> {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("order_line", String.valueOf(resultSet.getInt("line_no")));
                                item.put("goods_no", resultSet.getString("provider_sku_code"));
                                item.put("goods_name", resultSet.getString("product_name_snapshot"));
                                item.put("plan_quantity", qtyText(resultSet.getBigDecimal("instructed_quantity")));
                                item.put("shipped_quantity", qtyText(resultSet.getBigDecimal("shipped_quantity")));
                                item.put("unit", resultSet.getString("unit_snapshot"));
                                return item;
                            },
                            rs.getLong("provider_id"),
                            shipmentId);
                    Map<String, Object> tracking = jdbc.query(
                            """
                            SELECT logistics_company_code, logistics_company_name, tracking_number, received_at
                            FROM app.trackings WHERE shipment_id=?
                            """,
                            resultSet -> {
                                if (!resultSet.next()) {
                                    return null;
                                }
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("logistics_company_code", resultSet.getString("logistics_company_code"));
                                row.put("logistics_company_name", resultSet.getString("logistics_company_name"));
                                row.put("tracking_number", resultSet.getString("tracking_number"));
                                row.put("received_at", instant(resultSet.getObject("received_at", OffsetDateTime.class)));
                                return row;
                            },
                            shipmentId);
                    String syncStatus = rs.getString("sync_status");
                    String providerConfig = rs.getString("provider_config");
                    List<Map<String, Object>> submittedCargo =
                            parseCargoSnapshot(rs.getString("submitted_cargo_snapshot"));
                    return new InternalSnapshot(
                            rs.getLong("shipment_id"),
                            rs.getString("shipment_no"),
                            rs.getString("outbound_order_no"),
                            rs.getInt("shipment_sequence"),
                            rs.getLong("order_id"),
                            rs.getString("order_no"),
                            rs.getString("source_channel"),
                            rs.getString("source_ref"),
                            rs.getString("shipment_status"),
                            instant(rs.getObject("shipped_at", OffsetDateTime.class)),
                            instant(rs.getObject("created_at", OffsetDateTime.class)),
                            rs.getString("receiver_name_snapshot"),
                            rs.getString("receiver_phone_snapshot"),
                            rs.getString("receiver_address_snapshot"),
                            rs.getLong("provider_id"),
                            rs.getString("provider_name"),
                            rs.getString("provider_type"),
                            parseProviderConfig(providerConfig),
                            syncStatus,
                            rs.getString("jd_delivery_no"),
                            instant(rs.getObject("submitted_at", OffsetDateTime.class)),
                            rs.getInt("retry_count"),
                            rs.getString("last_error_code"),
                            rs.getString("last_error_message"),
                            rs.getString("client_mode"),
                            rs.getString("failure_phase"),
                            rs.getString("submitted_warehouse_no"),
                            submittedCargo,
                            List.copyOf(items),
                            tracking);
                },
                shipmentId);
        if (snapshot == null) {
            throw new BusinessException(404, "OUTBOUND_RECON_NOT_FOUND", "系统内部没有这笔出库，请核对查询条件");
        }
        return snapshot;
    }

    /** 京东 querySoOrder 查询；调用失败/超时降级为 UNAVAILABLE，不向上抛。 */
    private OutboundReconView.JdSide queryJd(String outboundOrderNo) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("erpDeliveryNo", outboundOrderNo);
        request.put("deliveryItemFlag", 1);
        request.put("deliveryPackageFlag", 1);
        request.put("deliveryStatusFlag", 1);
        JdResult result;
        try {
            result = jdWarehouse.queryOutboundOrder(request);
        } catch (RuntimeException exception) {
            log.warn("outbound recon JD query failed for {}", outboundOrderNo, exception);
            return new OutboundReconView.JdSide(JD_STATUS_UNAVAILABLE, "SDK_CALL_FAILED", "京东侧查询调用失败或超时", clientMode, null, List.of());
        }
        if (result == null || !result.success()) {
            String code = result == null || text(result.businessCode()) == null ? "UNKNOWN" : text(result.businessCode());
            String message = result == null || text(result.message()) == null
                    ? "京东侧查询未成功" : text(result.message());
            return new OutboundReconView.JdSide(JD_STATUS_UNAVAILABLE, code, message, clientMode, null, List.of());
        }
        Map<String, Object> envelope = jdResponseEnvelope(result.data());
        if (envelope == null || envelope.isEmpty()) {
            return new OutboundReconView.JdSide(JD_STATUS_NOT_FOUND, text(result.businessCode()), "京东查询成功但未返回出库记录",
                    clientMode, null, List.of());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("erp_delivery_no", text(envelope.get("erpDeliveryNo")));
        summary.put("delivery_no", text(envelope.get("deliveryNo")));
        summary.put("warehouse_no", text(envelope.get("warehouseNo")));
        String status = text(envelope.get("status"));
        summary.put("status", status);
        summary.put("status_semantic", status == null ? null : semanticLabel(JdOutboundStatus.semantic(status)));
        summary.put("is_split", text(envelope.get("isSplit")));
        summary.put("split_delivery_nos", text(envelope.get("splitDeliveryNos")));
        Map<String, Object> carrier = optionalMap(envelope.get("carrierInfo"));
        if (!carrier.isEmpty()) {
            Map<String, Object> carrierView = new LinkedHashMap<>();
            carrierView.put("carrier_no", text(carrier.get("carrierNo")));
            carrierView.put("carrier_name", text(carrier.get("carrierName")));
            carrierView.put("waybill_no", text(carrier.get("waybillNo")));
            summary.put("carrier", carrierView);
        }
        summary.put("item_count", listOfMaps(envelope.get("deliveryItemList")).size());
        summary.put("queried_at", Instant.now());
        // 收件人 PII 不在响应中下发：只保留脱敏姓名用于对齐，电话/地址整块剔除。
        summary.put("receiver_name_masked", maskName(text(optionalMap(envelope.get("receiverInfo")).get("name"))));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : listOfMaps(envelope.get("deliveryItemList"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order_line", text(row.get("orderLine")));
            item.put("goods_no", text(row.get("goodsNo")));
            item.put("plan_quantity", qtyText(row.get("planQuantity")));
            item.put("real_quantity", qtyText(row.get("realQuantity")));
            items.add(item);
        }
        return new OutboundReconView.JdSide(JD_STATUS_OK, text(result.businessCode()), text(result.message()), clientMode, summary, items);
    }

    /** 字段语义对齐 + 差异判定；京东整体不可达/无记录时逐行统一标记。 */
    private List<OutboundReconView.Comparison> buildComparisons(InternalSnapshot internal, OutboundReconView.JdSide jd) {
        List<OutboundReconView.Comparison> rows = new ArrayList<>();
        Map<String, Object> jdSummary = jd.summary() == null ? Map.of() : jd.summary();
        boolean jdOk = JD_STATUS_OK.equals(jd.status());
        String jdStatus = jd.status();

        rows.add(compare("outbound_order_no", "系统出库单号（商户出库引用）",
                internal.outboundOrderNo(), jdOk ? text(jdSummary.get("erp_delivery_no")) : null, jdStatus));

        rows.add(compare("jd_delivery_no", "京东出库单号",
                internal.jdDeliveryNo(), jdOk ? text(jdSummary.get("delivery_no")) : null, jdStatus));

        rows.add(compare("warehouse_no", "京东仓库",
                internal.warehouseNo(), jdOk ? text(jdSummary.get("warehouse_no")) : null, jdStatus));

        rows.add(statusComparison(internal, jdOk ? text(jdSummary.get("status")) : null, jdStatus));

        String internalReceiver = internal.receiverName() == null ? null : internal.receiverName().trim();
        String jdReceiver = jdOk ? text(jdSummary.get("receiver_name_masked")) : null;
        rows.add(compare("receiver_name", "收件人（京东侧姓名脱敏）",
                internalReceiver, jdReceiver, jdStatus));

        rows.add(itemsComparison(internal, jd));

        if (jdOk && !internal.jdOutboundPresent()) {
            // 系统内从未提交过京东出库单但京东返回了记录：在出库单号行补一句说明。
            rows.set(0, withNote(rows.get(0),
                    "系统内该发货批次未提交过京东出库单（sync_status=NONE），但京东侧返回了出库记录"));
        }
        return rows;
    }

    private OutboundReconView.Comparison statusComparison(InternalSnapshot internal, String jdStatusRaw, String jdStatus) {
        String internalSemantic = internalStatusSemanticLabel(internal.shipmentStatus());
        String internalValue = internal.shipmentStatus() + "（" + internalSemantic + "）";
        String jdValue = jdStatusRaw == null ? null : jdStatusRaw + "（" + semanticLabel(JdOutboundStatus.semantic(jdStatusRaw)) + "）";
        boolean internalPresent = true;
        boolean jdPresent = jdStatusRaw != null && JD_STATUS_OK.equals(jdStatus);
        String state;
        String note;
        switch (jdStatus) {
            case JD_STATUS_UNAVAILABLE -> {
                state = "JD_UNAVAILABLE";
                note = "京东侧查询失败或超时，未取到";
            }
            case JD_STATUS_NOT_FOUND -> {
                state = "JD_NOT_FOUND";
                note = "京东侧没有这笔出库记录";
            }
            default -> {
                if (!jdPresent) {
                    state = "INTERNAL_ONLY";
                    note = "京东未返回状态字段";
                } else {
                    boolean semanticEqual = Objects.equals(
                            internalStatusSemantic(internal.shipmentStatus()),
                            JdOutboundStatus.semantic(jdStatusRaw));
                    if (semanticEqual) {
                        state = "MATCH";
                        note = "内部状态与京东状态码编码不同但语义一致";
                    } else {
                        state = "MISMATCH";
                        note = "内部状态（" + internal.shipmentStatus() + " / " + internalSemantic
                                + "）与京东状态码（" + jdStatusRaw + " / " + semanticLabel(JdOutboundStatus.semantic(jdStatusRaw))
                                + "）语义不一致";
                    }
                }
            }
        }
        return new OutboundReconView.Comparison(
                "status", "出库状态", internalValue, jdValue, internalPresent, jdPresent, state, note);
    }

    /** 商品明细：内部指令（提交快照优先，未提交回落 shipment_items）与京东 deliveryItemList 按 goodsNo 对齐。 */
    private OutboundReconView.Comparison itemsComparison(InternalSnapshot internal, OutboundReconView.JdSide jd) {
        boolean jdOk = JD_STATUS_OK.equals(jd.status());
        List<Map<String, Object>> internalItems = internal.compareItems();
        List<Map<String, Object>> jdItems = jdOk ? jd.items() : List.of();
        Map<String, String> internalByGoods = new LinkedHashMap<>();
        for (Map<String, Object> row : internalItems) {
            String goodsNo = text(row.get("goods_no"));
            if (goodsNo != null) {
                internalByGoods.putIfAbsent(goodsNo, text(row.get("plan_quantity")));
            }
        }
        Map<String, String> jdByGoods = new LinkedHashMap<>();
        for (Map<String, Object> row : jdItems) {
            String goodsNo = text(row.get("goods_no"));
            if (goodsNo != null) {
                jdByGoods.putIfAbsent(goodsNo, text(row.get("plan_quantity")));
            }
        }
        boolean internalPresent = !internalItems.isEmpty();
        boolean jdPresent = !jdItems.isEmpty();

        List<String> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(internalByGoods.keySet());
        keys.addAll(jdByGoods.keySet());
        for (String goodsNo : keys) {
            String internalQty = internalByGoods.get(goodsNo);
            String jdQty = jdByGoods.get(goodsNo);
            if (internalQty != null && jdQty == null) {
                diffs.add("商品 " + goodsNo + "：内部指令 " + internalQty + " 件，京东明细未返回");
            } else if (internalQty == null && jdQty != null) {
                diffs.add("商品 " + goodsNo + "：京东明细 " + jdQty + " 件，内部未指令");
            } else if (internalQty != null && !Objects.equals(internalQty, jdQty)) {
                diffs.add("商品 " + goodsNo + "：内部指令 " + internalQty + " 件，京东计划 " + jdQty + " 件");
            }
        }

        String state;
        String note;
        switch (jd.status()) {
            case JD_STATUS_UNAVAILABLE -> {
                state = "JD_UNAVAILABLE";
                note = "京东侧查询失败或超时，未取到";
            }
            case JD_STATUS_NOT_FOUND -> {
                state = "JD_NOT_FOUND";
                note = "京东侧没有这笔出库记录";
            }
            default -> {
                if (!internalPresent && !jdPresent) {
                    state = "EMPTY";
                    note = "两侧均未记录商品明细";
                } else if (internalPresent && !jdPresent) {
                    state = "INTERNAL_ONLY";
                    note = "京东未返回商品明细（deliveryItemList 为空或缺失）";
                } else if (!internalPresent) {
                    state = "JD_ONLY";
                    note = "系统内部未记录商品明细";
                } else if (diffs.isEmpty()) {
                    state = "MATCH";
                    note = "商品行数与数量一致（" + keys.size() + " 行）";
                } else {
                    state = "MISMATCH";
                    note = String.join("；", diffs);
                }
            }
        }
        return new OutboundReconView.Comparison(
                "items",
                "商品明细",
                itemSummary(internalItems),
                itemSummary(jdItems),
                internalPresent,
                jdPresent,
                state,
                note);
    }

    private String itemSummary(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> row : items) {
            String goodsNo = text(row.get("goods_no"));
            String qty = text(row.get("plan_quantity"));
            String goodsName = text(row.get("goods_name"));
            String unit = text(row.get("unit"));
            StringBuilder part = new StringBuilder();
            if (goodsName != null) {
                part.append(goodsName);
            }
            if (goodsNo != null) {
                part.append('(').append(goodsNo).append(')');
            }
            if (qty != null) {
                part.append(" × ").append(qty);
                if (unit != null && !"件".equals(unit)) {
                    part.append(unit);
                }
            }
            parts.add(part.toString());
        }
        return String.join("、", parts);
    }

    private OutboundReconView.Comparison compare(
            String key, String label, Object internalValue, Object jdValue, String jdStatus) {
        boolean internalPresent = present(internalValue);
        boolean jdPresent = present(jdValue);
        String state;
        String note;
        switch (jdStatus) {
            case JD_STATUS_UNAVAILABLE -> {
                state = "JD_UNAVAILABLE";
                note = "京东侧查询失败或超时，未取到";
            }
            case JD_STATUS_NOT_FOUND -> {
                state = "JD_NOT_FOUND";
                note = "京东侧没有这笔出库记录";
            }
            default -> {
                if (internalPresent && jdPresent) {
                    boolean equal = Objects.equals(normalize(internalValue), normalize(jdValue));
                    if (equal) {
                        state = "MATCH";
                        note = null;
                    } else {
                        state = "MISMATCH";
                        note = "内部「" + display(internalValue) + "」与京东「" + display(jdValue) + "」不一致";
                    }
                } else if (internalPresent) {
                    state = "INTERNAL_ONLY";
                    note = "京东未返回该字段";
                } else if (jdPresent) {
                    state = "JD_ONLY";
                    note = "系统内部未记录该字段";
                } else {
                    state = "EMPTY";
                    note = "两侧均未记录该字段";
                }
            }
        }
        return new OutboundReconView.Comparison(key, label, internalValue, jdValue, internalPresent, jdPresent, state, note);
    }

    private OutboundReconView.Comparison withNote(OutboundReconView.Comparison row, String note) {
        return new OutboundReconView.Comparison(
                row.key(), row.label(), row.internal_value(), row.jd_value(),
                row.internal_present(), row.jd_present(), row.state(), note);
    }

    /** 审计：只留引用与计数，不含收件人 PII 或京东原始响应。 */
    private void audit(
            RequestContext context,
            InternalSnapshot internal,
            String type,
            String value,
            String jdStatus,
            int httpStatus,
            String businessCode,
            int matched,
            int mismatch) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(internal.orderId())
                .requestId(requestId(context))
                .traceId(context == null ? null : context.getTraceId())
                .operator(operator(context))
                .actorType(AuditActorType.HUMAN)
                .service("fulfillment")
                .operation(SCOPE)
                .requestPayload(Map.of(
                        "query_type", type,
                        "query_value", value))
                .responsePayload(Map.of(
                        "shipment_id", String.valueOf(internal.shipmentId()),
                        "outbound_order_no", internal.outboundOrderNo(),
                        "order_no", internal.orderNo(),
                        "jd_status", jdStatus,
                        "internal_present", true,
                        "matched_count", matched,
                        "mismatch_count", mismatch))
                .httpStatus(httpStatus)
                .businessCode(businessCode));
    }

    /** 未命中/歧义等失败查询同样留痕，保证「谁在什么时候查了什么」可追溯。 */
    private void auditFailure(RequestContext context, String type, String value, BusinessException exception) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(requestId(context))
                .traceId(context == null ? null : context.getTraceId())
                .operator(operator(context))
                .actorType(AuditActorType.HUMAN)
                .service("fulfillment")
                .operation(SCOPE)
                .requestPayload(Map.of(
                        "query_type", type,
                        "query_value", value))
                .responsePayload(Map.of(
                        "business_code", exception.getBusinessCode(),
                        "message", exception.getMessage()))
                .httpStatus(exception.getHttpStatus())
                .businessCode(exception.getBusinessCode()));
    }

    private String requestId(RequestContext context) {
        return context == null ? null : context.getRequestId();
    }

    private String operator(RequestContext context) {
        if (context == null || context.getOperator() == null || context.getOperator().isBlank()) {
            return "unauthenticated";
        }
        return context.getOperator();
    }

    // ---------- 内部事实快照 ----------

    /** 内部事实的不可变快照；在只读事务内加载，事务外消费。 */
    static final class InternalSnapshot {
        private final long shipmentId;
        private final String shipmentNo;
        private final String outboundOrderNo;
        private final int shipmentSequence;
        private final long orderId;
        private final String orderNo;
        private final String sourceChannel;
        private final String sourceRef;
        private final String shipmentStatus;
        private final Instant shippedAt;
        private final Instant createdAt;
        private final String receiverName;
        private final String receiverPhone;
        private final String receiverAddress;
        private final long providerId;
        private final String providerName;
        private final String providerType;
        private final Map<String, Object> providerConfig;
        private final String syncStatus;
        private final String jdDeliveryNo;
        private final Instant submittedAt;
        private final int retryCount;
        private final String lastErrorCode;
        private final String lastErrorMessage;
        private final String clientMode;
        private final String failurePhase;
        private final String submittedWarehouseNo;
        private final List<Map<String, Object>> submittedCargo;
        private final List<Map<String, Object>> items;
        private final Map<String, Object> tracking;

        InternalSnapshot(
                long shipmentId, String shipmentNo, String outboundOrderNo, int shipmentSequence,
                long orderId, String orderNo, String sourceChannel, String sourceRef, String shipmentStatus,
                Instant shippedAt, Instant createdAt, String receiverName, String receiverPhone,
                String receiverAddress, long providerId, String providerName, String providerType,
                Map<String, Object> providerConfig, String syncStatus, String jdDeliveryNo, Instant submittedAt,
                int retryCount, String lastErrorCode, String lastErrorMessage, String clientMode,
                String failurePhase, String submittedWarehouseNo, List<Map<String, Object>> submittedCargo,
                List<Map<String, Object>> items, Map<String, Object> tracking) {
            this.shipmentId = shipmentId;
            this.shipmentNo = shipmentNo;
            this.outboundOrderNo = outboundOrderNo;
            this.shipmentSequence = shipmentSequence;
            this.orderId = orderId;
            this.orderNo = orderNo;
            this.sourceChannel = sourceChannel;
            this.sourceRef = sourceRef;
            this.shipmentStatus = shipmentStatus;
            this.shippedAt = shippedAt;
            this.createdAt = createdAt;
            this.receiverName = receiverName;
            this.receiverPhone = receiverPhone;
            this.receiverAddress = receiverAddress;
            this.providerId = providerId;
            this.providerName = providerName;
            this.providerType = providerType;
            this.providerConfig = providerConfig;
            this.syncStatus = syncStatus;
            this.jdDeliveryNo = jdDeliveryNo;
            this.submittedAt = submittedAt;
            this.retryCount = retryCount;
            this.lastErrorCode = lastErrorCode;
            this.lastErrorMessage = lastErrorMessage;
            this.clientMode = clientMode;
            this.failurePhase = failurePhase;
            this.submittedWarehouseNo = submittedWarehouseNo;
            this.submittedCargo = submittedCargo;
            this.items = items;
            this.tracking = tracking;
        }

        long shipmentId() {
            return shipmentId;
        }

        String outboundOrderNo() {
            return outboundOrderNo;
        }

        String orderNo() {
            return orderNo;
        }

        long orderId() {
            return orderId;
        }

        String shipmentStatus() {
            return shipmentStatus;
        }

        String jdDeliveryNo() {
            return jdDeliveryNo;
        }

        String receiverName() {
            return receiverName;
        }

        boolean jdOutboundPresent() {
            return syncStatus != null;
        }

        /** 履约方配置中的京东仓库；优先使用建单提交快照仓库，其次配置值。 */
        String warehouseNo() {
            if (submittedWarehouseNo != null && !submittedWarehouseNo.isBlank()) {
                return submittedWarehouseNo;
            }
            Object config = providerConfig.get("warehouseNo");
            return config == null ? null : config.toString();
        }

        /** 用于与京东 deliveryItemList 对比的内部指令：提交快照优先，未提交回落 shipment_items。 */
        List<Map<String, Object>> compareItems() {
            if (submittedCargo != null && !submittedCargo.isEmpty()) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Map<String, Object> row : submittedCargo) {
                    result.add(Map.of(
                            "order_line", text(row.get("orderLine")),
                            "goods_no", text(row.get("goodsNo")),
                            "plan_quantity", qtyText(row.get("planQuantity"))));
                }
                return result;
            }
            return items;
        }

        /** 内部事实视图（响应体）；收件人快照与发货记录页口径一致。 */
        OutboundReconView.InternalSide toView() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("shipment_id", String.valueOf(shipmentId));
            summary.put("shipment_no", shipmentNo);
            summary.put("outbound_order_no", outboundOrderNo);
            summary.put("shipment_sequence", shipmentSequence);
            summary.put("order_id", String.valueOf(orderId));
            summary.put("order_no", orderNo);
            summary.put("source_channel", sourceChannel);
            summary.put("source_ref", sourceRef);
            summary.put("shipment_status", shipmentStatus);
            summary.put("shipped_at", shippedAt);
            summary.put("created_at", createdAt);
            Map<String, Object> receiver = new LinkedHashMap<>();
            receiver.put("name", receiverName);
            receiver.put("phone", receiverPhone);
            receiver.put("address", receiverAddress);
            summary.put("receiver", receiver);
            Map<String, Object> provider = new LinkedHashMap<>();
            provider.put("id", String.valueOf(providerId));
            provider.put("name", providerName);
            provider.put("type", providerType);
            summary.put("provider", provider);
            if (syncStatus != null) {
                Map<String, Object> jd = new LinkedHashMap<>();
                jd.put("sync_status", syncStatus);
                jd.put("jd_delivery_no", jdDeliveryNo);
                jd.put("submitted_at", submittedAt);
                jd.put("retry_count", retryCount);
                jd.put("failure_phase", failurePhase);
                jd.put("last_error_code", lastErrorCode);
                jd.put("last_error_message", lastErrorMessage);
                jd.put("client_mode", clientMode);
                summary.put("jd_outbound", jd);
            } else {
                summary.put("jd_outbound", null);
            }
            List<Map<String, Object>> itemViews = new ArrayList<>();
            for (Map<String, Object> row : items) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("order_line", row.get("order_line"));
                view.put("goods_no", row.get("goods_no"));
                view.put("goods_name", row.get("goods_name"));
                view.put("plan_quantity", row.get("plan_quantity"));
                view.put("shipped_quantity", row.get("shipped_quantity"));
                view.put("unit", row.get("unit"));
                itemViews.add(view);
            }
            return new OutboundReconView.InternalSide(summary, itemViews, tracking);
        }
    }

    // ---------- JD 响应解析（兼容 REAL 直接 data 与 Mock 包一层 response） ----------

    private Map<String, Object> jdResponseEnvelope(Object data) {
        if (!(data instanceof Map<?, ?> values)) {
            return null;
        }
        Map<String, Object> map = stringKeyMap(values);
        Object nested = map.get("response");
        if (nested instanceof Map<?, ?> inner) {
            return stringKeyMap(inner);
        }
        return map;
    }

    private Map<String, Object> optionalMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        return stringKeyMap(values);
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof Collection<?> values)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : values) {
            if (item instanceof Map<?, ?> map) {
                result.add(stringKeyMap(map));
            }
        }
        return result;
    }

    private List<Map<String, Object>> parseCargoSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            log.warn("submitted_cargo_snapshot parse failed: {}", json);
            return null;
        }
    }

    private Map<String, Object> parseProviderConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            log.warn("provider config parse failed");
            return Map.of();
        }
    }

    // ---------- 展示/比较辅助 ----------

    private static boolean present(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return true;
    }

    private static Object normalize(Object value) {
        if (value instanceof String text) {
            return text.trim();
        }
        return value;
    }

    private static String display(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            return collection.size() + " 行";
        }
        return value.toString();
    }

    private static String qtyText(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        } catch (RuntimeException exception) {
            return value.toString();
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = value.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String maskName(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() <= 1) {
            return "*";
        }
        return trimmed.substring(0, 1) + "*".repeat(trimmed.length() - 1);
    }

    private static String internalStatusSemanticLabel(String status) {
        return semanticLabel(internalStatusSemantic(status));
    }

    /** 内部发货批次状态 → 展示语义；状态词汇由 {@link ShipmentStatus} 唯一裁决（票 02）。 */
    private static JdOutboundStatus.Semantic internalStatusSemantic(String status) {
        if (ShipmentStatus.isShipped(status)) {
            return JdOutboundStatus.Semantic.SHIPPED;
        }
        if (ShipmentStatus.isFailed(status)) {
            return JdOutboundStatus.Semantic.EXCEPTION;
        }
        return JdOutboundStatus.Semantic.PENDING;
    }

    private static String semanticLabel(JdOutboundStatus.Semantic semantic) {
        return switch (semantic) {
            case SHIPPED -> "已发货/出库";
            case EXCEPTION -> "异常终止";
            case PENDING -> "未出库/处理中";
        };
    }
}
