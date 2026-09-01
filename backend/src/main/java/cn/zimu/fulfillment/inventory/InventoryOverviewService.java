package cn.zimu.fulfillment.inventory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读取已落库的最新库存观测，不将无观测补成零，也不替代履约方的库存决策。
 *
 * <p>当传入 warehouseCode 时，它限定的是目标观测范围，而不是主数据 SKU 范围。
 * SKU 在目标仓没有事实时仍返回 NOT_OBSERVED，绝不把别仓事实或缺行猜成零库存。
 * 覆盖摘要始终描述完整筛选范围，而不是当前分页。
 */
@Service
public class InventoryOverviewService {

    private static final String BASE_QUERY_PREFIX = """
            WITH latest_snapshots AS (
                SELECT DISTINCT ON (ps.fulfillment_provider_id, ps.sku_id, ps.warehouse_code)
                       ps.fulfillment_provider_id, ps.sku_id, ps.warehouse_code,
                       ps.stock_num, ps.usable_num, ps.quantity_unit, ps.source_type, ps.synced_at
                FROM app.provider_stock_snapshots ps
            """;

    private static final String BASE_QUERY_SUFFIX = """
                ORDER BY ps.fulfillment_provider_id, ps.sku_id, ps.warehouse_code,
                         ps.synced_at DESC, ps.id DESC
            ), inventory_rows AS (
                SELECT fp.id AS provider_id, fp.provider_code, fp.provider_name, fp.provider_type,
                       s.id AS sku_id, s.sku_code, p.product_name, s.specification, s.unit,
                       ls.warehouse_code, ls.stock_num, ls.usable_num, ls.quantity_unit,
                       ls.source_type, ls.synced_at
                FROM app.skus s
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id AND fp.active
                JOIN app.products p ON p.id=s.product_id
                LEFT JOIN latest_snapshots ls
                  ON ls.fulfillment_provider_id=fp.id AND ls.sku_id=s.id
                WHERE s.active
            )
            """;

    private final JdbcTemplate jdbc;
    private final Duration freshnessThreshold;

    public InventoryOverviewService(
            JdbcTemplate jdbc,
            @Value("${app.inventory.freshness-threshold:PT15M}") Duration freshnessThreshold) {
        this.jdbc = jdbc;
        this.freshnessThreshold = freshnessThreshold;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InventoryOverviewResponse overview(
            int page, int size, Long providerId, Long skuId, String warehouseCode) {
        String baseQuery = baseQuery(warehouseCode);
        List<Object> baseArgs = new ArrayList<>();
        if (warehouseCode != null) {
            baseArgs.add(warehouseCode);
        }
        List<Object> scopeArgs = new ArrayList<>();
        String filters = filters(scopeArgs, providerId, skuId);
        List<Object> filterArgs = new ArrayList<>(baseArgs);
        filterArgs.addAll(scopeArgs);
        long total = jdbc.queryForObject(
                baseQuery + "SELECT count(*) FROM inventory_rows WHERE 1=1" + filters,
                Long.class,
                filterArgs.toArray());

        List<Object> pageArgs = new ArrayList<>(filterArgs);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<InventoryOverviewItem> items = jdbc.query(
                baseQuery
                        + "SELECT * FROM inventory_rows WHERE 1=1"
                        + filters
                        + " ORDER BY provider_code, sku_code, warehouse_code NULLS LAST LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> item(resultSet, freshnessThreshold),
                pageArgs.toArray());

        List<Object> coverageArgs = new ArrayList<>(baseArgs);
        coverageArgs.add(OffsetDateTime.now(ZoneOffset.UTC).minus(freshnessThreshold));
        coverageArgs.addAll(scopeArgs);
        InventoryCoverage coverage = jdbc.query(
                baseQuery
                        + """
                        SELECT count(DISTINCT provider_id) AS provider_count,
                               count(DISTINCT provider_id) FILTER (WHERE synced_at IS NOT NULL) AS observed_provider_count,
                               count(DISTINCT sku_id) AS sku_count,
                               count(DISTINCT sku_id) FILTER (WHERE synced_at IS NOT NULL) AS observed_sku_count,
                               count(DISTINCT warehouse_code) FILTER (WHERE synced_at IS NOT NULL) AS warehouse_count,
                               max(synced_at) AS latest_observed_at,
                               count(*) FILTER (WHERE synced_at IS NOT NULL AND synced_at < ?) AS stale_count,
                               min(synced_at) FILTER (WHERE synced_at IS NOT NULL) AS oldest_observed_at
                        FROM inventory_rows WHERE 1=1
                        """
                        + filters,
                resultSet -> resultSet.next()
                        ? coverage(resultSet, freshnessThreshold)
                        : emptyCoverage(freshnessThreshold),
                coverageArgs.toArray());

        return new InventoryOverviewResponse(
                items,
                page,
                size,
                total,
                total == 0 ? 0 : (int) ((total + size - 1) / size),
                coverage);
    }

    private static String baseQuery(String warehouseCode) {
        return BASE_QUERY_PREFIX
                + (warehouseCode == null ? "" : " WHERE ps.warehouse_code=?\n")
                + BASE_QUERY_SUFFIX;
    }

    private static String filters(List<Object> args, Long providerId, Long skuId) {
        StringBuilder sql = new StringBuilder();
        if (providerId != null) {
            sql.append(" AND provider_id=?");
            args.add(providerId);
        }
        if (skuId != null) {
            sql.append(" AND sku_id=?");
            args.add(skuId);
        }
        return sql.toString();
    }

    private static InventoryOverviewItem item(ResultSet resultSet, Duration freshnessThreshold) throws SQLException {
        Integer total = resultSet.getObject("stock_num", Integer.class);
        Integer available = resultSet.getObject("usable_num", Integer.class);
        Instant observedAt = instant(resultSet, "synced_at");
        boolean observed = observedAt != null;
        long ageSeconds = observed
                ? Math.max(0, Instant.now().getEpochSecond() - observedAt.getEpochSecond())
                : 0;
        return new InventoryOverviewItem(
                id(resultSet.getLong("provider_id")),
                resultSet.getString("provider_code"),
                resultSet.getString("provider_name"),
                resultSet.getString("provider_type"),
                id(resultSet.getLong("sku_id")),
                resultSet.getString("sku_code"),
                resultSet.getString("product_name"),
                resultSet.getString("specification"),
                resultSet.getString("unit"),
                observed ? resultSet.getString("quantity_unit") : null,
                resultSet.getString("warehouse_code"),
                observed ? "OBSERVED" : "NOT_OBSERVED",
                total,
                available,
                observed ? total - available : null,
                observedAt,
                observed ? ageSeconds : null,
                observed ? (ageSeconds <= freshnessThreshold.toSeconds() ? "CURRENT" : "STALE") : "NOT_OBSERVED",
                observed ? resultSet.getString("source_type") : null);
    }

    private static InventoryCoverage coverage(ResultSet resultSet, Duration freshnessThreshold) throws SQLException {
        long providerCount = resultSet.getLong("provider_count");
        long observedProviderCount = resultSet.getLong("observed_provider_count");
        long skuCount = resultSet.getLong("sku_count");
        long observedSkuCount = resultSet.getLong("observed_sku_count");
        return new InventoryCoverage(
                providerCount,
                observedProviderCount,
                skuCount,
                observedSkuCount,
                resultSet.getLong("warehouse_count"),
                instant(resultSet, "latest_observed_at"),
                resultSet.getLong("stale_count"),
                instant(resultSet, "oldest_observed_at"),
                observedProviderCount < providerCount || observedSkuCount < skuCount,
                freshnessThreshold.toString());
    }

    private static InventoryCoverage emptyCoverage(Duration freshnessThreshold) {
        return new InventoryCoverage(0, 0, 0, 0, 0, null, 0, null, false, freshnessThreshold.toString());
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String id(long value) {
        return String.valueOf(value);
    }
}
