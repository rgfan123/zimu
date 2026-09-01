package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.fulfillment.JdCargoPlanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 原始行「jd_cargos」投影：该来源行将/已发送京东 SDK {@code cargoInfos[].planQuantity} 的
 * 精确货品清单。
 *
 * <p>血缘只走 {@code raw_import_row_order_lines}（全量分片，不使用 legacy
 * {@code raw_import_rows.order_line_id}）；每条京东履约分片行按 SDK 建单口径换算
 * （SINGLE = 履约请求/指令数量 × jd_pieces_per_unit，CUSTOM_BUNDLE = 该数量 ×
 * quantity_per_bundle × jd_pieces_per_unit）。展开与换算、映射裁决全部复用共享纯
 * {@link JdCargoPlanner}，绝不复制建单逻辑（不四舍五入、不向上取整，要求精确正整数）；
 * 第三方/无京东履约行为空数组。
 * 已提交（{@code shipment_jd_outbounds.submitted_cargo_snapshot}）时优先冻结实际提交值，
 * 映射再变不漂移。整页一次性批量查询：血缘/组件/数量/映射/已提交快照五个事实族各一条固定
 * IN 查询；装载、分组与纯投影拆为独立阶段，避免 N+1。
 */
@Service
public class ImportRowJdCargoProjectionService {

    private static final String JD_WAREHOUSE_PROVIDER_TYPE = "JD_WAREHOUSE";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ImportRowJdCargoProjectionService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 该来源行将/已发送京东 SDK 的精确货品清单（原始行 id → 货品，SDK 顺序）。 */
    public Map<Long, List<JdCargoProjection>> jdCargosByRawRowId(List<Long> rawRowIds) {
        if (rawRowIds.isEmpty()) {
            return Map.of();
        }
        List<RowLineLink> links = loadLinks(rawRowIds);
        if (links.isEmpty()) {
            return emptyProjection(rawRowIds);
        }
        List<Long> lineIds = distinctOrderLineIds(links);
        Map<Long, List<ComponentLine>> componentsByLine =
                groupComponentsByOrderLine(loadComponents(lineIds));
        Map<Long, LineQuantity> quantityByLine =
                groupQuantitiesByOrderLine(loadQuantities(lineIds));
        JdCargoFacts facts = new JdCargoFacts(
                groupLinksByRawRow(links),
                componentsByLine,
                quantityByLine,
                loadGoods(cargoSkuIds(links, componentsByLine)),
                loadSnapshots(distinctShipmentIds(quantityByLine)));
        return projectCargos(rawRowIds, facts);
    }

    /** 无血缘时的空投影：仍为每个原始行输出空数组（键全集与常规路径一致）。 */
    private static Map<Long, List<JdCargoProjection>> emptyProjection(List<Long> rawRowIds) {
        Map<Long, List<JdCargoProjection>> empty = new LinkedHashMap<>();
        rawRowIds.forEach(id -> empty.put(id, List.of()));
        return empty;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** 血缘装载：原始行 → 订单行（raw_import_row_order_lines 全量分片，按 partition_no 稳定排序）。 */
    private List<RowLineLink> loadLinks(List<Long> rawRowIds) {
        return jdbc.query(
                """
                SELECT rir.id AS raw_row_id, rirol.partition_no, ol.id AS order_line_id,
                       ol.line_type, ol.sku_id, ol.line_no, ol.fulfillment_provider_id,
                       fp.provider_type, ol.product_name_snapshot,
                       COALESCE(sk.unit, ol.unit_snapshot) AS unit_snapshot
                FROM app.raw_import_rows rir
                JOIN app.raw_import_row_order_lines rirol ON rirol.raw_import_row_id=rir.id
                JOIN app.order_lines ol ON ol.id=rirol.order_line_id
                JOIN app.fulfillment_providers fp ON fp.id=ol.fulfillment_provider_id
                LEFT JOIN app.skus sk ON sk.id=ol.sku_id
                WHERE rir.id IN ("""
                        + placeholders(rawRowIds.size()) + ")"
                        + " ORDER BY rir.id, rirol.partition_no",
                (resultSet, rowNum) -> new RowLineLink(
                        resultSet.getLong("raw_row_id"),
                        resultSet.getInt("partition_no"),
                        resultSet.getLong("order_line_id"),
                        resultSet.getString("line_type"),
                        resultSet.getObject("sku_id", Long.class),
                        resultSet.getInt("line_no"),
                        resultSet.getLong("fulfillment_provider_id"),
                        resultSet.getString("provider_type"),
                        resultSet.getString("product_name_snapshot"),
                        resultSet.getString("unit_snapshot")),
                rawRowIds.toArray());
    }

    /** 礼包组件装载：一次批量查询，按 order_line_id, component_no 稳定排序。 */
    private List<ComponentLine> loadComponents(List<Long> orderLineIds) {
        return jdbc.query(
                """
                SELECT olc.order_line_id, olc.component_no, olc.sku_id, olc.quantity_per_bundle,
                       olc.product_name_snapshot, COALESCE(sk.unit, olc.unit_snapshot) AS unit_snapshot
                FROM app.order_line_components olc
                LEFT JOIN app.skus sk ON sk.id=olc.sku_id
                WHERE olc.order_line_id IN ("""
                        + placeholders(orderLineIds.size()) + ")"
                        + " ORDER BY olc.order_line_id, olc.component_no",
                (resultSet, rowNum) -> new ComponentLine(
                        resultSet.getLong("order_line_id"),
                        resultSet.getInt("component_no"),
                        resultSet.getLong("sku_id"),
                        resultSet.getInt("quantity_per_bundle"),
                        resultSet.getString("product_name_snapshot"),
                        resultSet.getString("unit_snapshot")),
                orderLineIds.toArray());
    }

    /**
     * 数量装载：履约请求数量 + 发货明细指令数量 + 归属 shipment（提交快照匹配键）；无发货明细
     * 时回退请求数量，与 confirm 时 createJdShipments 的 instructed = fulfillment.requested 一致。
     */
    private List<LineQuantity> loadQuantities(List<Long> orderLineIds) {
        return jdbc.query(
                """
                SELECT f.order_line_id, f.requested_quantity,
                       si.instructed_quantity, si.shipment_id
                FROM app.fulfillments f
                LEFT JOIN app.shipment_items si ON si.fulfillment_id=f.id
                WHERE f.order_line_id IN ("""
                        + placeholders(orderLineIds.size()) + ")"
                        + " ORDER BY f.order_line_id, si.id",
                (resultSet, rowNum) -> new LineQuantity(
                        resultSet.getLong("order_line_id"),
                        resultSet.getInt("requested_quantity"),
                        resultSet.getObject("instructed_quantity", Integer.class),
                        resultSet.getObject("shipment_id", Long.class)),
                orderLineIds.toArray());
    }

    /** 商品映射装载：provider_skus（与建单 loadGoods 相同的 active 门禁，active 也交给共享 planner 再裁决）。 */
    private Map<ProviderSkuKey, ProviderSkuFacts> loadGoods(List<Long> skuIds) {
        Map<ProviderSkuKey, ProviderSkuFacts> goodsByKey = new LinkedHashMap<>();
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        jdbc.query(
                """
                SELECT ps.fulfillment_provider_id, ps.sku_id, ps.provider_sku_code,
                       ps.merchant_sku_code, ps.external_codes::text AS external_codes, ps.active
                FROM app.provider_skus ps
                WHERE ps.active AND ps.sku_id IN ("""
                        + placeholders(skuIds.size()) + ")",
                resultSet -> {
                    goodsByKey.put(
                            new ProviderSkuKey(
                                    resultSet.getLong("fulfillment_provider_id"),
                                    resultSet.getLong("sku_id")),
                            new ProviderSkuFacts(
                                    resultSet.getString("provider_sku_code"),
                                    resultSet.getString("merchant_sku_code"),
                                    parseJsonMap(resultSet.getString("external_codes")),
                                    resultSet.getBoolean("active")));
                },
                skuIds.toArray());
        return Map.copyOf(goodsByKey);
    }

    /** 已提交快照装载：shipment_jd_outbounds.submitted_cargo_snapshot，按 orderLine 键索引以便冻结匹配。 */
    private Map<Long, Map<String, Map<String, Object>>> loadSnapshots(List<Long> shipmentIds) {
        Map<Long, Map<String, Map<String, Object>>> snapshotByShipment = new LinkedHashMap<>();
        if (shipmentIds.isEmpty()) {
            return Map.of();
        }
        jdbc.query(
                """
                SELECT shipment_id, submitted_cargo_snapshot::text AS snapshot
                FROM app.shipment_jd_outbounds
                WHERE shipment_id IN ("""
                        + placeholders(shipmentIds.size()) + ")"
                        + " AND submitted_cargo_snapshot IS NOT NULL",
                resultSet -> {
                    Map<String, Map<String, Object>> byOrderLine = new LinkedHashMap<>();
                    for (Map<String, Object> cargo : parseCargoSnapshot(resultSet.getString("snapshot"))) {
                        Object orderLine = cargo.get("orderLine");
                        if (orderLine != null) {
                            byOrderLine.put(String.valueOf(orderLine), cargo);
                        }
                    }
                    snapshotByShipment.put(resultSet.getLong("shipment_id"), byOrderLine);
                },
                shipmentIds.toArray());
        return Map.copyOf(snapshotByShipment);
    }

    /** 血缘按原始行分组（保留 partition_no 稳定顺序）。 */
    private static Map<Long, List<RowLineLink>> groupLinksByRawRow(List<RowLineLink> links) {
        Map<Long, List<RowLineLink>> byRow = new LinkedHashMap<>();
        for (RowLineLink link : links) {
            byRow.computeIfAbsent(link.rawRowId(), ignored -> new ArrayList<>()).add(link);
        }
        return immutableIndex(byRow);
    }

    /** 礼包组件按订单行分组（保留 component_no 稳定顺序）。 */
    private static Map<Long, List<ComponentLine>> groupComponentsByOrderLine(List<ComponentLine> components) {
        Map<Long, List<ComponentLine>> byLine = new LinkedHashMap<>();
        for (ComponentLine component : components) {
            byLine.computeIfAbsent(component.orderLineId(), ignored -> new ArrayList<>()).add(component);
        }
        return immutableIndex(byLine);
    }

    /** 数量按订单行取首条（无发货明细时回退请求数量的口径，见 loadQuantities）。 */
    private static Map<Long, LineQuantity> groupQuantitiesByOrderLine(List<LineQuantity> quantities) {
        Map<Long, LineQuantity> byLine = new LinkedHashMap<>();
        for (LineQuantity quantity : quantities) {
            byLine.computeIfAbsent(quantity.orderLineId(), ignored -> quantity);
        }
        return Map.copyOf(byLine);
    }

    /** 稳定索引：外层按插入序不可变，内层列表也不可变（投影阶段只读）。 */
    private static <K, V> Map<K, List<V>> immutableIndex(Map<K, List<V>> grouped) {
        Map<K, List<V>> copy = new LinkedHashMap<>();
        grouped.forEach((key, values) -> copy.put(key, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static List<Long> distinctOrderLineIds(List<RowLineLink> links) {
        return links.stream().map(RowLineLink::orderLineId).distinct().toList();
    }

    /** 京东履约行涉及的 SKU 集合（行 SKU + 礼包组件 SKU），供 provider_skus 批量装载。 */
    private static List<Long> cargoSkuIds(
            List<RowLineLink> links, Map<Long, List<ComponentLine>> componentsByLine) {
        List<Long> skuIds = new ArrayList<>();
        for (RowLineLink link : links) {
            if (!JD_WAREHOUSE_PROVIDER_TYPE.equals(link.providerType())) {
                continue;
            }
            if (link.skuId() != null) {
                skuIds.add(link.skuId());
            }
            for (ComponentLine component : componentsByLine.getOrDefault(link.orderLineId(), List.of())) {
                skuIds.add(component.skuId());
            }
        }
        return skuIds.stream().distinct().toList();
    }

    private static List<Long> distinctShipmentIds(Map<Long, LineQuantity> quantityByLine) {
        return quantityByLine.values().stream()
                .map(LineQuantity::shipmentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * 纯投影阶段：无数据库访问，仅把装载结果换算为每个原始行的 SDK 货品清单
     * （SINGLE/礼包组件展开、数量换算与映射裁决全部由共享 {@link JdCargoPlanner} 执行，
     * 与建单 cargoInfos 同序同量；快照优先冻结）。
     */
    private Map<Long, List<JdCargoProjection>> projectCargos(List<Long> rawRowIds, JdCargoFacts facts) {
        Map<Long, List<JdCargoProjection>> result = new LinkedHashMap<>();
        for (Long rawRowId : rawRowIds) {
            List<JdCargoProjection> cargos = new ArrayList<>();
            for (RowLineLink link : facts.linksByRow().getOrDefault(rawRowId, List.of())) {
                if (!JD_WAREHOUSE_PROVIDER_TYPE.equals(link.providerType())) {
                    continue;
                }
                LineQuantity quantity = facts.quantityByLine().get(link.orderLineId());
                if (quantity == null) {
                    continue;
                }
                Integer systemQuantity = quantity.instructedQuantity() != null
                        ? quantity.instructedQuantity()
                        : quantity.requestedQuantity();
                if (systemQuantity == null || systemQuantity <= 0) {
                    continue;
                }
                Map<String, Map<String, Object>> snapshot = quantity.shipmentId() == null
                        ? null
                        : facts.snapshotByShipment().get(quantity.shipmentId());
                // SINGLE 行/礼包组件的展开与数量换算由共享 JdCargoPlanner 一处裁决：
                // 行投影与建单 cargoInfos 同序同量（冻结快照按候选 orderLine 键匹配）。
                List<JdCargoPlanner.ComponentCandidate> components = facts.componentsByLine()
                        .getOrDefault(link.orderLineId(), List.of()).stream()
                        .map(component -> new JdCargoPlanner.ComponentCandidate(
                                component.componentNo(), component.skuId(), component.productName(),
                                component.unit(), component.quantityPerBundle()))
                        .toList();
                for (JdCargoPlanner.CargoCandidate candidate : JdCargoPlanner.expand(
                        new JdCargoPlanner.LineCandidate(
                                link.lineType(), link.lineNo(), link.skuId(), link.productName(),
                                link.unit(), systemQuantity, components))) {
                    addPlannedCargo(
                            cargos, snapshot, candidate.orderLine(),
                            candidate.goodsName(), candidate.unit(), candidate.skuId(),
                            link.providerId(), candidate.systemQuantity(), facts.goodsByKey());
                }
            }
            result.put(rawRowId, cargos);
        }
        return result;
    }

    /**
     * 单条货品投影：优先冻结的已提交快照，否则走共享 {@link JdCargoPlanner} 裁决；
     * 缺映射/不可精确换算的货品不展示（与建单阻断语义一致）。
     */
    private void addPlannedCargo(
            List<JdCargoProjection> cargos,
            Map<String, Map<String, Object>> snapshot,
            String orderLineKey,
            String productName,
            String unit,
            Long skuId,
            long providerId,
            long systemQuantity,
            Map<ProviderSkuKey, ProviderSkuFacts> goodsByKey) {
        if (snapshot != null && snapshot.containsKey(orderLineKey)) {
            Map<String, Object> frozen = snapshot.get(orderLineKey);
            cargos.add(new JdCargoProjection(
                    productName,
                    String.valueOf(frozen.get("goodsNo")),
                    ((Number) frozen.get("planQuantity")).intValue()));
            return;
        }
        ProviderSkuFacts goods = skuId == null ? null : goodsByKey.get(new ProviderSkuKey(providerId, skuId));
        JdCargoPlanner.Result planned = JdCargoPlanner.plan(
                skuId, orderLineKey, productName, unit, systemQuantity, null, null,
                goods == null ? null : goods.toPlannerGoods());
        if (planned instanceof JdCargoPlanner.Cargo cargo) {
            cargos.add(new JdCargoProjection(productName, cargo.goodsNo(), cargo.planQuantity()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseCargoSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return (List<Map<String, Object>>) (List<?>) objectMapper.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("submitted_cargo_snapshot JSON 无法解析", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return (Map<String, Object>) objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("provider_skus.external_codes JSON 无法解析", exception);
        }
    }

    /** 装载阶段产物：五个事实族的稳定索引，只读地供纯投影阶段消费。 */
    private record JdCargoFacts(
            Map<Long, List<RowLineLink>> linksByRow,
            Map<Long, List<ComponentLine>> componentsByLine,
            Map<Long, LineQuantity> quantityByLine,
            Map<ProviderSkuKey, ProviderSkuFacts> goodsByKey,
            Map<Long, Map<String, Map<String, Object>>> snapshotByShipment) {
    }

    /** 原始行 → 关联订单行血缘（raw_import_row_order_lines，按 partition_no 稳定排序）。 */
    private record RowLineLink(
            long rawRowId,
            int partitionNo,
            long orderLineId,
            String lineType,
            Long skuId,
            int lineNo,
            long providerId,
            String providerType,
            String productName,
            String unit) {
    }

    private record ComponentLine(
            long orderLineId,
            int componentNo,
            long skuId,
            int quantityPerBundle,
            String productName,
            String unit) {
    }

    private record LineQuantity(
            long orderLineId,
            int requestedQuantity,
            Integer instructedQuantity,
            Long shipmentId) {
    }

    private record ProviderSkuKey(long providerId, long skuId) {
    }

    private record ProviderSkuFacts(
            String providerSkuCode, String merchantSkuCode, Map<String, Object> externalCodes, boolean active) {

        JdCargoPlanner.Goods toPlannerGoods() {
            return new JdCargoPlanner.Goods(providerSkuCode, merchantSkuCode, externalCodes, active);
        }
    }

    /** 行投影货品：与建单 cargoInfos 同源的展示字段（JSON 序列化为 snake_case）。 */
    public record JdCargoProjection(String productName, String providerSkuCode, int planQuantity) {
    }
}
