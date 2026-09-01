package cn.zimu.fulfillment.inventory;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects one inventory business object into its cached observation and explicitly integrated
 * low-frequency provider capabilities. It never invokes a provider API or treats missing data as zero.
 */
@Service
public class InventoryDetailsService {

    private static final List<InventoryDetailTool> BATCH_TOOLS = List.of(
            new InventoryDetailTool("JD_BATCH_CHANGES", "批次异动"),
            new InventoryDetailTool("JD_LEVEL_CHANGES", "库存级别异动"),
            new InventoryDetailTool("JD_SHELF_LIFE_GOODS", "效期商品"),
            new InventoryDetailTool("JD_SHELF_LIFE_INVENTORY", "效期库存"));
    private static final List<InventoryDetailTool> FLOW_TOOLS = List.of(
            new InventoryDetailTool("JD_SHOP_STOCK_FLOW", "库存流水"));
    private static final List<InventoryDetailTool> SERIAL_TOOLS = List.of(
            new InventoryDetailTool("JD_SERIAL_CONDITION", "序列号条件查询"),
            new InventoryDetailTool("JD_SERIAL_INSIDE", "在库序列号"));

    private final JdbcTemplate jdbc;
    private final Duration freshnessThreshold;
    private final String jdClientMode;

    public InventoryDetailsService(
            JdbcTemplate jdbc,
            @Value("${app.inventory.freshness-threshold:PT15M}") Duration freshnessThreshold,
            @Value("${app.jd.client-mode:MOCK}") String jdClientMode) {
        this.jdbc = jdbc;
        this.freshnessThreshold = freshnessThreshold;
        this.jdClientMode = normalizedRuntimeMode(jdClientMode);
    }

    @Transactional(readOnly = true)
    public InventoryDetailsResponse details(Long providerId, Long skuId, String warehouseCode) {
        Instant queryTime = Instant.now();
        List<DetailRow> rows = jdbc.query(
                """
                SELECT fp.id AS provider_id, fp.provider_code, fp.provider_name, fp.provider_type,
                       s.id AS sku_id, s.sku_code, p.product_name, s.specification, s.unit,
                       ps.provider_sku_code,
                       snapshot.warehouse_code, snapshot.stock_num, snapshot.usable_num,
                       snapshot.quantity_unit, snapshot.source_type, snapshot.synced_at
                FROM app.skus s
                JOIN app.fulfillment_providers fp
                  ON fp.id=s.fulfillment_provider_id AND fp.active
                JOIN app.products p ON p.id=s.product_id
                LEFT JOIN app.provider_skus ps
                  ON ps.fulfillment_provider_id=fp.id AND ps.sku_id=s.id AND ps.active
                LEFT JOIN LATERAL (
                    SELECT ss.warehouse_code, ss.stock_num, ss.usable_num,
                           ss.quantity_unit, ss.source_type, ss.synced_at
                    FROM app.provider_stock_snapshots ss
                    WHERE ss.fulfillment_provider_id=fp.id
                      AND ss.sku_id=s.id
                      AND (CAST(? AS varchar) IS NULL OR ss.warehouse_code=?)
                    ORDER BY ss.synced_at DESC, ss.id DESC
                    LIMIT 1
                ) snapshot ON true
                WHERE fp.id=? AND s.id=? AND s.active
                """,
                (resultSet, rowNumber) -> row(resultSet),
                warehouseCode,
                warehouseCode,
                providerId,
                skuId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("库存明细对象不存在");
        }

        DetailRow row = rows.getFirst();
        String resolvedWarehouse = warehouseCode == null ? row.warehouseCode() : warehouseCode;
        InventoryDetailContext context = new InventoryDetailContext(
                id(row.providerId()),
                row.providerCode(),
                row.providerName(),
                row.providerType(),
                id(row.skuId()),
                row.skuCode(),
                row.productName(),
                row.specification(),
                row.unit(),
                row.providerSkuCode(),
                resolvedWarehouse);
        return new InventoryDetailsResponse(
                context,
                observation(row, queryTime),
                queryTime,
                freshnessThreshold.toString(),
                capabilities(row));
    }

    private InventoryDetailObservation observation(DetailRow row, Instant queryTime) {
        if (row.observedAt() == null) {
            return new InventoryDetailObservation(
                    "NOT_OBSERVED", null, null, null, null, null, null, null,
                    "NOT_OBSERVED", null, "NO_OBSERVATION");
        }
        long ageSeconds = Math.max(0, queryTime.getEpochSecond() - row.observedAt().getEpochSecond());
        return new InventoryDetailObservation(
                "OBSERVED",
                row.totalQuantity(),
                row.availableQuantity(),
                row.totalQuantity() - row.availableQuantity(),
                knownQuantityUnit(row.quantityUnit()),
                row.observedAt(),
                ageSeconds,
                row.observedAt().plus(freshnessThreshold),
                ageSeconds <= freshnessThreshold.toSeconds() ? "CURRENT" : "STALE",
                knownSourceType(row.sourceType()),
                "CACHED_SNAPSHOT");
    }

    private List<InventoryDetailCapability> capabilities(DetailRow row) {
        if (!"JD_WAREHOUSE".equals(row.providerType())) {
            return List.of(
                    unavailable("BATCH_AND_SHELF_LIFE", "批次 / 库存水位变化 / 效期"),
                    unavailable("INVENTORY_FLOW", "库存流水"),
                    unavailable("SERIAL_NUMBER", "序列号"));
        }
        if (row.providerSkuCode() == null || row.providerSkuCode().isBlank()) {
            return List.of(
                    missingContext("BATCH_AND_SHELF_LIFE", "批次 / 库存水位变化 / 效期"),
                    missingContext("INVENTORY_FLOW", "库存流水"),
                    missingContext("SERIAL_NUMBER", "序列号"));
        }
        return List.of(
                integrated("BATCH_AND_SHELF_LIFE", "批次 / 库存水位变化 / 效期", BATCH_TOOLS),
                integrated("INVENTORY_FLOW", "库存流水", FLOW_TOOLS),
                integrated("SERIAL_NUMBER", "序列号", SERIAL_TOOLS));
    }

    private InventoryDetailCapability integrated(
            String group, String label, List<InventoryDetailTool> tools) {
        return new InventoryDetailCapability(
                group,
                label,
                "INTEGRATED",
                jdClientMode,
                "JD_ISC_READ_ONLY",
                "已接入京东 ISC 只读查询；查询结果不会改写总库存事实。",
                tools);
    }

    private static InventoryDetailCapability unavailable(String group, String label) {
        return new InventoryDetailCapability(
                group,
                label,
                "NOT_INTEGRATED",
                "NOT_APPLICABLE",
                null,
                "当前履约方尚未接入该类专业库存查询。",
                List.of());
    }

    private InventoryDetailCapability missingContext(String group, String label) {
        return new InventoryDetailCapability(
                group,
                label,
                "CONTEXT_MISSING",
                jdClientMode,
                "JD_ISC_READ_ONLY",
                "当前 SKU 缺少已启用的京东商品编码映射，不能发起对象化查询。",
                List.of());
    }

    private static DetailRow row(ResultSet resultSet) throws SQLException {
        return new DetailRow(
                resultSet.getLong("provider_id"),
                resultSet.getString("provider_code"),
                resultSet.getString("provider_name"),
                resultSet.getString("provider_type"),
                resultSet.getLong("sku_id"),
                resultSet.getString("sku_code"),
                resultSet.getString("product_name"),
                resultSet.getString("specification"),
                resultSet.getString("unit"),
                resultSet.getString("provider_sku_code"),
                resultSet.getString("warehouse_code"),
                resultSet.getInt("stock_num"),
                resultSet.getInt("usable_num"),
                resultSet.getString("quantity_unit"),
                resultSet.getString("source_type"),
                instant(resultSet, "synced_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String knownQuantityUnit(String value) {
        return switch (value == null ? "" : value) {
            case "JD_PIECE", "INTERNAL_UNIT", "UNKNOWN" -> value;
            default -> "UNKNOWN";
        };
    }

    private static String knownSourceType(String value) {
        return switch (value == null ? "" : value) {
            case "JD_ISC_QUERY_STOCK", "NORMALIZED_PROVIDER_SNAPSHOT", "UNKNOWN" -> value;
            default -> "UNKNOWN";
        };
    }

    private static String normalizedRuntimeMode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return "REAL".equals(normalized) || "MOCK".equals(normalized) ? normalized : "UNKNOWN";
    }

    private static String id(long value) {
        return String.valueOf(value);
    }

    private record DetailRow(
            long providerId,
            String providerCode,
            String providerName,
            String providerType,
            long skuId,
            String skuCode,
            String productName,
            String specification,
            String unit,
            String providerSkuCode,
            String warehouseCode,
            int totalQuantity,
            int availableQuantity,
            String quantityUnit,
            String sourceType,
            Instant observedAt) {}
}
