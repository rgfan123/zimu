package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.fulfillment.ShipmentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shipment source-sync 的唯一 JDBC 事实读取器。 */
@Component
public final class SourceSyncFactsReader {

    private final JdbcTemplate jdbc;

    public SourceSyncFactsReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Loaded load(long shipmentId) {
        return load(shipmentId, false);
    }

    public Loaded loadLocked(long shipmentId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("source-sync locked facts require an active transaction");
        }
        return load(shipmentId, true);
    }

    private Loaded load(long shipmentId, boolean lock) {
        String sql = HEADER_SQL + (lock ? " FOR NO KEY UPDATE OF s, o, ib" : "");
        Header header = jdbc.query(sql, rs -> rs.next() ? header(rs) : null, shipmentId);
        if (header == null) {
            throw BusinessException.notFound("BUSINESS 发货批次不存在: " + shipmentId);
        }
        List<Item> rows = jdbc.query(
                ITEMS_SQL + (lock ? " FOR UPDATE OF si, f, ol" : ""),
                (rs, rowNum) -> item(rs),
                header.importBatchId(), header.importBatchId(), shipmentId);
        return assemble(header, rows);
    }

    private Loaded assemble(Header header, List<Item> rows) {
        List<SourceSyncBlocker> blockers = new ArrayList<>();
        if (header.channel() != SourceChannel.JUFUBAO
                && header.channel() != SourceChannel.CAISHIXIAN) {
            block(blockers, "SOURCE_SYNC_CHANNEL_UNSUPPORTED", "source_channel",
                    "来源回传在线闭环仅支持聚福宝和彩食鲜");
        }
        if (header.confirmedAt() == null) block(blockers, "SOURCE_BATCH_NOT_CONFIRMED", "import_batch", "来源导入批次尚未确认");
        if (!ShipmentStatus.isShipped(header.shipmentStatus())) {
            block(blockers, "SHIPMENT_NOT_SHIPPED", "shipment_status", "Shipment 尚未形成已发货事实");
        }
        if (blank(header.trackingNumber()) || blank(header.carrierCode())) {
            block(blockers, "FORMAL_TRACKING_REQUIRED", "tracking", "Shipment 尚无正式物流公司和运单号");
        }
        if (!header.connectorEnabled()) block(blockers, "SOURCE_SYNC_CONNECTOR_DISABLED", "connector", "来源 Connector 未启用");
        if (!"API".equals(header.transportMode())) {
            block(blockers, "SOURCE_SYNC_ONLINE_TRANSPORT_REQUIRED", "connector.transport_mode", "来源 Connector 未启用 API 回传");
        }
        if (blank(header.carrierOutputValue())) {
            block(blockers, "SOURCE_SYNC_CARRIER_MAPPING_REQUIRED", "carrier", "内部物流公司缺少来源平台输出映射");
        }
        switch (header.projection().status()) {
            case SYNCING -> block(blockers, "SOURCE_SYNC_IN_PROGRESS", "sync_status", "该 Shipment 正在回传");
            case SYNCED -> block(blockers, "SOURCE_SYNC_ALREADY_SYNCED", "sync_status", "该 Shipment 已完成来源回传");
            case RECONCILIATION_REQUIRED -> block(blockers, "SOURCE_SYNC_RECONCILIATION_REQUIRED", "sync_status", "上次平台写结果未知，必须先对账");
            default -> { }
        }
        if (rows.isEmpty()) block(blockers, "SOURCE_SYNC_ITEMS_REQUIRED", "shipment_items", "Shipment 没有可回传明细");

        Map<Long, Integer> rawRowsByItem = new LinkedHashMap<>();
        Map<Long, Integer> itemsByRawRow = new LinkedHashMap<>();
        Set<String> sourceLines = new LinkedHashSet<>();
        BigDecimal orderedSource = BigDecimal.ZERO;
        BigDecimal shippedSource = BigDecimal.ZERO;
        BigDecimal internalShipped = BigDecimal.ZERO;
        boolean quantityValid = true;
        for (Item row : rows) {
            rawRowsByItem.merge(row.shipmentItemId(), 1, Integer::sum);
            itemsByRawRow.merge(row.rawRowId(), 1, Integer::sum);
            if (!blank(row.sourceLineRef())) sourceLines.add(row.sourceLineRef().trim());
            if (row.shippedQuantity() == null || row.shippedQuantity().compareTo(row.instructedQuantity()) != 0) {
                blockOnce(blockers, "SOURCE_SYNC_FULL_SHIPMENT_REQUIRED", "shipment_items", "P0 在线回传只支持全部实发的 Shipment");
            }
            if (!"FULLY_FULFILLED".equals(row.fulfillmentOutcome())) {
                blockOnce(blockers, "SOURCE_SYNC_FULL_FULFILLMENT_REQUIRED", "fulfillment.outcome", "P0 在线回传只支持全部履约的来源子单");
            }
            if (row.cumulativeShippedQuantity() == null
                    || row.cumulativeShippedQuantity().compareTo(row.requestedQuantity()) != 0) {
                blockOnce(blockers, "SOURCE_SYNC_CUMULATIVE_QUANTITY_INCOMPLETE", "fulfillment.cumulative_shipped_quantity",
                        "履约累计实发数量尚未完整覆盖请求数量");
            }
            if (row.cancelledQuantity() != null && row.cancelledQuantity().signum() != 0) {
                blockOnce(blockers, "SOURCE_SYNC_CANCELLED_REMAINING_UNSUPPORTED", "fulfillment.cancelled_quantity", "存在取消剩余量，必须走人工复核或文件降级");
            }
            if (row.shippedQuantity() != null) internalShipped = internalShipped.add(row.shippedQuantity());
            try {
                orderedSource = orderedSource.add(row.sourceQuantity() == null
                        ? toSourceUnits(row.requestedQuantity(), row.multiplier()) : integer(row.sourceQuantity()));
                shippedSource = shippedSource.add(toSourceUnits(row.shippedQuantity(), row.multiplier()));
            } catch (ArithmeticException exception) {
                quantityValid = false;
                blockOnce(blockers, "SOURCE_SYNC_QUANTITY_NOT_SOURCE_UNIT", "quantity", "内部数量无法精确还原为来源整数份数");
            }
        }
        if (rawRowsByItem.values().stream().anyMatch(count -> count != 1)) {
            block(blockers, "SOURCE_SYNC_LINEAGE_AMBIGUOUS", "source_line_ref", "Shipment 明细与来源行血缘不是一一对应");
        }
        if (itemsByRawRow.values().stream().anyMatch(count -> count != 1)) {
            block(blockers, "SOURCE_SYNC_RAW_ROW_REUSED", "raw_import_row",
                    "同一来源原始行映射到多个 Shipment 明细，P0 禁止重复累计来源份数");
        }
        if (sourceLines.size() != 1) {
            block(blockers, "SOURCE_SYNC_SINGLE_SOURCE_LINE_REQUIRED", "source_line_ref", "P0 在线回传要求整个 Shipment 只对应一个来源子单");
        }
        String sourceLine = sourceLines.size() == 1 ? sourceLines.iterator().next() : null;
        if (sourceLine != null && shipmentCount(header.importBatchId(), sourceLine) != 1) {
            block(blockers, "SOURCE_SYNC_MULTI_SHIPMENT_UNSUPPORTED", "shipment_id", "同一来源子单存在多个 Shipment，必须走人工复核或文件降级");
        }
        if (quantityValid && orderedSource.compareTo(shippedSource) != 0) {
            block(blockers, "SOURCE_SYNC_SOURCE_QUANTITY_INCOMPLETE", "source_quantity",
                    "来源下单份数与拟回传实发份数不一致，禁止 P0 在线回传");
        }
        SourceSyncFacts facts = new SourceSyncFacts(
                header.shipmentId(), header.orderId(), header.channel(), header.sourceRef(), sourceLine,
                header.receiverName(), header.receiverPhone(), header.receiverAddress(),
                quantityValid ? orderedSource : null, quantityValid ? shippedSource : null, internalShipped,
                rows.stream().allMatch(row -> "FULLY_FULFILLED".equals(row.fulfillmentOutcome())) ? "FULLY_FULFILLED" : "INCOMPLETE",
                header.carrierCode(), header.carrierName(), header.carrierOutputValue(), header.trackingNumber());
        SourceSyncReconciliationIntentView reconciliationIntent =
                header.projection().status() == SourceSyncStatus.RECONCILIATION_REQUIRED
                        ? new SourceSyncReconciliationIntentView(
                                header.intentCheckHash(),
                                header.intentSourceLineRef(),
                                header.intentCarrierCode(),
                                header.intentTrackingNumber(),
                                header.projection().lockVersion())
                        : null;
        return new Loaded(facts, List.copyOf(blockers), header.projection(), reconciliationIntent);
    }

    private int shipmentCount(long batchId, String sourceLineRef) {
        Integer count = jdbc.queryForObject(MULTI_SHIPMENT_SQL, Integer.class, batchId, batchId, sourceLineRef);
        return count == null ? 0 : count;
    }

    private static BigDecimal toSourceUnits(BigDecimal internal, BigDecimal multiplier) {
        if (internal == null || multiplier == null || multiplier.signum() <= 0) {
            throw new ArithmeticException("missing quantity or multiplier snapshot");
        }
        return internal.divide(multiplier, 0, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal integer(BigDecimal value) {
        return value.setScale(0, RoundingMode.UNNECESSARY);
    }

    private static void block(List<SourceSyncBlocker> blockers, String code, String field, String message) {
        blockers.add(new SourceSyncBlocker(code, field, message));
    }

    private static void blockOnce(List<SourceSyncBlocker> blockers, String code, String field, String message) {
        if (blockers.stream().noneMatch(item -> code.equals(item.code()))) block(blockers, code, field, message);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static Header header(ResultSet rs) throws SQLException {
        return new Header(
                rs.getLong("shipment_id"), rs.getLong("order_id"), rs.getLong("import_batch_id"),
                SourceChannel.valueOf(rs.getString("source_channel")), rs.getString("source_ref"),
                rs.getString("shipment_status"), rs.getString("receiver_name"), rs.getString("receiver_phone"),
                rs.getString("receiver_address"), rs.getObject("confirmed_at", OffsetDateTime.class),
                rs.getString("carrier_code"), rs.getString("carrier_name"), rs.getString("tracking_number"),
                rs.getBoolean("connector_enabled"), rs.getString("transport_mode"), rs.getString("carrier_output_value"),
                new SourceSyncProjection(
                        SourceSyncStatus.valueOf(rs.getString("sync_status")), rs.getInt("attempt_count"),
                        rs.getLong("sync_lock_version"), rs.getString("last_error_code"),
                        rs.getString("last_error_message"), rs.getObject("synced_at", OffsetDateTime.class)),
                rs.getString("sync_intent_check_hash"),
                rs.getString("sync_intent_source_line_ref"),
                rs.getString("sync_intent_carrier_code"),
                rs.getString("sync_intent_tracking_number"));
    }

    private static Item item(ResultSet rs) throws SQLException {
        return new Item(
                rs.getLong("shipment_item_id"), rs.getLong("raw_row_id"), rs.getBigDecimal("instructed_quantity"),
                rs.getBigDecimal("shipped_quantity"), rs.getBigDecimal("requested_quantity"),
                rs.getBigDecimal("cumulative_shipped_quantity"), rs.getBigDecimal("cancelled_quantity"),
                rs.getString("fulfillment_outcome"),
                rs.getBigDecimal("source_quantity_snapshot"), rs.getBigDecimal("mapping_multiplier_snapshot"),
                rs.getString("source_line_ref"));
    }

    public record Loaded(
            SourceSyncFacts facts,
            List<SourceSyncBlocker> blockers,
            SourceSyncProjection projection,
            SourceSyncReconciliationIntentView reconciliationIntent) {

        public Loaded(
                SourceSyncFacts facts,
                List<SourceSyncBlocker> blockers,
                SourceSyncProjection projection) {
            this(facts, blockers, projection, null);
        }
    }
    private record Header(long shipmentId, long orderId, long importBatchId, SourceChannel channel, String sourceRef,
            String shipmentStatus, String receiverName, String receiverPhone, String receiverAddress,
            OffsetDateTime confirmedAt, String carrierCode, String carrierName, String trackingNumber,
            boolean connectorEnabled, String transportMode, String carrierOutputValue, SourceSyncProjection projection,
            String intentCheckHash, String intentSourceLineRef, String intentCarrierCode,
            String intentTrackingNumber) {}
    private record Item(long shipmentItemId, long rawRowId, BigDecimal instructedQuantity, BigDecimal shippedQuantity,
            BigDecimal requestedQuantity, BigDecimal cumulativeShippedQuantity,
            BigDecimal cancelledQuantity, String fulfillmentOutcome,
            BigDecimal sourceQuantity, BigDecimal multiplier, String sourceLineRef) {}

    private static final String HEADER_SQL = """
            SELECT s.id shipment_id, s.order_id, s.shipment_status,
                   s.receiver_name_snapshot receiver_name, s.receiver_phone_snapshot receiver_phone,
                   s.receiver_address_snapshot receiver_address, o.source_ref,
                   ib.id import_batch_id, ib.confirmed_at,
                   source.effective_source_channel source_channel,
                   t.logistics_company_code carrier_code, t.logistics_company_name carrier_name, t.tracking_number,
                   COALESCE(cc.enabled, false) connector_enabled, cc.transport_mode,
                   cc.config #>> ARRAY['carrier_mappings', t.logistics_company_code] carrier_output_value,
                   COALESCE(ss.sync_status, 'PENDING') sync_status,
                   COALESCE(ss.attempt_count, 0) attempt_count,
                   COALESCE(ss.lock_version, 0) sync_lock_version,
                   ss.last_error_code, ss.last_error_message, ss.synced_at,
                   ss.check_hash sync_intent_check_hash,
                   ss.source_line_ref sync_intent_source_line_ref,
                   ss.carrier_code sync_intent_carrier_code,
                   ss.tracking_number sync_intent_tracking_number
            FROM app.shipments s
            JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
            JOIN app.import_batches ib ON ib.id=o.source_import_batch_id AND ib.batch_type='SOURCE_ORDER'
            JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
            LEFT JOIN app.connector_configs cc ON cc.source_channel=source.effective_source_channel
            LEFT JOIN app.trackings t ON t.shipment_id=s.id
            LEFT JOIN app.shipment_syncs ss ON ss.shipment_id=s.id AND ss.source_channel=source.effective_source_channel
            WHERE s.id=?
            """;

    private static final String ITEMS_SQL = """
            WITH raw_line_links AS (
                SELECT rir.id raw_row_id, rir.order_line_id FROM app.raw_import_rows rir
                WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                UNION
                SELECT rirol.raw_import_row_id, rirol.order_line_id
                FROM app.raw_import_row_order_lines rirol
                JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id WHERE rir.import_batch_id=?
            )
            SELECT si.id shipment_item_id, rir.id raw_row_id, si.instructed_quantity, si.shipped_quantity,
                   f.requested_quantity, f.cumulative_shipped_quantity,
                   f.cancelled_quantity, f.outcome fulfillment_outcome,
                   ol.source_quantity_snapshot, ol.mapping_multiplier_snapshot,
                   rir.raw_cells->>'source_line_ref' source_line_ref
            FROM app.shipment_items si
            JOIN app.fulfillments f ON f.id=si.fulfillment_id
            JOIN app.order_lines ol ON ol.id=f.order_line_id
            JOIN raw_line_links rll ON rll.order_line_id=ol.id
            JOIN app.raw_import_rows rir ON rir.id=rll.raw_row_id AND rir.status='ACCEPTED'
            WHERE si.shipment_id=? ORDER BY si.id, rir.id
            """;

    private static final String MULTI_SHIPMENT_SQL = """
            WITH raw_line_links AS (
                SELECT rir.id raw_row_id, rir.order_line_id FROM app.raw_import_rows rir
                WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                UNION
                SELECT rirol.raw_import_row_id, rirol.order_line_id
                FROM app.raw_import_row_order_lines rirol
                JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id WHERE rir.import_batch_id=?
            )
            SELECT COUNT(DISTINCT si.shipment_id)
            FROM raw_line_links rll
            JOIN app.raw_import_rows rir ON rir.id=rll.raw_row_id
            JOIN app.fulfillments f ON f.order_line_id=rll.order_line_id
            JOIN app.shipment_items si ON si.fulfillment_id=f.id
            WHERE rir.raw_cells->>'source_line_ref'=?
            """;
}
