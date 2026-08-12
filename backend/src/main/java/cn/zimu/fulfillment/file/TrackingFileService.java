package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 履约方返回整批先校验、后在单事务内接收，不部分落账。 */
@Service
class TrackingFileService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ContentAddressedFileStore fileStore;
    private final SourceFileParser sourceFileParser;
    private final AuditLogService auditLogService;
    private final ShipmentTrackingService shipmentTrackingService;
    private final DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);

    TrackingFileService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ContentAddressedFileStore fileStore,
            SourceFileParser sourceFileParser,
            AuditLogService auditLogService,
            ShipmentTrackingService shipmentTrackingService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fileStore = fileStore;
        this.sourceFileParser = sourceFileParser;
        this.auditLogService = auditLogService;
        this.shipmentTrackingService = shipmentTrackingService;
    }

    @Transactional
    Map<String, Object> upload(
            long exportId,
            byte[] bytes,
            String originalFilename,
            String importMode,
            Long parentBatchId,
            String idempotencyKey,
            CommandContext context) {
        if (!"NEW".equals(importMode) || parentBatchId != null) {
            throw BusinessException.unprocessable(
                    "TRACKING_REVISION_UNSUPPORTED",
                    "履约返回修订必须等待人工冲突规则；当前仅接受 NEW");
        }
        ExportHeader export = export(exportId);
        if (!"THIRD_PARTY".equals(export.exportKind())) {
            throw BusinessException.unprocessable("JD_TRACKING_TEMPLATE_GATE", "当前缺少京东官方回传 golden 样表");
        }
        String hash = fileStore.sha256(bytes);
        Long replay = existing(exportId, hash);
        if (replay != null) {
            return get(replay);
        }

        List<TrackingRow> rows = parseAndValidateThirdParty(export, bytes);
        ContentAddressedFileStore.StoredFile retained = fileStore.put("tracking-imports", bytes, ".xlsx");
        String batchNo = "TRK-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, fulfillment_provider_id,
                     source_fulfillment_export_id, template_family, template_version, template_fingerprint,
                     original_file_name, content_sha256, file_ref, status, uploaded_by)
                VALUES (?, 'PROVIDER_TRACKING', 'NEW', 1, ?, ?, 'THIRD_PARTY_TRACKING', 'v1-24-columns',
                        'THIRD_PARTY-v1-24-columns', ?, ?, ?, 'PROCESSING', ?)
                RETURNING id
                """,
                Long.class,
                batchNo,
                export.providerId(),
                exportId,
                safeFilename(originalFilename),
                hash,
                retained.fileRef(),
                context.operator());

        int shipped = 0;
        int partial = 0;
        int failed = 0;
        for (TrackingRow row : rows) {
            jdbc.update(
                    """
                    INSERT INTO app.raw_import_rows
                        (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                         source_order_ref, status, order_id, order_line_id)
                    VALUES (?, '发货清单', 0, ?, ?::jsonb, ?, 'ACCEPTED', ?, ?)
                    """,
                    batchId,
                    row.rowIndex(),
                    json(row.cells()),
                    row.outboundOrderNo(),
                    row.orderId(),
                    row.orderLineId());
            shipmentTrackingService.accept(new ShipmentTrackingCommand(
                    batchId, row.shipmentId(), row.fulfillmentId(), row.orderLineId(), row.orderId(), row.result(),
                    row.shippedQuantity(), row.carrier() == null ? null : internalCarrierCode(row.carrier()),
                    row.carrier(), row.trackingNo(), row.shippedAt(), row.failureReason(), row.cells()), context);
            if ("FAILED".equals(row.result())) {
                failed++;
                continue;
            }
            if ("SHIPPED".equals(row.result())) {
                shipped++;
            } else {
                partial++;
            }
        }

        List<Long> sourceBatches = jdbc.query(
                """
                SELECT DISTINCT rir.import_batch_id
                FROM app.fulfillment_export_items fei
                JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                WHERE fei.fulfillment_export_id=?
                """,
                (resultSet, rowNum) -> resultSet.getLong(1), exportId);
        List<Long> sourceReturnIds = new ArrayList<>();
        for (long sourceBatchId : sourceBatches) {
            sourceReturnIds.add(generateSourceReturn(sourceBatchId, batchId, context.operator()));
        }
        jdbc.update(
                "UPDATE app.import_batches SET status='COMPLETED', processed_at=CURRENT_TIMESTAMP WHERE id=?",
                batchId);
        Map<String, Object> result = get(batchId);
        result.put("business_results", Map.of("shipped", shipped, "partial", partial, "failed", failed));
        result.put("generated_source_return_export_ids", sourceReturnIds.stream().map(Object::toString).toList());
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("provider-tracking").operation("file.upload")
                .requestPayload(Map.of("export_id", exportId, "content_sha256", hash, "idempotency_key", idempotencyKey))
                .responsePayload(result).httpStatus(201).businessCode("TRACKING_BATCH_ACCEPTED"));
        return result;
    }

    private List<TrackingRow> parseAndValidateThirdParty(ExportHeader export, byte[] bytes) {
        if (bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw BusinessException.unprocessable("TRACKING_CONTAINER_INVALID", "第三方履约返回必须是真实 XLSX");
        }
        List<TrackingRow> result = new ArrayList<>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(0);
            for (int index = 0; index < ProviderFileService.THIRD_PARTY_HEADERS.size(); index++) {
                if (!ProviderFileService.THIRD_PARTY_HEADERS.get(index)
                        .equals(formatter.formatCellValue(headerRow.getCell(index)).strip())) {
                    throw BusinessException.unprocessable("TRACKING_HEADER_INVALID", "回传文件必须保持精确 24 列及顺序");
                }
            }
            List<ExpectedExportLine> expected = expected(export.id());
            if (sheet.getLastRowNum() != expected.size()) {
                throw BusinessException.unprocessable("TRACKING_ROW_SET_MISMATCH", "回传行数与导出明细不一致");
            }
            for (int index = 0; index < expected.size(); index++) {
                var row = sheet.getRow(index + 1);
                if (row == null) {
                    throw BusinessException.unprocessable("TRACKING_ROW_MISSING", "回传文件存在空行");
                }
                Map<String, String> cells = new LinkedHashMap<>();
                for (int column = 0; column < ProviderFileService.THIRD_PARTY_HEADERS.size(); column++) {
                    cells.put(ProviderFileService.THIRD_PARTY_HEADERS.get(column),
                            formatter.formatCellValue(row.getCell(column)).strip());
                }
                ExpectedExportLine line = expected.get(index);
                for (int immutable = 0; immutable < 18; immutable++) {
                    String name = ProviderFileService.THIRD_PARTY_HEADERS.get(immutable);
                    if (!Objects.equals(normalizeNumber(cells.get(name)), normalizeNumber(line.outputCells().get(name)))) {
                        throw BusinessException.unprocessable(
                                "TRACKING_IMMUTABLE_FIELD_CHANGED", "回传修改了导出指令列: " + name);
                    }
                }
                result.add(validateResult(index + 2, cells, line));
            }
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unprocessable("TRACKING_READ_FAILED", "无法读取履约返回文件");
        }
    }

    private TrackingRow validateResult(int rowIndex, Map<String, String> cells, ExpectedExportLine line) {
        String result = cells.get("结果");
        if (!List.of("SHIPPED", "PARTIAL", "FAILED").contains(result)) {
            throw BusinessException.unprocessable("TRACKING_RESULT_INVALID", "结果必须为 SHIPPED/PARTIAL/FAILED");
        }
        if ("FAILED".equals(result)) {
            if (cells.get("异常原因").isBlank()) {
                throw BusinessException.unprocessable("TRACKING_FAILURE_REASON_REQUIRED", "FAILED 必须填异常原因");
            }
            return new TrackingRow(rowIndex, cells, result, line, null, null, null, null, cells.get("异常原因"));
        }
        BigDecimal quantity;
        try {
            quantity = new BigDecimal(cells.get("实际发货数量"));
        } catch (NumberFormatException exception) {
            throw BusinessException.unprocessable("TRACKING_QUANTITY_INVALID", "实际发货数量非法");
        }
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 3
                || quantity.compareTo(line.instructedQuantity()) > 0) {
            throw BusinessException.unprocessable("TRACKING_QUANTITY_INVALID", "实际数量必须大于0、不超过指令数且最多三位小数");
        }
        if (("SHIPPED".equals(result) && quantity.compareTo(line.instructedQuantity()) != 0)
                || ("PARTIAL".equals(result) && quantity.compareTo(line.instructedQuantity()) >= 0)) {
            throw BusinessException.unprocessable("TRACKING_RESULT_QUANTITY_MISMATCH", "结果与实发数量不一致");
        }
        String carrier = cells.get("快递公司");
        String trackingNo = cells.get("物流单号");
        if (carrier.isBlank() || trackingNo.isBlank()) {
            throw BusinessException.unprocessable("TRACKING_REQUIRED", "发货结果必须填快递公司与物流单号");
        }
        Instant shippedAt = cells.get("发货时间").isBlank()
                ? null
                : parseShippedAt(cells.get("发货时间"));
        return new TrackingRow(rowIndex, cells, result, line, quantity, carrier, trackingNo, shippedAt, null);
    }

    private Instant parseShippedAt(String value) {
        try {
            return LocalDateTime.parse(value, SOURCE_TIME).atZone(SHANGHAI).toInstant();
        } catch (java.time.format.DateTimeParseException exception) {
            throw BusinessException.unprocessable("TRACKING_SHIPPED_AT_INVALID", "发货时间必须为空或 yyyy-MM-dd HH:mm:ss");
        }
    }

    private long generateSourceReturn(long sourceBatchId, long trackingBatchId, String operator) {
        SourceBatch source = sourceBatch(sourceBatchId);
        ParsedSourceFile original = sourceFileParser.parse(fileStore.read(source.fileRef()));
        List<ReturnRow> returns = returnRows(sourceBatchId);
        Map<String, ReturnRow> byCoordinate = returns.stream().collect(java.util.stream.Collectors.toMap(
                row -> row.sheetIndex() + ":" + row.rowIndex(), row -> row));
        List<ParsedSourceRow> rendered = original.rows().stream().map(row -> {
            ReturnRow fill = byCoordinate.get(row.sheetIndex() + ":" + row.rowIndex());
            if (fill == null) {
                return row;
            }
            Map<String, String> cells = new LinkedHashMap<>(row.rawCells());
            switch (source.channel()) {
                case "CAISHIXIAN" -> {
                    cells.put("发货数量", fill.shippedQuantity().toPlainString());
                    cells.put("物流公司代码", fill.sourceCarrier());
                    cells.put("物流单号", fill.trackingNo());
                    cells.put("错误原因", "");
                }
                case "JUFUBAO" -> {
                    cells.put("是否发完", "是");
                    cells.put("发货数量", fill.shippedQuantity().toPlainString());
                    cells.put("快递公司", fill.sourceCarrier());
                    cells.put("快递单号", fill.trackingNo());
                }
                case "FEIXIANG" -> {
                    cells.put("物流状态", "已发货");
                    if (cells.containsKey("物流公司")) {
                        cells.put("物流公司", fill.sourceCarrier());
                    }
                    cells.put("物流单号", fill.trackingNo());
                }
                default -> throw new IllegalStateException("unsupported source return channel");
            }
            return copyWithCells(row, cells);
        }).toList();
        byte[] file = "FEIXIANG".equals(source.channel())
                ? trueCsv(source, rendered)
                : sourceWorkbook(source, rendered);
        String suffix = "FEIXIANG".equals(source.channel()) ? ".csv" : ".xlsx";
        ContentAddressedFileStore.StoredFile stored = fileStore.put("source-return-exports", file, suffix);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0)+1 FROM app.source_return_exports WHERE import_batch_id=?",
                Integer.class, sourceBatchId);
        boolean hasMultiShipmentFollowup = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.review_cases rc
                    JOIN app.raw_import_rows rir ON rir.order_line_id=rc.order_line_id
                    WHERE rir.import_batch_id=?
                      AND rc.reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP')
                """,
                Boolean.class,
                sourceBatchId));
        boolean isFinal = !hasMultiShipmentFollowup && returns.size() == jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status='ACCEPTED'",
                Integer.class, sourceBatchId);
        long returnId = jdbc.queryForObject(
                """
                INSERT INTO app.source_return_exports
                    (import_batch_id, generated_from_tracking_batch_id, version_no, is_final, template_version, tracking_cutoff_at,
                     file_ref, file_sha256, generated_by)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?) RETURNING id
                """,
                Long.class,
                sourceBatchId,
                trackingBatchId,
                version,
                isFinal,
                source.templateVersion(),
                stored.fileRef(),
                stored.sha256(),
                operator);
        for (ReturnRow row : returns) {
            ParsedSourceRow parsed = rendered.stream()
                    .filter(item -> item.sheetIndex() == row.sheetIndex() && item.rowIndex() == row.rowIndex())
                    .findFirst().orElseThrow();
            jdbc.update(
                    """
                    INSERT INTO app.source_return_export_items
                        (source_return_export_id, raw_import_row_id, order_line_id, shipment_id,
                         shipment_sequence, item_result, output_sheet_name, output_row_index,
                         shipped_quantity, logistics_company, tracking_number, fulfillment_outcome,
                         cancelled_quantity, output_cells)
                    VALUES (?, ?, ?, ?, ?, 'FILLED', ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    returnId,
                    row.rawRowId(),
                    row.orderLineId(),
                    row.shipmentId(),
                    row.shipmentSequence(),
                    parsed.sheetName(),
                    parsed.rowIndex(),
                    row.shippedQuantity(),
                    row.sourceCarrier(),
                    row.trackingNo(),
                    row.fulfillmentOutcome(),
                    row.cancelledQuantity(),
                    json(parsed.rawCells()));
            jdbc.update(
                    """
                    UPDATE app.order_lines ol SET processing_stage=CASE
                        WHEN EXISTS (
                            SELECT 1 FROM app.review_cases rc
                            WHERE rc.order_line_id=ol.id AND rc.status='OPEN'
                              AND rc.reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP'
                        ) THEN ol.processing_stage
                        ELSE 'RETURN_FILE_READY'
                    END
                    WHERE ol.id=?
                    """,
                    row.orderLineId());
        }
        return returnId;
    }

    private byte[] trueCsv(SourceBatch source, List<ParsedSourceRow> rows) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("source return requires source rows");
        }
        List<String> headers = new ArrayList<>(rows.getFirst().rawCells().keySet());
        boolean v2Gb18030Lf = source.templateVersion().startsWith("v2-gb18030-lf");
        String recordSeparator = v2Gb18030Lf ? "\n" : "\r\n";
        try (StringWriter writer = new StringWriter(); CSVPrinter printer = new CSVPrinter(
                writer, CSVFormat.RFC4180.builder()
                        .setHeader(headers.toArray(String[]::new))
                        .setRecordSeparator(recordSeparator)
                        .get())) {
            for (ParsedSourceRow row : rows) {
                printer.printRecord(headers.stream().map(header -> row.rawCells().getOrDefault(header, "")).toList());
            }
            printer.flush();
            return v2Gb18030Lf
                    ? writer.toString().getBytes(java.nio.charset.Charset.forName("GB18030"))
                    : ("\uFEFF" + writer).getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] sourceWorkbook(SourceBatch source, List<ParsedSourceRow> rows) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fileStore.read(source.fileRef())));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (ParsedSourceRow parsed : rows) {
                var sheet = workbook.getSheetAt(parsed.sheetIndex());
                var header = sheet.getRow(0);
                var data = sheet.getRow(parsed.rowIndex() - 1);
                for (int column = 0; column < header.getLastCellNum(); column++) {
                    String name = formatter.formatCellValue(header.getCell(column)).strip();
                    if (parsed.rawCells().containsKey(name)) {
                        if (data.getCell(column) == null) data.createCell(column);
                        data.getCell(column).setCellValue(parsed.rawCells().get(name));
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    List<Map<String, Object>> listSourceReturns(long sourceBatchId) {
        return jdbc.query(
                """
                SELECT id, import_batch_id, version_no, is_final, template_version,
                       tracking_cutoff_at, file_sha256, generated_at
                FROM app.source_return_exports WHERE import_batch_id=? ORDER BY version_no
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", resultSet.getString("id"));
                    result.put("import_batch_id", resultSet.getString("import_batch_id"));
                    result.put("version_no", resultSet.getInt("version_no"));
                    result.put("is_final", resultSet.getBoolean("is_final"));
                    result.put("template_version", resultSet.getString("template_version"));
                    result.put("tracking_cutoff_at", resultSet.getTimestamp("tracking_cutoff_at").toInstant());
                    result.put("file_sha256", resultSet.getString("file_sha256"));
                    result.put("generated_at", resultSet.getTimestamp("generated_at").toInstant());
                    return result;
                }, sourceBatchId);
    }

    ProviderFileService.FileDownload downloadSourceReturn(long returnId, CommandContext context) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT sre.file_ref, sre.template_version, ib.source_channel
                FROM app.source_return_exports sre JOIN app.import_batches ib ON ib.id=sre.import_batch_id
                WHERE sre.id=?
                """, returnId);
        if (rows.isEmpty()) throw BusinessException.notFound("来源回填文件不存在");
        String channel = rows.getFirst().get("source_channel").toString();
        String templateVersion = rows.getFirst().get("template_version").toString();
        String suffix = "FEIXIANG".equals(channel) ? ".csv" : ".xlsx";
        String contentType = "FEIXIANG".equals(channel)
                ? (templateVersion.startsWith("v2-gb18030-lf")
                        ? "text/csv;charset=GB18030"
                        : "text/csv;charset=UTF-8")
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).requestId(context.requestId()).traceId(context.traceId())
                .operator(context.operator()).actorType(AuditActorType.HUMAN)
                .service("source-return-export").operation("file.download")
                .requestPayload(Map.of("export_id", returnId)).httpStatus(200).businessCode("FILE_DOWNLOADED"));
        return new ProviderFileService.FileDownload(
                "source-return-" + returnId + suffix,
                fileStore.read(rows.getFirst().get("file_ref").toString()),
                contentType);
    }

    Map<String, Object> get(long batchId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT id, batch_no, batch_type, import_mode, revision_no, fulfillment_provider_id,
                       source_fulfillment_export_id, template_family, template_version, template_fingerprint,
                       original_file_name, content_sha256, status, received_at, processed_at
                FROM app.import_batches WHERE id=? AND batch_type='PROVIDER_TRACKING'
                """, batchId);
        if (rows.isEmpty()) throw BusinessException.notFound("运单导入批次不存在");
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        row.replaceAll((key, value) -> key.endsWith("_id") && value != null ? value.toString() : value);
        row.put("row_counts", rowCounts(batchId));
        row.put("generated_fulfillment_export_ids", List.of());
        row.put("generated_source_return_export_ids", generatedSourceReturnIds(batchId));
        row.put("business_results", businessResults(batchId));
        row.put("rows", trackingRows(batchId));
        return row;
    }

    private List<ExpectedExportLine> expected(long exportId) {
        return jdbc.query(
                """
                SELECT fei.export_line_no, fei.shipment_id, fei.fulfillment_id, fei.order_line_id,
                       fei.outbound_order_no, fei.instructed_quantity, fei.output_cells::text output_cells,
                       s.order_id
                FROM app.fulfillment_export_items fei JOIN app.shipments s ON s.id=fei.shipment_id
                WHERE fei.fulfillment_export_id=? ORDER BY fei.export_line_no
                """,
                (resultSet, rowNum) -> new ExpectedExportLine(
                        resultSet.getInt("export_line_no"), resultSet.getLong("shipment_id"),
                        resultSet.getLong("fulfillment_id"), resultSet.getLong("order_line_id"),
                        resultSet.getLong("order_id"), resultSet.getString("outbound_order_no"),
                        resultSet.getBigDecimal("instructed_quantity"), jsonMap(resultSet.getString("output_cells"))),
                exportId);
    }

    private ExportHeader export(long exportId) {
        List<ExportHeader> rows = jdbc.query(
                """
                SELECT id, fulfillment_provider_id, export_batch_no, export_kind
                FROM app.fulfillment_exports WHERE id=?
                """,
                (resultSet, rowNum) -> new ExportHeader(
                        resultSet.getLong("id"), resultSet.getLong("fulfillment_provider_id"),
                        resultSet.getString("export_batch_no"), resultSet.getString("export_kind")),
                exportId);
        if (rows.isEmpty()) throw BusinessException.notFound("履约导出不存在");
        return rows.getFirst();
    }

    private SourceBatch sourceBatch(long sourceBatchId) {
        return jdbc.queryForObject(
                """
                SELECT source_channel, template_version, file_ref FROM app.import_batches WHERE id=?
                """,
                (resultSet, rowNum) -> new SourceBatch(
                        resultSet.getString("source_channel"), resultSet.getString("template_version"),
                        resultSet.getString("file_ref")), sourceBatchId);
    }

    private List<ReturnRow> returnRows(long sourceBatchId) {
        return jdbc.query(
                """
                SELECT rir.id raw_row_id, rir.sheet_index, rir.row_index, rir.order_line_id,
                       s.id shipment_id, s.shipment_sequence, si.shipped_quantity,
                       t.tracking_number, f.outcome, f.cancelled_quantity,
                       cc.config #>> ARRAY['carrier_mappings', t.logistics_company_code] source_carrier
                FROM app.raw_import_rows rir
                JOIN app.fulfillments f ON f.order_line_id=rir.order_line_id
                JOIN app.shipment_items si ON si.fulfillment_id=f.id AND si.shipped_quantity>0
                JOIN app.shipments s ON s.id=si.shipment_id
                JOIN app.trackings t ON t.shipment_id=s.id
                JOIN app.import_batches ib ON ib.id=rir.import_batch_id
                JOIN app.connector_configs cc ON cc.source_channel=ib.source_channel
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                  AND s.shipment_sequence=(SELECT MIN(s2.shipment_sequence) FROM app.shipments s2
                                           JOIN app.shipment_items si2 ON si2.shipment_id=s2.id
                                           WHERE si2.fulfillment_id=f.id AND si2.shipped_quantity>0)
                ORDER BY rir.sheet_index, rir.row_index
                """,
                (resultSet, rowNum) -> {
                    String carrier = resultSet.getString("source_carrier");
                    if (carrier == null || carrier.isBlank()) {
                        throw BusinessException.unprocessable("CARRIER_MAPPING", "来源渠道快递映射缺失");
                    }
                    return new ReturnRow(
                            resultSet.getLong("raw_row_id"), resultSet.getInt("sheet_index"),
                            resultSet.getInt("row_index"), resultSet.getLong("order_line_id"),
                            resultSet.getLong("shipment_id"), resultSet.getInt("shipment_sequence"),
                            resultSet.getBigDecimal("shipped_quantity"), carrier,
                            resultSet.getString("tracking_number"), resultSet.getString("outcome"),
                            resultSet.getBigDecimal("cancelled_quantity"));
                }, sourceBatchId);
    }

    private ParsedSourceRow copyWithCells(ParsedSourceRow row, Map<String, String> cells) {
        return new ParsedSourceRow(
                row.sheetName(), row.sheetIndex(), row.rowIndex(), cells, row.sourceOrderRef(), row.sourceLineRef(),
                row.sourceCustomerRef(), row.customerName(), row.receiverName(), row.receiverPhone(), row.receiverAddress(),
                row.receiverProvince(), row.receiverCity(), row.receiverDistrict(), row.sourceSkuRef(), row.productName(),
                row.specification(), row.unit(), row.quantity(), row.orderedAt(), row.settlementMethod(), row.remark(),
                row.errorCode(), row.errorMessage());
    }

    private String internalCarrierCode(String providerCarrier) {
        if ("京东物流".equals(providerCarrier) || "JD".equals(providerCarrier)) return "JD";
        throw BusinessException.unprocessable("CARRIER_MAPPING", "未配置内部快递映射: " + providerCarrier);
    }

    private Long existing(long exportId, String hash) {
        List<Long> ids = jdbc.query(
                """
                SELECT id FROM app.import_batches WHERE batch_type='PROVIDER_TRACKING'
                  AND source_fulfillment_export_id=? AND content_sha256=? LIMIT 1
                """, (resultSet, rowNum) -> resultSet.getLong(1), exportId, hash);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Map<String, Integer> rowCounts(long batchId) {
        int accepted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status='ACCEPTED'",
                Integer.class, batchId);
        return Map.of("total", accepted, "accepted", accepted, "need_review", 0, "rejected", 0);
    }

    private Map<String, Integer> businessResults(long batchId) {
        return jdbc.queryForObject(
                """
                SELECT COUNT(*) FILTER (WHERE raw_cells->>'结果'='SHIPPED') shipped,
                       COUNT(*) FILTER (WHERE raw_cells->>'结果'='PARTIAL') partial,
                       COUNT(*) FILTER (WHERE raw_cells->>'结果'='FAILED') failed
                FROM app.raw_import_rows WHERE import_batch_id=?
                """,
                (resultSet, rowNum) -> Map.of(
                        "shipped", resultSet.getInt("shipped"),
                        "partial", resultSet.getInt("partial"),
                        "failed", resultSet.getInt("failed")), batchId);
    }

    private List<Map<String, Object>> trackingRows(long batchId) {
        return jdbc.query(
                """
                SELECT id, sheet_name, sheet_index, row_index, raw_cells::text raw_cells,
                       source_order_ref, status, order_id, order_line_id
                FROM app.raw_import_rows WHERE import_batch_id=? ORDER BY row_index
                """,
                (resultSet, rowNum) -> Map.of(
                        "id", resultSet.getString("id"), "sheet_name", resultSet.getString("sheet_name"),
                        "sheet_index", resultSet.getInt("sheet_index"), "row_index", resultSet.getInt("row_index"),
                        "raw_cells", jsonMap(resultSet.getString("raw_cells")),
                        "source_order_ref", resultSet.getString("source_order_ref"), "status", resultSet.getString("status"),
                        "order_id", resultSet.getString("order_id"), "order_line_id", resultSet.getString("order_line_id")),
                batchId);
    }

    private List<String> generatedSourceReturnIds(long trackingBatchId) {
        return jdbc.query(
                """
                SELECT id FROM app.source_return_exports
                WHERE generated_from_tracking_batch_id=? ORDER BY id
                """, (resultSet, rowNum) -> resultSet.getString(1), trackingBatchId);
    }

    private String normalizeNumber(Object value) {
        String text = value == null ? "" : value.toString();
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return text;
        }
    }

    private String safeFilename(String value) {
        String safe = value == null || value.isBlank() ? "tracking.xlsx" : java.nio.file.Path.of(value).getFileName().toString();
        return safe.length() > 255 ? safe.substring(safe.length() - 255) : safe;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private Map<String, Object> jsonMap(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private record ExportHeader(long id, long providerId, String batchNo, String exportKind) {}
    private record ExpectedExportLine(
            int lineNo, long shipmentId, long fulfillmentId, long orderLineId, long orderId,
            String outboundOrderNo, BigDecimal instructedQuantity, Map<String, Object> outputCells) {}
    private record TrackingRow(
            int rowIndex, Map<String, String> cells, String result, ExpectedExportLine line,
            BigDecimal shippedQuantity, String carrier, String trackingNo, Instant shippedAt, String failureReason) {
        long shipmentId() { return line.shipmentId(); }
        long fulfillmentId() { return line.fulfillmentId(); }
        long orderLineId() { return line.orderLineId(); }
        long orderId() { return line.orderId(); }
        String outboundOrderNo() { return line.outboundOrderNo(); }
    }
    private record SourceBatch(String channel, String templateVersion, String fileRef) {}
    private record ReturnRow(
            long rawRowId, int sheetIndex, int rowIndex, long orderLineId, long shipmentId, int shipmentSequence,
            BigDecimal shippedQuantity, String sourceCarrier, String trackingNo, String fulfillmentOutcome,
            BigDecimal cancelledQuantity) {}
}
