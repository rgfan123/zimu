package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.ContinuationExportGenerator;
import cn.zimu.fulfillment.order.ReadySourceBatchExporter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将已经过订单应用层门禁的行按单一履约方生成不可变文件。 */
@Service
public class ProviderFileService implements ContinuationExportGenerator, ReadySourceBatchExporter {

    private static final ClassPathResource BUILT_IN_JD_TEMPLATE =
            new ClassPathResource("templates/jd-cold-chain-order-template.xlsx");

    public static final List<String> THIRD_PARTY_HEADERS = List.of(
            "导出批次号", "出库单号", "导出明细号", "履约方编码", "履约方名称", "内部订单号",
            "来源渠道", "来源订单号", "订单行号", "礼包分组标识", "收件人", "电话", "地址", "履约方SKU编码",
            "品名", "规格", "单位", "请求发货数量", "结果", "实际发货数量", "快递公司", "物流单号", "发货时间", "异常原因");

    static final List<String> JD_HEADERS = List.of(
            "*isv出库单号", "*ISV来源编号", "*事业部编号", "*店铺编号", "青龙业主号", "*仓库编号", "*承运商编号",
            "*授权码pin", "销售平台订单号", "*销售平台来源", "销售平台下单时间", "订单类型", "*订单标记位", "*收货人姓名",
            "*收货人手机", "收货人电话", "收货人电话邮箱", "收货人省", "收货人市", "收货人县", "收货人镇", "*收货人地址", "收货人邮编",
            "商家门店编号", "是否地址解析", "期望发货时间", "订单应收金额", "客户留言", "商家留言", "模板备注", "三方运单号", "大头笔", "顺丰E标",
            "业务类型", "目的地代码", "目的地名称", "发件网点代码", "发件网点名称", "寄件方式", "收件方式", "预约配送时间", "运费支付方式", "月结账号", "是否保价",
            "保价声明价值", "寄托物", "预约号", "入仓时间", "进仓备注", "签单返还收件人名称", "签单返还收件人电话", "签单返还收件人手机", "签单返还收件人地址",
            "验货方式", "*京东商品编号", "*商家商品编号", "安维标识", "*商品金额", "*商品的出库数量", "商品行号", "包装细数", "包装批号", "采购单号", "生产日期", "到期日期",
            "生产批号", "商品等级", "计量单位", "是否卸车(仓配冷链整车冷链城配)", "有无动物检疫证", "车型", "派送服务", "仓配产品", "送仓类型", "是否送货入仓", "商家三方", "商家意愿");

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter JD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(SHANGHAI);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ContentAddressedFileStore fileStore;
    private final AuditLogService auditLogService;
    private final FulfillmentExportWecomService wecomExportService;

    ProviderFileService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ContentAddressedFileStore fileStore,
            AuditLogService auditLogService,
            FulfillmentExportWecomService wecomExportService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fileStore = fileStore;
        this.auditLogService = auditLogService;
        this.wecomExportService = wecomExportService;
    }

    @Transactional
    List<Long> generateForSourceBatch(long sourceBatchId, String operator) {
        return (List<Long>) routeForSourceBatch(sourceBatchId, operator).get("file_export_ids");
    }

    /**
     * 来源批次确认的履约路由（jd-real-sdk-switch 05）：按履约方显式配置分流。
     *
     * <p>京东履约方 {@code config.outboundMode=SDK} 时只创建发货批次（Shipment + ShipmentItems），
     * 不生成导单文件、不推进订单阶段——建单由确认事务提交后的 SDK 直连完成，失败留痕且不阻断批次确认；
     * 缺省或显式 {@code FILE} 保持既有导单文件路径（回退不改变历史批次处置方式）。
     * 第三方履约方始终走文件。返回 {@code {file_export_ids, jd_sdk_shipment_ids}}。
     */
    @Transactional
    Map<String, Object> routeForSourceBatch(long sourceBatchId, String operator) {
        List<ExportRow> rows = candidateRows(sourceBatchId);
        Map<Long, List<ExportRow>> byProvider = rows.stream()
                .collect(Collectors.groupingBy(ExportRow::providerId, LinkedHashMap::new, Collectors.toList()));
        List<Long> fileExportIds = new ArrayList<>();
        List<Long> jdSdkShipmentIds = new ArrayList<>();
        for (Map.Entry<Long, List<ExportRow>> entry : byProvider.entrySet()) {
            if ("JD_WAREHOUSE".equals(entry.getValue().getFirst().providerType())) {
                List<ExportRow> eligible = entry.getValue().stream()
                        .filter(this::jdQuantityIsPositiveInteger)
                        .toList();
                entry.getValue().stream()
                        .filter(row -> !jdQuantityIsPositiveInteger(row))
                        .forEach(row -> markJdQuantityReview(sourceBatchId, row));
                if (eligible.isEmpty()) {
                    continue;
                }
                if ("SDK".equals(outboundMode(entry.getKey()))) {
                    jdSdkShipmentIds.addAll(createJdShipments(entry.getKey(), eligible));
                } else {
                    fileExportIds.add(generateJd(entry.getKey(), eligible, operator));
                }
            } else {
                fileExportIds.add(generateThirdParty(entry.getKey(), entry.getValue(), operator));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file_export_ids", fileExportIds);
        result.put("jd_sdk_shipment_ids", jdSdkShipmentIds);
        return result;
    }

    /**
     * 把已经人工确认且完成映射的企业微信订单接回京东 Shipment pipeline。
     *
     * <p>该接缝只创建本地 Shipment/ShipmentItem，不调用京东；地址确认、实时库存、京东建单与
     * 运单回填仍由既有 Shipment 级公开命令分别完成。当前只接受全部订单行均为京东普通单品的
     * 订单，任何第三方、礼包、复核、缺映射或非整数数量均失败关闭。
     */
    @Transactional
    ReadyOrderRoute routeReadyWecomOrder(long orderId, long expectedOrderVersion) {
        ReadyOrder header = lockReadyOrder(orderId);
        if (header.version() != expectedOrderVersion) {
            throw BusinessException.conflict("VERSION_CONFLICT", "订单已更新，请刷新后重试");
        }
        if (!"WECOM".equals(header.sourceChannel())) {
            throw BusinessException.unprocessable(
                    "ORDER_ROUTING_SOURCE_UNSUPPORTED", "该入口只处理已确认的企业微信订单");
        }
        if (header.sourceImportBatchId() != null) {
            throw BusinessException.unprocessable(
                    "ORDER_ROUTING_BATCH_UNSUPPORTED", "来源批次订单必须通过批次确认路由");
        }
        if (!"SKU_MAPPED".equals(header.orderStatus())) {
            throw BusinessException.conflict(
                    "ORDER_ROUTING_STATUS_INVALID", "订单必须处于已完成 SKU 映射状态");
        }
        Boolean openReview = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app.review_cases WHERE order_id=? AND status='OPEN')",
                Boolean.class,
                orderId);
        if (Boolean.TRUE.equals(openReview)) {
            throw BusinessException.conflict("ORDER_ROUTING_REVIEW_OPEN", "订单仍有开放复核事项");
        }
        Boolean alreadyRouted = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app.shipment_items si "
                        + "JOIN app.fulfillments f ON f.id=si.fulfillment_id "
                        + "JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=?)",
                Boolean.class,
                orderId);
        if (Boolean.TRUE.equals(alreadyRouted)) {
            throw BusinessException.conflict("ORDER_ALREADY_ROUTED", "订单已经生成发货批次，禁止重复路由");
        }

        List<ExportRow> rows = readyWecomJdRows(orderId);
        long distinctLines = rows.stream().map(ExportRow::orderLineId).distinct().count();
        if (rows.isEmpty() || rows.size() != distinctLines || distinctLines != header.lineCount()) {
            throw BusinessException.unprocessable(
                    "ORDER_ROUTING_NOT_READY", "订单存在非京东普通单品、缺少履约方 SKU 映射或尚未就绪的行");
        }
        if (rows.stream().anyMatch(row -> !"JD_WAREHOUSE".equals(row.providerType())
                || !"SDK".equals(outboundMode(row.providerId())))) {
            throw BusinessException.unprocessable(
                    "ORDER_ROUTING_PROVIDER_UNSUPPORTED", "该入口只支持京东云仓 SDK 履约");
        }
        if (rows.stream().anyMatch(row -> !jdQuantityIsPositiveInteger(row))) {
            throw BusinessException.unprocessable(
                    "ORDER_ROUTING_QUANTITY_INVALID", "京东出库数量必须为正整数");
        }

        List<Long> shipmentIds = new ArrayList<>();
        rows.stream()
                .collect(Collectors.groupingBy(ExportRow::providerId, LinkedHashMap::new, Collectors.toList()))
                .forEach((providerId, providerRows) -> shipmentIds.addAll(createJdShipments(providerId, providerRows)));
        jdbc.update(
                """
                UPDATE app.order_lines
                SET fulfillment_committed_at=COALESCE(fulfillment_committed_at, CURRENT_TIMESTAMP),
                    updated_at=CURRENT_TIMESTAMP
                WHERE order_id=?
                """,
                orderId);
        int updated = jdbc.update(
                "UPDATE app.orders SET lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP "
                        + "WHERE id=? AND lock_version=?",
                orderId,
                expectedOrderVersion);
        if (updated != 1) {
            throw BusinessException.conflict("VERSION_CONFLICT", "订单已更新，请刷新后重试");
        }
        return new ReadyOrderRoute(List.copyOf(shipmentIds), expectedOrderVersion + 1);
    }

    private ReadyOrder lockReadyOrder(long orderId) {
        ReadyOrder value = jdbc.query(
                """
                SELECT o.lock_version, o.source_channel, o.order_status, o.source_import_batch_id,
                       (SELECT count(*) FROM app.order_lines ol WHERE ol.order_id=o.id) line_count
                FROM app.orders o
                WHERE o.id=? AND o.data_scope='BUSINESS'
                FOR UPDATE OF o
                """,
                resultSet -> resultSet.next()
                        ? new ReadyOrder(
                                resultSet.getLong("lock_version"),
                                resultSet.getString("source_channel"),
                                resultSet.getString("order_status"),
                                resultSet.getObject("source_import_batch_id", Long.class),
                                resultSet.getLong("line_count"))
                        : null,
                orderId);
        if (value == null) {
            throw BusinessException.notFound("BUSINESS 订单不存在");
        }
        return value;
    }

    private List<ExportRow> readyWecomJdRows(long orderId) {
        return jdbc.query(
                """
                SELECT 0::bigint raw_row_id, o.id order_id, o.order_no, o.source_channel, o.source_ref,
                       o.settlement_time ordered_at, o.remark,
                       o.receiver_name, o.receiver_phone, o.receiver_address,
                       ol.id order_line_id, ol.line_no, ol.product_name_snapshot,
                       ol.specification_snapshot, ol.unit_snapshot,
                       f.id fulfillment_id, f.requested_quantity fulfillment_quantity,
                       f.requested_quantity requested_quantity,
                       fp.id provider_id, fp.provider_code, fp.provider_name, fp.provider_type,
                       fp.tracking_sla_minutes, ps.provider_sku_code
                FROM app.orders o
                JOIN app.order_lines ol ON ol.order_id=o.id
                    AND ol.line_type='SINGLE' AND ol.processing_stage='READY_TO_EXPORT'
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                    AND f.shipping_progress='NOT_SHIPPED' AND f.outcome='IN_PROGRESS'
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id AND fp.active
                JOIN app.provider_skus ps ON ps.fulfillment_provider_id=fp.id
                    AND ps.sku_id=ol.sku_id AND ps.active
                WHERE o.id=? AND o.data_scope='BUSINESS' AND o.source_channel='WECOM'
                ORDER BY fp.id, ol.line_no
                FOR UPDATE OF ol, f
                """,
                (resultSet, rowNum) -> new ExportRow(
                        resultSet.getLong("raw_row_id"),
                        resultSet.getLong("order_id"),
                        resultSet.getString("order_no"),
                        resultSet.getString("source_channel"),
                        resultSet.getString("source_ref"),
                        nullableInstant(resultSet, "ordered_at"),
                        resultSet.getString("remark"),
                        resultSet.getString("receiver_name"),
                        resultSet.getString("receiver_phone"),
                        resultSet.getString("receiver_address"),
                        resultSet.getLong("order_line_id"),
                        resultSet.getInt("line_no"),
                        resultSet.getString("product_name_snapshot"),
                        resultSet.getString("specification_snapshot"),
                        resultSet.getString("unit_snapshot"),
                        resultSet.getLong("fulfillment_id"),
                        null,
                        resultSet.getBigDecimal("fulfillment_quantity"),
                        resultSet.getBigDecimal("requested_quantity"),
                        resultSet.getLong("provider_id"),
                        resultSet.getString("provider_code"),
                        resultSet.getString("provider_name"),
                        resultSet.getString("provider_type"),
                        resultSet.getInt("tracking_sla_minutes"),
                        resultSet.getString("provider_sku_code")),
                orderId);
    }

    /** 京东 SDK 路由：只创建发货批次与明细，不产文件、不推进订单阶段（由建单结果决定）。 */
    private List<Long> createJdShipments(long providerId, List<ExportRow> sourceRows) {
        Map<String, ShipmentPlan> shipments = new LinkedHashMap<>();
        for (ExportRow source : sourceRows) {
            String group = source.orderId() + ":" + source.providerId();
            ShipmentPlan shipment = shipments.computeIfAbsent(group, ignored -> createShipment(source));
            jdbc.update(
                    """
                    INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity)
                    VALUES (?, ?, ?)
                    """,
                    shipment.id(), source.fulfillmentId(), source.fulfillmentQuantity());
        }
        return shipments.values().stream().map(ShipmentPlan::id).toList();
    }

    private String outboundMode(long providerId) {
        return jdbc.queryForObject(
                "SELECT config->>'outboundMode' FROM app.fulfillment_providers WHERE id=?",
                String.class,
                providerId);
    }

    @Transactional
    void validateSourceBatchExportability(long sourceBatchId) {
        holdThirdPartyBundleLinesWithoutProviderSku(sourceBatchId);
        candidateRows(sourceBatchId).stream()
                .filter(row -> "JD_WAREHOUSE".equals(row.providerType()))
                .filter(row -> !jdQuantityIsPositiveInteger(row))
                .forEach(row -> markJdQuantityReview(sourceBatchId, row));
    }

    /** 第三方礼包缺 provider_sku 时只把该 provider 分片留在 NEED_REVIEW，不阻断已就绪的京东分片。 */
    private void holdThirdPartyBundleLinesWithoutProviderSku(long sourceBatchId) {
        List<ProviderSkuHold> holds = jdbc.query(
                """
                WITH raw_line_links AS (
                    SELECT rir.id raw_row_id, rir.order_line_id
                    FROM app.raw_import_rows rir
                    WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                    WHERE rir.import_batch_id=?
                )
                SELECT DISTINCT o.id order_id, ol.id order_line_id, f.id fulfillment_id
                FROM raw_line_links rll
                JOIN app.raw_import_rows rir ON rir.id=rll.raw_row_id AND rir.status='ACCEPTED'
                JOIN app.order_lines ol ON ol.id=rll.order_line_id
                    AND ol.line_type='CUSTOM_BUNDLE' AND ol.bundle_id IS NOT NULL
                    AND ol.processing_stage='READY_TO_EXPORT'
                JOIN app.orders o ON o.id=ol.order_id
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                    AND fp.provider_type='THIRD_PARTY'
                WHERE EXISTS (
                    SELECT 1
                    FROM app.order_line_components olc
                    WHERE olc.order_line_id=ol.id
                      AND NOT EXISTS (
                          SELECT 1 FROM app.provider_skus ps
                          WHERE ps.fulfillment_provider_id=f.fulfillment_provider_id
                            AND ps.sku_id=olc.sku_id AND ps.active
                      )
                )
                ORDER BY ol.id
                """,
                (resultSet, rowNum) -> new ProviderSkuHold(
                        resultSet.getLong("order_id"),
                        resultSet.getLong("order_line_id"),
                        resultSet.getLong("fulfillment_id")),
                sourceBatchId,
                sourceBatchId);
        for (ProviderSkuHold hold : holds) {
            jdbc.update(
                    """
                    UPDATE app.order_lines
                    SET processing_stage='NEED_REVIEW', exception_code='PROVIDER_SKU_MAPPING_REQUIRED',
                        exception_reason='第三方礼包组件缺少履约方 SKU 映射', updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """,
                    hold.orderLineId());
            jdbc.update(
                    "UPDATE app.orders SET order_status='NEED_REVIEW', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    hold.orderId());
            jdbc.update(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, status, responsible_team, reason_code,
                         order_id, order_line_id, fulfillment_id, import_batch_id, detail)
                    VALUES (?, 'FULFILLMENT_EXPORT', 'OPEN', 'SKU_OPS', 'PROVIDER_SKU_MAPPING_REQUIRED',
                            ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (case_no) DO NOTHING
                    """,
                    "RC-PROVIDER-SKU-" + hold.orderLineId(),
                    hold.orderId(),
                    hold.orderLineId(),
                    hold.fulfillmentId(),
                    sourceBatchId,
                    json(providerSkuReviewDetail(sourceBatchId, hold)));
        }
    }

    /** 为履约方 SKU 缺失生成可行动的商品证据，并保留来源文件位置。 */
    private Map<String, Object> providerSkuReviewDetail(long sourceBatchId, ProviderSkuHold hold) {
        Map<String, Object> line = jdbc.queryForMap(
                """
                SELECT line_no, product_name_snapshot, specification_snapshot, unit_snapshot, requested_quantity
                FROM app.order_lines WHERE id=?
                """,
                hold.orderLineId());
        List<Map<String, Object>> evidenceItems = jdbc.query(
                """
                SELECT s.sku_code, p.product_name, s.specification, s.unit,
                       ol.requested_quantity * olc.quantity_per_bundle AS quantity
                FROM app.order_line_components olc
                JOIN app.order_lines ol ON ol.id=olc.order_line_id
                JOIN app.skus s ON s.id=olc.sku_id
                JOIN app.products p ON p.id=s.product_id
                JOIN app.fulfillments f ON f.id=? AND f.order_line_id=ol.id
                WHERE olc.order_line_id=?
                  AND NOT EXISTS (
                      SELECT 1 FROM app.provider_skus ps
                      WHERE ps.fulfillment_provider_id=f.fulfillment_provider_id
                        AND ps.sku_id=olc.sku_id AND ps.active)
                ORDER BY olc.id
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source_sku_ref", null);
                    item.put("product_name", resultSet.getString("product_name"));
                    item.put("specification", resultSet.getString("specification"));
                    item.put("unit", resultSet.getString("unit"));
                    item.put(
                            "quantity",
                            resultSet.getBigDecimal("quantity").setScale(3, RoundingMode.HALF_UP).toPlainString());
                    return item;
                },
                hold.fulfillmentId(),
                hold.orderLineId());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", "第三方礼包组件缺少履约方 SKU 映射");
        detail.put("line_no", line.get("line_no"));
        detail.put("source_product_name", line.get("product_name_snapshot"));
        detail.put("source_specification", line.get("specification_snapshot"));
        detail.put("source_unit", line.get("unit_snapshot"));
        detail.put("source_quantity", ((BigDecimal) line.get("requested_quantity")).toPlainString());
        List<Map<String, Object>> sourceRows = jdbc.queryForList(
                """
                SELECT ib.source_channel, rir.sheet_name, rir.row_index, rir.raw_cells::text AS raw_cells
                FROM app.raw_import_rows rir
                JOIN app.import_batches ib ON ib.id=rir.import_batch_id
                LEFT JOIN app.raw_import_row_order_lines rirol ON rirol.raw_import_row_id=rir.id
                WHERE rir.import_batch_id=?
                  AND (rir.order_line_id=? OR rirol.order_line_id=?)
                ORDER BY rir.sheet_index, rir.row_index
                LIMIT 1
                """,
                sourceBatchId,
                hold.orderLineId(),
                hold.orderLineId());
        if (!sourceRows.isEmpty()) {
            Map<String, Object> sourceRow = sourceRows.getFirst();
            String sourceChannel = sourceRow.get("source_channel").toString();
            Map<String, String> projection = sourceProjection(sourceChannel, sourceRow.get("raw_cells").toString());
            String sourceSkuRef = projection.get("source_sku_ref");
            detail.put("source_channel", sourceChannel);
            detail.put("source_sheet_name", sourceRow.get("sheet_name"));
            detail.put("source_row_index", sourceRow.get("row_index"));
            detail.put("missing_source_sku_refs", sourceSkuRef == null ? List.of() : List.of(sourceSkuRef));
            evidenceItems.forEach(item -> item.put("source_sku_ref", sourceSkuRef));
        }
        detail.putIfAbsent("missing_source_sku_refs", List.of());
        detail.put("evidence_items", evidenceItems);
        return detail;
    }

    private Map<String, String> sourceProjection(String sourceChannel, String rawCellsJson) {
        try {
            Map<String, String> rawCells = objectMapper.readValue(rawCellsJson, new TypeReference<>() {});
            return new SourceFileParser().projection(SourceChannel.valueOf(sourceChannel), rawCells);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("来源行快照无法解析", exception);
        }
    }

    @Override
    public List<Long> generateReadyExports(long sourceBatchId, String operator) {
        return generateForSourceBatch(sourceBatchId, operator);
    }

    private long generateThirdParty(long providerId, List<ExportRow> sourceRows, String operator) {
        String batchNo = "EXP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Map<String, ShipmentPlan> shipments = new LinkedHashMap<>();
        Set<Long> allocatedFulfillments = new HashSet<>();
        for (ExportRow source : sourceRows) {
            String group = source.orderId() + ":" + source.providerId();
            ShipmentPlan shipment = shipments.computeIfAbsent(group, ignored -> createShipment(source));
            if (allocatedFulfillments.add(source.fulfillmentId())) {
                jdbc.update(
                        """
                        INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity)
                        VALUES (?, ?, ?)
                        """,
                        shipment.id(), source.fulfillmentId(), source.fulfillmentQuantity());
            }
        }

        List<PlannedExportRow> planned = new ArrayList<>();
        int lineNo = 1;
        for (ExportRow source : sourceRows) {
            ShipmentPlan shipment = shipments.get(source.orderId() + ":" + source.providerId());
            planned.add(new PlannedExportRow(lineNo++, source, shipment));
        }
        byte[] workbook = thirdPartyWorkbook(batchNo, planned);
        ContentAddressedFileStore.StoredFile stored = fileStore.put("fulfillment-exports", workbook, ".xlsx");
        ExportRow first = sourceRows.getFirst();
        long exportId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_exports
                    (export_batch_no, fulfillment_provider_id, export_kind, template_version,
                     file_ref, file_sha256, tracking_due_at, generated_by)
                VALUES (?, ?, 'THIRD_PARTY', 'v1-24-columns', ?, ?,
                        NULL, ?)
                RETURNING id
                """,
                Long.class,
                batchNo,
                providerId,
                stored.fileRef(),
                stored.sha256(),
                operator);
        // #84：同一业务事务内登记企微出站状态 + 入队 initial delivery（JD 路径不调用 = 不入队）
        wecomExportService.scheduleInitial(exportId, providerId, first.trackingSlaMinutes());
        for (PlannedExportRow row : planned) {
            Map<String, Object> cells = outputCells(batchNo, row);
            jdbc.update(
                    """
                    INSERT INTO app.fulfillment_export_items
                        (fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
                         order_line_id, order_line_component_id, raw_import_row_id,
                         outbound_order_no, provider_sku_code,
                         instructed_quantity, unit_snapshot, output_cells)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    exportId,
                    row.lineNo(),
                    row.shipment().id(),
                    row.source().fulfillmentId(),
                    row.source().orderLineId(),
                    row.source().orderLineComponentId(),
                    row.source().rawRowId(),
                    row.shipment().outboundOrderNo(),
                    row.source().providerSkuCode(),
                    row.source().requestedQuantity(),
                    row.source().unit(),
                    json(cells));
            jdbc.update(
                    "UPDATE app.order_lines SET processing_stage='WAITING_PROVIDER' WHERE id=?",
                    row.source().orderLineId());
            jdbc.update(
                    "UPDATE app.orders SET order_status='FULFILLING' WHERE id=?",
                    row.source().orderId());
        }
        return exportId;
    }

    /** 已部分发货的单个 Fulfillment 创建独立第三方续发文件。 */
    @Transactional
    @Override
    public ContinuationExportGenerator.ContinuationExport generateContinuation(
            long fulfillmentId, BigDecimal instructedQuantity, String remark, String operator) {
        ExportRow source = continuationRow(fulfillmentId, instructedQuantity, remark);
        ShipmentPlan shipment = createShipment(source);
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) VALUES (?, ?, ?)",
                shipment.id(),
                fulfillmentId,
                instructedQuantity);
        String batchNo = "EXP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        PlannedExportRow planned = new PlannedExportRow(1, source, shipment);
        ContentAddressedFileStore.StoredFile stored = fileStore.put(
                "fulfillment-exports", thirdPartyWorkbook(batchNo, List.of(planned)), ".xlsx");
        long exportId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_exports
                    (export_batch_no, fulfillment_provider_id, export_kind, template_version,
                     file_ref, file_sha256, tracking_due_at, generated_by)
                VALUES (?, ?, 'THIRD_PARTY', 'v1-24-columns', ?, ?,
                        NULL, ?)
                RETURNING id
                """,
                Long.class,
                batchNo,
                source.providerId(),
                stored.fileRef(),
                stored.sha256(),
                operator);
        // #84：第三方续发导出同样在同一事务登记出站状态并入队 initial delivery
        wecomExportService.scheduleInitial(exportId, source.providerId(), source.trackingSlaMinutes());
        Map<String, Object> cells = outputCells(batchNo, planned);
        jdbc.update(
                """
                INSERT INTO app.fulfillment_export_items
                    (fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
                     order_line_id, raw_import_row_id, outbound_order_no, provider_sku_code,
                     instructed_quantity, unit_snapshot, output_cells)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """,
                exportId,
                shipment.id(),
                fulfillmentId,
                source.orderLineId(),
                source.rawRowId(),
                shipment.outboundOrderNo(),
                source.providerSkuCode(),
                instructedQuantity,
                source.unit(),
                json(cells));
        jdbc.update("UPDATE app.order_lines SET processing_stage='WAITING_PROVIDER' WHERE id=?", source.orderLineId());
        return new ContinuationExportGenerator.ContinuationExport(
                exportId, shipment.id(), shipmentSequence(shipment.id()), shipment.outboundOrderNo(), batchNo);
    }

    private long generateJd(long providerId, List<ExportRow> sourceRows, String operator) {
        String batchNo = "EXP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Map<String, ShipmentPlan> shipments = new LinkedHashMap<>();
        for (ExportRow source : sourceRows) {
            String group = source.orderId() + ":" + source.providerId();
            ShipmentPlan shipment = shipments.computeIfAbsent(group, ignored -> createShipment(source));
            jdbc.update(
                    """
                    INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity)
                    VALUES (?, ?, ?)
                    """,
                    shipment.id(), source.fulfillmentId(), source.fulfillmentQuantity());
        }

        List<PlannedExportRow> planned = new ArrayList<>();
        int lineNo = 1;
        for (ExportRow source : sourceRows) {
            ShipmentPlan shipment = shipments.get(source.orderId() + ":" + source.providerId());
            planned.add(new PlannedExportRow(lineNo++, source, shipment));
        }
        List<Map<String, Object>> plannedCells = planned.stream().map(this::jdOutputCells).toList();
        JdWorkbook generated = jdWorkbook(planned, plannedCells);
        ContentAddressedFileStore.StoredFile stored = fileStore.put("fulfillment-exports", generated.bytes(), ".xlsx");
        ExportRow first = sourceRows.getFirst();
        long exportId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_exports
                    (export_batch_no, fulfillment_provider_id, export_kind, template_version,
                     file_ref, file_sha256, tracking_due_at, generated_by)
                VALUES (?, ?, 'JD_WAREHOUSE', ?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 minute'), ?)
                RETURNING id
                """,
                Long.class,
                batchNo,
                providerId,
                generated.templateVersion(),
                stored.fileRef(),
                stored.sha256(),
                first.trackingSlaMinutes(),
                operator);
        for (int index = 0; index < planned.size(); index++) {
            PlannedExportRow row = planned.get(index);
            Map<String, Object> cells = plannedCells.get(index);
            jdbc.update(
                    """
                    INSERT INTO app.fulfillment_export_items
                        (fulfillment_export_id, export_line_no, shipment_id, fulfillment_id,
                         order_line_id, raw_import_row_id, outbound_order_no, provider_sku_code,
                         instructed_quantity, unit_snapshot, item_amount, output_cells)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?::jsonb)
                    """,
                    exportId,
                    row.lineNo(),
                    row.shipment().id(),
                    row.source().fulfillmentId(),
                    row.source().orderLineId(),
                    row.source().rawRowId(),
                    row.shipment().outboundOrderNo(),
                    row.source().providerSkuCode(),
                    row.source().requestedQuantity(),
                    row.source().unit(),
                    json(cells));
            jdbc.update("UPDATE app.order_lines SET processing_stage='WAITING_PROVIDER' WHERE id=?",
                    row.source().orderLineId());
            jdbc.update("UPDATE app.orders SET order_status='FULFILLING' WHERE id=?", row.source().orderId());
        }
        return exportId;
    }

    private JdWorkbook jdWorkbook(
            List<PlannedExportRow> rows, List<Map<String, Object>> plannedCells) {
        try {
            if (!BUILT_IN_JD_TEMPLATE.exists()) {
                throw BusinessException.unprocessable(
                        "JD_EXPORT_TEMPLATE_REQUIRED", "应用缺少内置的脱敏京东官方77列导单模板");
            }
            byte[] templateBytes = BUILT_IN_JD_TEMPLATE.getContentAsByteArray();
            try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(templateBytes));
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                validateJdGolden(workbook);
                var sheet = workbook.getSheetAt(0);
                Row styleRow = sheet.getRow(1);
                if (styleRow == null) {
                    throw BusinessException.unprocessable("JD_EXPORT_TEMPLATE_INVALID", "京东官方模板缺少数据行样式");
                }
                for (int index = sheet.getLastRowNum(); index > 1; index--) {
                    Row existing = sheet.getRow(index);
                    if (existing != null) {
                        sheet.removeRow(existing);
                    }
                }
                clearValues(styleRow);
                for (int index = 0; index < rows.size(); index++) {
                    PlannedExportRow planned = rows.get(index);
                    Row target = planned.lineNo() == 1 ? styleRow : copyStyledRow(sheet, styleRow, planned.lineNo());
                    writeJdRow(target, plannedCells.get(index));
                }
                workbook.setForceFormulaRecalculation(true);
                workbook.write(output);
                return new JdWorkbook(output.toByteArray(), "jd-golden-" + sha256(templateBytes).substring(0, 12));
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw BusinessException.unprocessable("JD_EXPORT_TEMPLATE_INVALID", "京东官方模板无法解析");
        }
    }

    private void validateJdGolden(XSSFWorkbook workbook) {
        if (workbook.getNumberOfSheets() < 2
                || !"导入数据".equals(workbook.getSheetName(0))
                || !"导入说明".equals(workbook.getSheetName(1))) {
            throw BusinessException.unprocessable("JD_EXPORT_TEMPLATE_INVALID", "京东官方模板 Sheet 结构不匹配");
        }
        Row header = workbook.getSheetAt(0).getRow(0);
        DataFormatter formatter = new DataFormatter();
        if (header == null || header.getLastCellNum() != 78
                || !formatter.formatCellValue(header.getCell(0)).isBlank()) {
            throw BusinessException.unprocessable("JD_EXPORT_TEMPLATE_INVALID", "京东官方模板必须保持 A 列空占位和 B:BZ 77 业务列");
        }
        List<String> actual = new ArrayList<>();
        for (int index = 1; index < header.getLastCellNum(); index++) {
            actual.add(formatter.formatCellValue(header.getCell(index)));
        }
        if (!JD_HEADERS.equals(actual)) {
            throw BusinessException.unprocessable("JD_EXPORT_TEMPLATE_INVALID", "京东官方77列表头或顺序不匹配");
        }
    }

    private void clearValues(Row row) {
        for (int index = 0; index < 78; index++) {
            var cell = row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            cell.setBlank();
        }
    }

    private Row copyStyledRow(org.apache.poi.ss.usermodel.Sheet sheet, Row styleRow, int rowNumber) {
        Row row = sheet.createRow(rowNumber);
        row.setHeight(styleRow.getHeight());
        for (int index = 0; index < 78; index++) {
            var styleCell = styleRow.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            row.createCell(index, CellType.BLANK).setCellStyle(styleCell.getCellStyle());
        }
        return row;
    }

    private void writeJdRow(Row row, Map<String, Object> values) {
        Map<String, Integer> columns = jdColumns();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            int index = columns.get(entry.getKey());
            if (entry.getValue() instanceof Number number) {
                row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(number.doubleValue());
            } else {
                row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(entry.getValue().toString());
            }
        }
    }

    /**
     * 京东租户标识：一律取自 {@code fulfillment_providers.config}，缺失即阻断导出。
     *
     * <p>本方法过去把 sourceNo/ownerNo/shopNo/customerCode/warehouseNo/carrierNo/pin/
     * salesPlatformSource 八个值硬编码在源码里，与 SDK 建单路径
     * （{@code ShipmentJdOutboundPreparer}，明确要求「identifiers come from the selected
     * FulfillmentProvider configuration, never hard-coded」并在缺失时 fail-closed）互相矛盾：
     * 同一个履约方，导出走硬编码常量、建单走配置，改一处不影响另一处，是错配的温床。
     * 更糟的是 {@code pin} 是京东授权凭据，硬编码等于把凭据提交进 Git，且无法轮换。
     *
     * <p>现在两条路共用一份配置。缺配置时导出失败而不是发出可能错误的单据——
     * 与建单侧同样的 fail-closed 语义。
     */
    private String requiredJdConfig(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) {
            throw BusinessException.unprocessable(
                    "JD_EXPORT_PROVIDER_CONFIG_MISSING",
                    "履约方配置缺少京东标识 " + key + "，请在「系统管理 → 履约方」补齐后再导出");
        }
        return value.toString();
    }

    /** 履约方京东配置整体读取；行级导出复用同一份，避免逐字段查库。 */
    private Map<String, Object> jdProviderConfig(long providerId) {
        String raw = jdbc.queryForObject(
                "SELECT config::text FROM app.fulfillment_providers WHERE id=?", String.class, providerId);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw BusinessException.unprocessable(
                    "JD_EXPORT_PROVIDER_CONFIG_INVALID",
                    "履约方配置不是合法 JSON 对象，无法导出；请检查履约方 " + providerId + " 的配置");
        }
    }

    private Map<String, Object> jdOutputCells(PlannedExportRow planned) {
        ExportRow source = planned.source();
        Map<String, Object> config = jdProviderConfig(source.providerId());
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("*isv出库单号", planned.shipment().outboundOrderNo());
        cells.put("*ISV来源编号", requiredJdConfig(config, "sourceNo"));
        cells.put("*事业部编号", requiredJdConfig(config, "ownerNo"));
        cells.put("*店铺编号", requiredJdConfig(config, "shopNo"));
        cells.put("青龙业主号", requiredJdCustomerCode(config, source.orderId()));
        cells.put("*仓库编号", requiredJdConfig(config, "warehouseNo"));
        cells.put("*承运商编号", requiredJdConfig(config, "carrierNo"));
        cells.put("*授权码pin", requiredJdConfig(config, "pin"));
        cells.put("销售平台订单号", source.sourceRef());
        // 京东模板此列是数值；配置以字符串保存（JD config 的形状校验只收非空字符串），
        // 能解析成整数就还原为数值，否则原样落字符串而不是猜一个默认值。
        String platformSource = requiredJdConfig(config, "salesPlatformSource");
        cells.put("*销售平台来源", parseIntOrText(platformSource));
        if (source.orderedAt() != null) {
            cells.put("销售平台下单时间", JD_TIME.format(source.orderedAt()));
        }
        cells.put("*订单标记位", "0".repeat(50));
        cells.put("*收货人姓名", source.receiverName());
        cells.put("*收货人手机", source.receiverPhone());
        cells.put("*收货人地址", source.receiverAddress());
        if (source.remark() != null && !source.remark().isBlank()) {
            cells.put("客户留言", source.remark());
        }
        cells.put("*京东商品编号", source.providerSkuCode());
        cells.put("*商品金额", 0);
        cells.put("*商品的出库数量", source.requestedQuantity().intValueExact());
        cells.put("仓配产品", "LL-HD-M");
        return cells;
    }

    /**
     * 青龙业主号（customerCode）：与建单路径 {@code ShipmentJdOutboundPreparer}
     * 同一套取值语义——履约方配置优先，客户档案 jd_customer_code 为历史回退源；
     * 两者都缺时 fail-closed 阻断导出，避免发出可能错误的单据。
     */
    private String requiredJdCustomerCode(Map<String, Object> config, long orderId) {
        Object configCode = config.get("customerCode");
        if (configCode != null && !configCode.toString().isBlank()) {
            return configCode.toString();
        }
        String archiveCode = jdbc.queryForObject(
                "SELECT c.profile->>'jd_customer_code' FROM app.orders o"
                        + " LEFT JOIN app.customers c ON c.id=o.customer_id WHERE o.id=?",
                String.class, orderId);
        if (archiveCode != null && !archiveCode.isBlank()) {
            return archiveCode;
        }
        throw BusinessException.unprocessable(
                "JD_EXPORT_PROVIDER_CONFIG_MISSING",
                "履约方配置缺少京东标识 customerCode（或客户档案 jd_customer_code 回退值），请在「系统管理 → 履约方」补齐后再导出");
    }

    /** 数值列的宽容解析：可解析即数值，否则原样字符串（不猜默认值）。 */
    private static Object parseIntOrText(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException ex) {
            return text;
        }
    }

    private Map<String, Integer> jdColumns() {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < JD_HEADERS.size(); index++) {
            columns.put(JD_HEADERS.get(index), index + 1);
        }
        return columns;
    }

    private boolean jdQuantityIsPositiveInteger(ExportRow row) {
        return row.requestedQuantity().signum() > 0 && row.requestedQuantity().stripTrailingZeros().scale() <= 0;
    }

    private void markJdQuantityReview(long sourceBatchId, ExportRow row) {
        Map<String, Object> lineFacts = jdbc.queryForMap(
                "SELECT source_quantity_snapshot, mapping_multiplier_snapshot FROM app.order_lines WHERE id=?",
                row.orderLineId());
        // 数量换算事实（Issue #72）：来源数量原文/单位/当前乘数/换算后结果/拒绝原因，
        // 全部为确定性快照与固定文案，不读取任何敏感字段。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reject_reason", "京东出库数量必须为正整数");
        detail.put("source_quantity", toPlainString(lineFacts.get("source_quantity_snapshot")));
        detail.put("source_unit", row.unit());
        detail.put("quantity_multiplier", toPlainString(lineFacts.get("mapping_multiplier_snapshot")));
        detail.put("converted_quantity", row.requestedQuantity().toPlainString());
        detail.put("requested_quantity", row.requestedQuantity().toPlainString());
        detail.put("provider_code", row.providerCode());
        jdbc.update(
                """
                UPDATE app.order_lines
                SET processing_stage='NEED_REVIEW', exception_code='QUANTITY_SCALE',
                    exception_reason='京东出库数量必须为正整数', updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                row.orderLineId());
        jdbc.update("UPDATE app.orders SET order_status='NEED_REVIEW', updated_at=CURRENT_TIMESTAMP WHERE id=?", row.orderId());
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, order_line_id, fulfillment_id, import_batch_id, raw_import_row_id, detail)
                VALUES (?, 'FULFILLMENT_EXPORT', 'OPEN', 'FULFILLMENT_OPS', 'QUANTITY_SCALE',
                        ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (case_no) DO NOTHING
                """,
                "RC-QUANTITY-" + row.orderLineId(),
                row.orderId(),
                row.orderLineId(),
                row.fulfillmentId(),
                sourceBatchId,
                row.rawRowId(),
                json(detail));
    }

    private static String toPlainString(Object value) {
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : null;
    }

    private ShipmentPlan createShipment(ExportRow row) {
        String shipmentNo = "SHIP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, COALESCE((SELECT MAX(shipment_sequence)+1 FROM app.shipments
                                           WHERE order_id=? AND fulfillment_provider_id=?), 1), ?, ?, ?)
                RETURNING id, outbound_order_no
                """,
                (resultSet, rowNum) -> new ShipmentPlan(resultSet.getLong("id"), resultSet.getString("outbound_order_no")),
                shipmentNo,
                row.orderId(),
                row.providerId(),
                row.orderId(),
                row.providerId(),
                row.receiverName(),
                row.receiverPhone(),
                row.receiverAddress());
    }

    private int shipmentSequence(long shipmentId) {
        return jdbc.queryForObject(
                "SELECT shipment_sequence FROM app.shipments WHERE id=?", Integer.class, shipmentId);
    }

    private ExportRow continuationRow(long fulfillmentId, BigDecimal instructedQuantity, String continuationRemark) {
        List<ExportRow> rows = jdbc.query(
                """
                SELECT rir.id raw_row_id, o.id order_id, o.order_no,
                       source.effective_source_channel source_channel, o.source_ref,
                       o.settlement_time ordered_at, o.remark,
                       o.receiver_name, o.receiver_phone, o.receiver_address,
                       ol.id order_line_id, ol.line_no, ol.product_name_snapshot, ol.specification_snapshot,
                       ol.unit_snapshot, f.id fulfillment_id,
                       fp.id provider_id, fp.provider_code, fp.provider_name, fp.provider_type,
                       fp.tracking_sla_minutes, ps.provider_sku_code
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id AND ol.line_type='SINGLE'
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id AND fp.active
                JOIN app.provider_skus ps ON ps.fulfillment_provider_id=fp.id AND ps.sku_id=ol.sku_id AND ps.active
                JOIN app.raw_import_rows rir ON rir.order_line_id=ol.id AND rir.status='ACCEPTED'
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=rir.import_batch_id
                WHERE f.id=? ORDER BY rir.id LIMIT 1
                """,
                (resultSet, rowNum) -> new ExportRow(
                        resultSet.getLong("raw_row_id"), resultSet.getLong("order_id"),
                        resultSet.getString("order_no"), resultSet.getString("source_channel"),
                        resultSet.getString("source_ref"), nullableInstant(resultSet, "ordered_at"),
                        appendRemark(resultSet.getString("remark"), continuationRemark),
                        resultSet.getString("receiver_name"), resultSet.getString("receiver_phone"),
                        resultSet.getString("receiver_address"), resultSet.getLong("order_line_id"),
                        resultSet.getInt("line_no"), resultSet.getString("product_name_snapshot"),
                        resultSet.getString("specification_snapshot"), resultSet.getString("unit_snapshot"),
                        resultSet.getLong("fulfillment_id"), null, instructedQuantity, instructedQuantity,
                        resultSet.getLong("provider_id"), resultSet.getString("provider_code"),
                        resultSet.getString("provider_name"), resultSet.getString("provider_type"),
                        resultSet.getInt("tracking_sla_minutes"), resultSet.getString("provider_sku_code")),
                fulfillmentId);
        if (rows.isEmpty()) {
            throw BusinessException.conflict(
                    "CONTINUATION_EXPORT_EVIDENCE_MISSING", "续发批次缺少已确认的来源行或第三方 SKU 映射");
        }
        return rows.getFirst();
    }

    private String appendRemark(String original, String continuation) {
        return original == null || original.isBlank() ? continuation : original + "；续发：" + continuation;
    }

    private List<ExportRow> candidateRows(long batchId) {
        return jdbc.query(
                """
                WITH raw_line_links AS (
                    SELECT rir.id raw_row_id, rir.order_line_id
                    FROM app.raw_import_rows rir
                    WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                    WHERE rir.import_batch_id=?
                )
                SELECT rir.id raw_row_id, o.id order_id, o.order_no,
                       source.effective_source_channel source_channel, o.source_ref,
                       o.settlement_time ordered_at, o.remark,
                       o.receiver_name, o.receiver_phone, o.receiver_address,
                       ol.id order_line_id, ol.line_no,
                       CASE WHEN olc.id IS NULL THEN ol.product_name_snapshot ELSE olc.product_name_snapshot END
                           product_name_snapshot,
                       CASE WHEN olc.id IS NULL THEN ol.specification_snapshot ELSE olc.specification_snapshot END
                           specification_snapshot,
                       CASE WHEN olc.id IS NULL THEN ol.unit_snapshot ELSE olc.unit_snapshot END unit_snapshot,
                       f.id fulfillment_id, olc.id order_line_component_id,
                       f.requested_quantity fulfillment_quantity,
                       f.requested_quantity * COALESCE(olc.quantity_per_bundle, 1) requested_quantity,
                       fp.id provider_id, fp.provider_code, fp.provider_name, fp.provider_type, fp.tracking_sla_minutes,
                       ps.provider_sku_code
                FROM app.raw_import_rows rir
                JOIN raw_line_links rll ON rll.raw_row_id=rir.id
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=rir.import_batch_id
                JOIN app.orders o ON o.id=rir.order_id AND o.data_scope='BUSINESS'
                JOIN app.order_lines ol ON ol.id=rll.order_line_id AND ol.processing_stage='READY_TO_EXPORT'
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id AND fp.active
                LEFT JOIN app.order_line_components olc
                  ON olc.order_line_id=ol.id
                 AND ol.line_type='CUSTOM_BUNDLE'
                 AND fp.provider_type<>'JD_WAREHOUSE'
                LEFT JOIN app.provider_skus ps
                  ON ps.fulfillment_provider_id=fp.id
                 AND ps.sku_id=COALESCE(olc.sku_id, ol.sku_id)
                 AND ps.active
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                  AND (
                    (ol.line_type='SINGLE' AND ps.id IS NOT NULL)
                    OR (
                      ol.line_type='CUSTOM_BUNDLE'
                      AND fp.provider_type='JD_WAREHOUSE'
                      AND fp.config->>'outboundMode'='SDK'
                    )
                    OR (
                      ol.line_type='CUSTOM_BUNDLE'
                      AND ol.bundle_id IS NOT NULL
                      AND fp.provider_type='THIRD_PARTY'
                      AND olc.id IS NOT NULL
                      AND ps.id IS NOT NULL
                    )
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM app.review_cases rc
                    WHERE rc.order_id=o.id AND rc.status='OPEN'
                      AND (rc.order_line_id IS NULL OR rc.order_line_id=ol.id)
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM app.fulfillment_export_items existing_item
                    WHERE existing_item.raw_import_row_id=rir.id
                      AND existing_item.order_line_id=ol.id
                  )
                  AND NOT EXISTS (
                    SELECT 1 FROM app.shipment_items existing_item
                    JOIN app.fulfillments existing_fulfillment ON existing_fulfillment.id=existing_item.fulfillment_id
                    WHERE existing_fulfillment.order_line_id=ol.id
                  )
                ORDER BY fp.id, o.id, ol.line_no, olc.component_no NULLS FIRST
                """,
                (resultSet, rowNum) -> new ExportRow(
                        resultSet.getLong("raw_row_id"), resultSet.getLong("order_id"), resultSet.getString("order_no"),
                        resultSet.getString("source_channel"), resultSet.getString("source_ref"),
                        nullableInstant(resultSet, "ordered_at"), resultSet.getString("remark"),
                        resultSet.getString("receiver_name"), resultSet.getString("receiver_phone"),
                        resultSet.getString("receiver_address"), resultSet.getLong("order_line_id"),
                        resultSet.getInt("line_no"), resultSet.getString("product_name_snapshot"),
                        resultSet.getString("specification_snapshot"), resultSet.getString("unit_snapshot"),
                        resultSet.getLong("fulfillment_id"),
                        (Long) resultSet.getObject("order_line_component_id"),
                        resultSet.getBigDecimal("fulfillment_quantity"),
                        resultSet.getBigDecimal("requested_quantity"),
                        resultSet.getLong("provider_id"), resultSet.getString("provider_code"),
                        resultSet.getString("provider_name"), resultSet.getString("provider_type"),
                        resultSet.getInt("tracking_sla_minutes"), resultSet.getString("provider_sku_code")),
                batchId, batchId, batchId);
    }

    private byte[] thirdPartyWorkbook(String batchNo, List<PlannedExportRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("发货清单");
            var header = sheet.createRow(0);
            for (int index = 0; index < THIRD_PARTY_HEADERS.size(); index++) {
                header.createCell(index).setCellValue(THIRD_PARTY_HEADERS.get(index));
            }
            for (PlannedExportRow row : rows) {
                var xlsxRow = sheet.createRow(row.lineNo());
                Map<String, Object> cells = outputCells(batchNo, row);
                for (int index = 0; index < THIRD_PARTY_HEADERS.size(); index++) {
                    Object value = cells.get(THIRD_PARTY_HEADERS.get(index));
                    xlsxRow.createCell(index).setCellValue(value == null ? "" : value.toString());
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成第三方履约文件", exception);
        }
    }

    private Map<String, Object> outputCells(String batchNo, PlannedExportRow row) {
        ExportRow source = row.source();
        Map<String, Object> cells = new LinkedHashMap<>();
        THIRD_PARTY_HEADERS.forEach(header -> cells.put(header, ""));
        cells.put("导出批次号", batchNo);
        cells.put("出库单号", row.shipment().outboundOrderNo());
        cells.put("导出明细号", row.lineNo());
        cells.put("履约方编码", source.providerCode());
        cells.put("履约方名称", source.providerName());
        cells.put("内部订单号", source.orderNo());
        cells.put("来源渠道", SourceChannelDisplayNames.displayName(source.sourceChannel()));
        cells.put("来源订单号", source.sourceRef());
        cells.put("订单行号", source.lineNo());
        if (source.orderLineComponentId() != null) {
            cells.put("礼包分组标识", row.shipment().outboundOrderNo() + "-" + source.lineNo());
        }
        cells.put("收件人", source.receiverName());
        cells.put("电话", source.receiverPhone());
        cells.put("地址", source.receiverAddress());
        cells.put("履约方SKU编码", source.providerSkuCode());
        cells.put("品名", source.productName());
        cells.put("规格", source.specification());
        cells.put("单位", source.unit());
        cells.put("请求发货数量", source.requestedQuantity().toPlainString());
        return cells;
    }

    PageResponse<Map<String, Object>> list(int page, int size, Long providerId, String usageStatus) {
        if (page < 0 || size < 1 || size > 200) {
            throw BusinessException.badRequest("INVALID_PAGINATION", "page/size 不合法");
        }
        List<Object> args = new ArrayList<>();
        String where = " WHERE 1=1";
        if (providerId != null) {
            where += " AND fe.fulfillment_provider_id=?";
            args.add(providerId);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM app.fulfillment_exports fe" + where, Long.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        paged.add(size);
        paged.add((long) page * size);
        List<Map<String, Object>> items = jdbc.query(
                "SELECT fe.id FROM app.fulfillment_exports fe" + where + " ORDER BY fe.generated_at DESC LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> summary(resultSet.getLong(1), false),
                paged.toArray());
        if (usageStatus != null && !usageStatus.isBlank()) {
            items = items.stream().filter(item -> usageStatus.equals(item.get("usage_status"))).toList();
            total = items.size();
        }
        return new PageResponse<>(items, page, size, total, total == 0 ? 0 : (int) ((total + size - 1) / size));
    }

    Map<String, Object> detail(long exportId) {
        return summary(exportId, true);
    }

    private Map<String, Object> summary(long exportId, boolean includeLines) {
        List<Map<String, Object>> values = jdbc.query(
                """
                SELECT fe.id, fe.export_batch_no, fe.fulfillment_provider_id, fe.export_kind, fe.template_version,
                       fe.file_sha256, fe.tracking_due_at, fe.generated_at,
                       (SELECT id FROM app.import_batches ib WHERE ib.source_fulfillment_export_id=fe.id
                        ORDER BY id DESC LIMIT 1) tracking_import_batch_id,
                       -- 恰好来自一个导入批次时才给出，跨批次一律返回 NULL：
                       -- 取其一会让前端按错误批次下载回填表，宁可显示「尚未生成」也不给错答案。
                       (SELECT CASE WHEN COUNT(DISTINCT rir.import_batch_id) = 1
                                    THEN MIN(rir.import_batch_id) END
                        FROM app.fulfillment_export_items fei
                        JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                        WHERE fei.fulfillment_export_id=fe.id) import_batch_id
                FROM app.fulfillment_exports fe WHERE fe.id=?
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", resultSet.getString("id"));
                    result.put("export_batch_no", resultSet.getString("export_batch_no"));
                    result.put("provider_id", resultSet.getString("fulfillment_provider_id"));
                    result.put("export_kind", resultSet.getString("export_kind"));
                    result.put("template_version", resultSet.getString("template_version"));
                    result.put("file_sha256", resultSet.getString("file_sha256"));
                    result.put("generated_at", resultSet.getTimestamp("generated_at").toInstant());
                    result.put("legacy_tracking_due_at", resultSet.getTimestamp("tracking_due_at") == null
                            ? null : resultSet.getTimestamp("tracking_due_at").toInstant());
                    result.put("tracking_import_batch_id", nullableId(resultSet.getObject("tracking_import_batch_id")));
                    result.put("import_batch_id", nullableId(resultSet.getObject("import_batch_id")));
                    return result;
                },
                exportId);
        if (values.isEmpty()) {
            throw BusinessException.notFound("履约导出不存在: " + exportId);
        }
        Map<String, Object> result = values.getFirst();
        // #84：新第三方导出以 sent_at 派生的 due 为权威（未发送时为 null，不展示假到期时间）；
        // 历史（LEGACY）与 JD 导出保持旧 generated_at 派生语义。
        Map<String, Object> wecom = wecomExportService.view(exportId);
        boolean authoritativeDue = wecom != null && !"LEGACY".equals(wecom.get("status"));
        result.put("tracking_due_at", authoritativeDue ? wecom.get("tracking_due_at") : result.get("legacy_tracking_due_at"));
        result.remove("legacy_tracking_due_at");
        result.put("wecom", wecom);
        Map<String, Object> download = downloadAudit(exportId);
        result.put("download_audit", download);
        result.put("usage_status", usageStatus(result, download));
        if (includeLines) {
            result.put("lines", lines(exportId));
        }
        return result;
    }

    FileDownload download(long exportId, CommandContext context) {
        String fileRef = jdbc.queryForObject(
                "SELECT file_ref FROM app.fulfillment_exports WHERE id=?", String.class, exportId);
        if (fileRef == null) {
            throw BusinessException.notFound("履约导出不存在: " + exportId);
        }
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment-export").operation("file.download")
                .requestPayload(Map.of("export_id", exportId)).httpStatus(200).businessCode("FILE_DOWNLOADED"));
        return new FileDownload("fulfillment-export-" + exportId + ".xlsx", fileStore.read(fileRef),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    private List<Map<String, Object>> lines(long exportId) {
        return jdbc.query(
                """
                SELECT export_line_no, shipment_id, fulfillment_id, order_line_id, order_line_component_id,
                       raw_import_row_id, outbound_order_no, provider_sku_code, instructed_quantity,
                       unit_snapshot, item_amount
                FROM app.fulfillment_export_items WHERE fulfillment_export_id=? ORDER BY export_line_no
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("export_line_no", resultSet.getInt("export_line_no"));
                    row.put("shipment_id", resultSet.getString("shipment_id"));
                    row.put("fulfillment_id", resultSet.getString("fulfillment_id"));
                    row.put("order_line_id", resultSet.getString("order_line_id"));
                    row.put("order_line_component_id", nullableId(resultSet.getObject("order_line_component_id")));
                    row.put("raw_import_row_id", nullableId(resultSet.getObject("raw_import_row_id")));
                    row.put("outbound_order_no", resultSet.getString("outbound_order_no"));
                    row.put("provider_sku_code", resultSet.getString("provider_sku_code"));
                    row.put("instructed_quantity", resultSet.getBigDecimal("instructed_quantity").toPlainString());
                    row.put("unit", resultSet.getString("unit_snapshot"));
                    row.put("item_amount", resultSet.getBigDecimal("item_amount") == null
                            ? null : resultSet.getBigDecimal("item_amount").toPlainString());
                    return row;
                },
                exportId);
    }

    private Map<String, Object> downloadAudit(long exportId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) download_count, MIN(created_at) first_downloaded_at,
                       MAX(created_at) last_downloaded_at,
                       (array_agg(operator ORDER BY created_at DESC))[1] last_downloaded_by
                FROM app.audit_logs
                WHERE service='fulfillment-export' AND operation='file.download'
                  AND request_payload->>'export_id'=?
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> audit = new LinkedHashMap<>();
                    audit.put("download_count", resultSet.getInt("download_count"));
                    audit.put("first_downloaded_at", resultSet.getTimestamp("first_downloaded_at") == null
                            ? null : resultSet.getTimestamp("first_downloaded_at").toInstant());
                    audit.put("last_downloaded_at", resultSet.getTimestamp("last_downloaded_at") == null
                            ? null : resultSet.getTimestamp("last_downloaded_at").toInstant());
                    audit.put("last_downloaded_by", resultSet.getString("last_downloaded_by"));
                    return audit;
                },
                Long.toString(exportId));
    }

    private String usageStatus(Map<String, Object> export, Map<String, Object> download) {
        if (export.get("tracking_import_batch_id") != null) {
            return "RETURNED";
        }
        if (((Number) download.get("download_count")).intValue() == 0) {
            return "GENERATED_NOT_DOWNLOADED";
        }
        // #84：未发送的第三方导出没有权威 due（null）→ 不判超时，避免展示假的「回传超时」
        Object due = export.get("tracking_due_at");
        if (!(due instanceof Instant dueInstant)) {
            return "DOWNLOADED_WAITING_RETURN";
        }
        return Instant.now().isAfter(dueInstant) ? "RETURN_OVERDUE" : "DOWNLOADED_WAITING_RETURN";
    }

    private String nullableId(Object value) {
        return value == null ? null : value.toString();
    }

    private Instant nullableInstant(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record FileDownload(String filename, byte[] bytes, String contentType) {}
    private record JdWorkbook(byte[] bytes, String templateVersion) {}
    private record ShipmentPlan(long id, String outboundOrderNo) {}
    private record PlannedExportRow(int lineNo, ExportRow source, ShipmentPlan shipment) {}
    private record ProviderSkuHold(long orderId, long orderLineId, long fulfillmentId) {}
    record ReadyOrderRoute(List<Long> shipmentIds, long orderVersion) {}
    private record ReadyOrder(
            long version, String sourceChannel, String orderStatus, Long sourceImportBatchId, long lineCount) {}
    private record ExportRow(
            long rawRowId, long orderId, String orderNo, String sourceChannel, String sourceRef,
            Instant orderedAt, String remark,
            String receiverName, String receiverPhone, String receiverAddress,
            long orderLineId, int lineNo, String productName, String specification, String unit,
            long fulfillmentId, Long orderLineComponentId, BigDecimal fulfillmentQuantity,
            BigDecimal requestedQuantity, long providerId, String providerCode,
            String providerName, String providerType, int trackingSlaMinutes, String providerSkuCode) {}
}
