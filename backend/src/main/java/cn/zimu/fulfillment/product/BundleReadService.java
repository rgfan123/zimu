package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleCandidate;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleComponent;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleDetail;
import cn.zimu.fulfillment.product.BundleReadQuery.BundleSummary;
import cn.zimu.fulfillment.product.BundleReadQuery.InventoryObservation;
import cn.zimu.fulfillment.product.BundleReadQuery.ProviderSummary;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 静态礼包只读查询用例；MCP 只负责参数校验与安全字段投影。
 *
 * <p>礼包履约方从当前组件 SKU 推导，不能把主表的 {@code fulfillment_provider_id=NULL}
 * 直接解释成“无履约方”：V43 规定多履约方礼包正是以 NULL 表示，并在下单时按组件履约方拆分。
 * 库存只返回各仓最新观测；没有观测时保留空列表，不补成零。
 */
@Service
public class BundleReadService implements BundleReadQuery {

    private static final String BUNDLE_FROM = " FROM app.product_bundles pb";

    private final JdbcTemplate jdbc;

    public BundleReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<BundleSummary> searchBundles(
            String status, Long providerId, String query, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = bundleFilters(status, providerId, query, args);
        long total = jdbc.queryForObject(
                "SELECT count(*)" + BUNDLE_FROM + where,
                Long.class,
                args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<BundleRow> rows = jdbc.query(
                """
                SELECT pb.id, pb.bundle_code, pb.bundle_name, pb.status,
                       count(bi.id) AS component_count,
                       count(bi.id) > 0 AND bool_and(COALESCE(s.active, false)) AS all_components_active
                FROM app.product_bundles pb
                LEFT JOIN app.bundle_items bi ON bi.bundle_id=pb.id
                LEFT JOIN app.skus s ON s.id=bi.sku_id
                """
                        + where
                        + " GROUP BY pb.id, pb.bundle_code, pb.bundle_name, pb.status"
                        + " ORDER BY pb.updated_at DESC, pb.id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> new BundleRow(
                        id(resultSet.getLong("id")),
                        resultSet.getString("bundle_code"),
                        resultSet.getString("bundle_name"),
                        resultSet.getString("status"),
                        resultSet.getInt("component_count"),
                        resultSet.getBoolean("all_components_active")),
                pageArgs.toArray());
        Map<String, List<ProviderSummary>> providers = providersFor(rows.stream().map(BundleRow::id).toList());
        List<BundleSummary> items = rows.stream()
                .map(row -> new BundleSummary(
                        row.id(),
                        row.bundleCode(),
                        row.bundleName(),
                        row.status(),
                        row.componentCount(),
                        row.allComponentsActive(),
                        providers.getOrDefault(row.id(), List.of())))
                .toList();
        return new PageResponse<>(items, page, size, total, totalPages(total, size));
    }

    @Transactional(readOnly = true)
    public BundleDetail getBundle(long bundleId) {
        List<BundleHeader> headers = jdbc.query(
                """
                SELECT id, bundle_code, bundle_name, category_id, barcode, description,
                       settlement_cost, status
                FROM app.product_bundles
                WHERE id=?
                """,
                (resultSet, rowNumber) -> new BundleHeader(
                        id(resultSet.getLong("id")),
                        resultSet.getString("bundle_code"),
                        resultSet.getString("bundle_name"),
                        nullableId(resultSet, "category_id"),
                        resultSet.getString("barcode"),
                        resultSet.getString("description"),
                        price(resultSet.getBigDecimal("settlement_cost")),
                        resultSet.getString("status")),
                bundleId);
        if (headers.isEmpty()) {
            throw BusinessException.notFound("礼包不存在: " + bundleId);
        }
        List<BundleComponent> components = jdbc.query(
                """
                SELECT bi.sort_no, bi.sku_id, bi.quantity_per_bundle,
                       s.sku_code, s.product_id, s.specification, s.unit, s.purchase_price, s.active,
                       p.product_code, p.product_name,
                       fp.id AS provider_id, fp.provider_code, fp.provider_name, fp.provider_type
                FROM app.bundle_items bi
                JOIN app.skus s ON s.id=bi.sku_id
                JOIN app.products p ON p.id=s.product_id
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE bi.bundle_id=?
                ORDER BY bi.sort_no, bi.id
                """,
                (resultSet, rowNumber) -> new BundleComponent(
                        resultSet.getInt("sort_no"),
                        id(resultSet.getLong("sku_id")),
                        resultSet.getString("sku_code"),
                        id(resultSet.getLong("product_id")),
                        resultSet.getString("product_code"),
                        resultSet.getString("product_name"),
                        resultSet.getString("specification"),
                        resultSet.getString("unit"),
                        quantity(resultSet.getBigDecimal("quantity_per_bundle")),
                        price(resultSet.getBigDecimal("purchase_price")),
                        resultSet.getBoolean("active"),
                        provider(resultSet)),
                bundleId);
        Map<String, ProviderSummary> providers = new LinkedHashMap<>();
        components.forEach(component -> providers.putIfAbsent(component.provider().id(), component.provider()));
        BundleHeader header = headers.getFirst();
        return new BundleDetail(
                header.id(),
                header.bundleCode(),
                header.bundleName(),
                header.categoryId(),
                header.barcode(),
                header.description(),
                header.settlementCost(),
                header.status(),
                components,
                !components.isEmpty() && components.stream().allMatch(BundleComponent::active),
                List.copyOf(providers.values()));
    }

    @Transactional(readOnly = true)
    public PageResponse<BundleCandidate> findCandidates(
            String query, Long providerId, String mappingStatus, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = candidateFilters(query, providerId, mappingStatus, args);
        String from = """
                FROM app.skus s
                JOIN app.products p ON p.id=s.product_id AND p.active
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id AND fp.active
                LEFT JOIN app.provider_skus ps
                  ON ps.fulfillment_provider_id=fp.id AND ps.sku_id=s.id AND ps.active
                """;
        long total = jdbc.queryForObject("SELECT count(*) " + from + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<CandidateRow> rows = jdbc.query(
                """
                SELECT s.id AS sku_id, s.sku_code, s.product_id, p.product_code, p.product_name,
                       s.specification, s.unit, s.purchase_price,
                       fp.id AS provider_id, fp.provider_code, fp.provider_name, fp.provider_type,
                       ps.provider_sku_code
                """
                        + from
                        + where
                        + " ORDER BY p.product_name, s.sku_code LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> new CandidateRow(
                        id(resultSet.getLong("sku_id")),
                        resultSet.getString("sku_code"),
                        id(resultSet.getLong("product_id")),
                        resultSet.getString("product_code"),
                        resultSet.getString("product_name"),
                        resultSet.getString("specification"),
                        resultSet.getString("unit"),
                        price(resultSet.getBigDecimal("purchase_price")),
                        provider(resultSet),
                        resultSet.getString("provider_sku_code")),
                pageArgs.toArray());
        Map<String, List<InventoryObservation>> observations = inventoryFor(rows.stream()
                .map(CandidateRow::skuId)
                .toList());
        List<BundleCandidate> items = rows.stream()
                .map(row -> new BundleCandidate(
                        row.skuId(),
                        row.skuCode(),
                        row.productId(),
                        row.productCode(),
                        row.productName(),
                        row.specification(),
                        row.unit(),
                        row.purchasePrice(),
                        row.provider(),
                        row.providerSkuCode(),
                        observations.getOrDefault(row.skuId(), List.of())))
                .toList();
        return new PageResponse<>(items, page, size, total, totalPages(total, size));
    }

    private static String bundleFilters(
            String status, Long providerId, String query, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null) {
            where.append(" AND pb.status=?");
            args.add(status);
        }
        if (providerId != null) {
            where.append("""
                     AND EXISTS (
                         SELECT 1 FROM app.bundle_items provider_item
                         JOIN app.skus provider_sku ON provider_sku.id=provider_item.sku_id
                         WHERE provider_item.bundle_id=pb.id
                           AND provider_sku.fulfillment_provider_id=?
                     )
                    """);
            args.add(providerId);
        }
        if (query != null) {
            String like = "%" + query + "%";
            where.append("""
                     AND (
                         pb.bundle_code ILIKE ? OR pb.bundle_name ILIKE ?
                         OR EXISTS (
                             SELECT 1 FROM app.bundle_items keyword_item
                             JOIN app.skus keyword_sku ON keyword_sku.id=keyword_item.sku_id
                             JOIN app.products keyword_product ON keyword_product.id=keyword_sku.product_id
                             WHERE keyword_item.bundle_id=pb.id
                               AND (keyword_sku.sku_code ILIKE ?
                                    OR keyword_sku.specification ILIKE ?
                                    OR keyword_product.product_name ILIKE ?)
                         )
                     )
                    """);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return where.toString();
    }

    private static String candidateFilters(
            String query, Long providerId, String mappingStatus, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE s.active");
        if (query != null) {
            String like = "%" + query + "%";
            where.append(" AND (s.sku_code ILIKE ? OR s.specification ILIKE ? OR p.product_name ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (providerId != null) {
            where.append(" AND fp.id=?");
            args.add(providerId);
        }
        if ("MAPPED".equals(mappingStatus)) {
            where.append(" AND ps.id IS NOT NULL");
        } else if ("UNMAPPED".equals(mappingStatus)) {
            where.append(" AND ps.id IS NULL");
        }
        return where.toString();
    }

    private Map<String, List<ProviderSummary>> providersFor(List<String> bundleIds) {
        if (bundleIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ProviderSummary>> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT DISTINCT bi.bundle_id, fp.id AS provider_id, fp.provider_code,
                       fp.provider_name, fp.provider_type
                FROM app.bundle_items bi
                JOIN app.skus s ON s.id=bi.sku_id
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE bi.bundle_id IN (
                """
                        + placeholders(bundleIds.size())
                        + ") ORDER BY bi.bundle_id, fp.id",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> result
                        .computeIfAbsent(id(resultSet.getLong("bundle_id")), ignored -> new ArrayList<>())
                        .add(provider(resultSet)),
                bundleIds.stream().map(Long::valueOf).toArray());
        return immutableLists(result);
    }

    private Map<String, List<InventoryObservation>> inventoryFor(List<String> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<InventoryObservation>> result = new LinkedHashMap<>();
        jdbc.query(
                """
                WITH latest AS (
                    SELECT DISTINCT ON (stock.fulfillment_provider_id, stock.sku_id, stock.warehouse_code)
                           stock.sku_id, stock.warehouse_code, stock.stock_num, stock.usable_num,
                           stock.quantity_unit, stock.source_type, stock.synced_at
                    FROM app.provider_stock_snapshots stock
                    WHERE stock.sku_id IN (
                """
                        + placeholders(skuIds.size())
                        + """
                    )
                    ORDER BY stock.fulfillment_provider_id, stock.sku_id, stock.warehouse_code,
                             stock.synced_at DESC, stock.id DESC
                )
                SELECT * FROM latest ORDER BY sku_id, warehouse_code
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> result
                        .computeIfAbsent(id(resultSet.getLong("sku_id")), ignored -> new ArrayList<>())
                        .add(new InventoryObservation(
                                resultSet.getString("warehouse_code"),
                                quantity(resultSet.getBigDecimal("stock_num")),
                                quantity(resultSet.getBigDecimal("usable_num")),
                                resultSet.getString("quantity_unit"),
                                instant(resultSet, "synced_at"),
                                resultSet.getString("source_type"))),
                skuIds.stream().map(Long::valueOf).toArray());
        return immutableLists(result);
    }

    private static <T> Map<String, List<T>> immutableLists(Map<String, List<T>> source) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    private static ProviderSummary provider(ResultSet resultSet) throws SQLException {
        return new ProviderSummary(
                id(resultSet.getLong("provider_id")),
                resultSet.getString("provider_code"),
                resultSet.getString("provider_name"),
                resultSet.getString("provider_type"));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int totalPages(long total, int size) {
        return total == 0 ? 0 : (int) ((total + size - 1) / size);
    }

    private static String id(long value) {
        return String.valueOf(value);
    }

    private static String nullableId(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : id(value);
    }

    private static String quantity(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String price(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record BundleRow(
            String id,
            String bundleCode,
            String bundleName,
            String status,
            int componentCount,
            boolean allComponentsActive) {}

    private record BundleHeader(
            String id,
            String bundleCode,
            String bundleName,
            String categoryId,
            String barcode,
            String description,
            String settlementCost,
            String status) {}

    private record CandidateRow(
            String skuId,
            String skuCode,
            String productId,
            String productCode,
            String productName,
            String specification,
            String unit,
            String purchasePrice,
            ProviderSummary provider,
            String providerSkuCode) {}

    @Override
    public List<ComponentSkuFact> componentSkuFacts(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(skuIds.size(), "?"));
        var byId = new java.util.HashMap<String, ComponentSkuFact>();
        jdbc.query(
                "SELECT s.id, s.sku_code, p.product_name, s.specification, s.active, s.purchase_price "
                        + "FROM app.skus s JOIN app.products p ON p.id = s.product_id "
                        + "WHERE s.id IN (" + placeholders + ")",
                resultSet -> {
                    byId.put(String.valueOf(resultSet.getLong("id")), new ComponentSkuFact(
                            String.valueOf(resultSet.getLong("id")),
                            resultSet.getString("sku_code"),
                            resultSet.getString("product_name"),
                            resultSet.getString("specification"),
                            resultSet.getBoolean("active"),
                            price(resultSet.getBigDecimal("purchase_price"))));
                },
                skuIds.toArray());
        // 按入参顺序回，缺失项如实缺席（调用方 fail-fast）
        return skuIds.stream()
                .map(id -> byId.get(String.valueOf(id)))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

}
