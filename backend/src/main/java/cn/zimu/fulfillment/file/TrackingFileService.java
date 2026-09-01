package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingAcceptance;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingBatchCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentTrackingCommand;
import cn.zimu.fulfillment.fulfillment.CarrierPrefixMatcher;
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
import java.util.LinkedHashSet;
import java.util.Set;
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
public class TrackingFileService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ContentAddressedFileStore fileStore;
    private final SourceFileParser sourceFileParser;
    private final AuditLogService auditLogService;
    private final ShipmentTrackingService shipmentTrackingService;
    private final FulfillmentExportWecomService wecomExportService;
    private final CarrierPrefixMatcher carrierMatcher;
    private final SourceReturnDerivationQueue sourceReturnDerivations;
    private final DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);

    TrackingFileService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ContentAddressedFileStore fileStore,
            SourceFileParser sourceFileParser,
            AuditLogService auditLogService,
            ShipmentTrackingService shipmentTrackingService,
            FulfillmentExportWecomService wecomExportService,
            CarrierPrefixMatcher carrierMatcher,
            SourceReturnDerivationQueue sourceReturnDerivations) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fileStore = fileStore;
        this.sourceFileParser = sourceFileParser;
        this.auditLogService = auditLogService;
        this.shipmentTrackingService = shipmentTrackingService;
        this.wecomExportService = wecomExportService;
        this.carrierMatcher = carrierMatcher;
        this.sourceReturnDerivations = sourceReturnDerivations;
    }

    /**
     * 企微文件任务的只读解析 seam：复用后台上传的 24 列、不可变指令列、数量与结果校验，
     * 但只返回草稿输入，绝不创建 import batch、Shipment/Tracking 或来源回填文件。
     */
    ParsedTrackingFile parseForDraft(byte[] bytes) {
        ExportHeader export = identifyExport(bytes);
        List<TrackingRow> rows = collapseBundleComponentRows(parseAndValidate(export, bytes));
        validateShipmentGroups(rows);
        List<ParsedTrackingRow> parsed = rows.stream()
                .map(row -> new ParsedTrackingRow(
                        row.rowIndex(),
                        row.shipmentId(),
                        row.fulfillmentId(),
                        row.orderLineId(),
                        row.orderId(),
                        row.cells().get("收件人"),
                        row.result(),
                        row.shippedQuantity(),
                        row.carrier() == null ? null : internalCarrier(row.carrier()).code(),
                        row.carrier(),
                        row.trackingNo(),
                        row.failureReason(),
                        row.line().instructedQuantity(),
                        Map.copyOf(row.cells())))
                .toList();
        return new ParsedTrackingFile(export.id(), export.batchNo(), parsed);
    }

    @Transactional
    TrackingUploadResult upload(
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
            List<Long> shipmentIds = jdbc.queryForList(
                    """
                    SELECT DISTINCT shipment_id FROM app.trackings
                    WHERE provider_tracking_batch_id=? ORDER BY shipment_id
                    """,
                    Long.class,
                    replay);
            List<Long> taskIds = shipmentIds.stream()
                    .map(shipmentId -> sourceReturnDerivations.enqueue(
                            shipmentId, replay, context.operator()))
                    .toList();
            return new TrackingUploadResult(get(replay), taskIds);
        }

        List<TrackingRow> rows = parseAndValidate(export, bytes);
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
        }

        List<TrackingRow> acceptedRows = collapseBundleComponentRows(rows);
        acceptTrackingRows(batchId, acceptedRows, context);
        int shipped = 0;
        int partial = 0;
        int failed = 0;
        for (TrackingRow row : acceptedRows) {
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

        List<Long> sourceReturnTaskIds = acceptedRows.stream()
                .map(TrackingRow::shipmentId)
                .distinct()
                .map(shipmentId -> sourceReturnDerivations.enqueue(
                        shipmentId, batchId, context.operator()))
                .toList();
        jdbc.update(
                "UPDATE app.import_batches SET status='COMPLETED', processed_at=CURRENT_TIMESTAMP WHERE id=?",
                batchId);
        // #84：接收事务内主动做收齐判定；已全部收齐的导出标记 COMPLETED（scanner 发送前也会复查自愈）
        wecomExportService.markTrackingReceived(exportId);
        Map<String, Object> result = get(batchId);
        result.put("business_results", Map.of("shipped", shipped, "partial", partial, "failed", failed));
        result.put("generated_source_return_export_ids", List.of());
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("provider-tracking").operation("file.upload")
                .requestPayload(Map.of("export_id", exportId, "content_sha256", hash, "idempotency_key", idempotencyKey))
                .responsePayload(result).httpStatus(201).businessCode("TRACKING_BATCH_ACCEPTED"));
        return new TrackingUploadResult(result, sourceReturnTaskIds);
    }

    /**
     * 礼包在第三方文件中按组件展开，但业务履约数量是完整礼包份数。组件原行全部留证，
     * 此处按 Fulfillment 聚合并校验完整比例与同一运单，避免一份礼包重复接受 Tracking。
     */
    private List<TrackingRow> collapseBundleComponentRows(List<TrackingRow> rows) {
        Map<Long, List<TrackingRow>> byFulfillment = new LinkedHashMap<>();
        for (TrackingRow row : rows) {
            byFulfillment.computeIfAbsent(row.fulfillmentId(), ignored -> new ArrayList<>()).add(row);
        }
        List<TrackingRow> collapsed = new ArrayList<>();
        for (List<TrackingRow> group : byFulfillment.values()) {
            TrackingRow first = group.getFirst();
            boolean componentExport = first.line().orderLineComponentId() != null;
            if (!componentExport) {
                if (group.size() != 1) {
                    throw BusinessException.unprocessable(
                            "TRACKING_FULFILLMENT_DUPLICATE", "普通履约明细不能重复出现在回传文件中");
                }
                collapsed.add(first);
                continue;
            }
            if (group.stream().anyMatch(row -> row.line().orderLineComponentId() == null)
                    || group.stream().map(TrackingRow::shipmentId).distinct().count() != 1
                    || group.stream().map(TrackingRow::orderLineId).distinct().count() != 1
                    || group.stream().map(row -> row.cells().get("礼包分组标识")).distinct().count() != 1
                    || first.cells().get("礼包分组标识").isBlank()) {
                throw BusinessException.unprocessable(
                        "TRACKING_BUNDLE_GROUP_MISMATCH", "礼包组件必须完整归属同一礼包分组与 Shipment");
            }
            if (group.stream().map(TrackingRow::result).distinct().count() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_BUNDLE_RESULT_MISMATCH", "同一礼包分组的组件结果必须一致");
            }
            if ("FAILED".equals(first.result())) {
                if (group.stream().map(TrackingRow::failureReason).distinct().count() != 1) {
                    throw BusinessException.unprocessable(
                            "TRACKING_BUNDLE_RESULT_MISMATCH", "同一礼包分组的失败原因必须一致");
                }
                collapsed.add(first);
                continue;
            }
            if (group.stream().map(TrackingRow::carrier).distinct().count() != 1
                    || group.stream().map(TrackingRow::trackingNo).distinct().count() != 1
                    || group.stream().map(TrackingRow::shippedAt).distinct().count() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_BUNDLE_TRACKING_MISMATCH", "同一礼包分组的组件必须使用同一承运商、运单与发货时间");
            }
            Integer bundleQuantity = null;
            for (TrackingRow row : group) {
                Integer componentQuantity = row.line().componentQuantityPerBundle();
                Integer current;
                if (componentQuantity == null || componentQuantity <= 0
                        || row.shippedQuantity() % componentQuantity != 0) {
                    throw BusinessException.unprocessable(
                            "TRACKING_BUNDLE_QUANTITY_MISMATCH", "礼包组件实发数量无法还原为完整礼包份数");
                }
                current = row.shippedQuantity() / componentQuantity;
                if (current <= 0 || (bundleQuantity != null && !current.equals(bundleQuantity))) {
                    throw BusinessException.unprocessable(
                            "TRACKING_BUNDLE_QUANTITY_MISMATCH", "同一礼包分组的组件实发比例必须一致");
                }
                bundleQuantity = current;
            }
            collapsed.add(new TrackingRow(
                    first.rowIndex(), first.cells(), first.result(), first.line(), bundleQuantity,
                    first.carrier(), first.trackingNo(), first.shippedAt(), null));
        }
        return List.copyOf(collapsed);
    }

    /** 同 Shipment 的完整发货一次接受；多明细 PARTIAL/FAILED 暂无安全批量语义，失败关闭。 */
    private void acceptTrackingRows(long batchId, List<TrackingRow> rows, CommandContext context) {
        Map<Long, List<TrackingRow>> byShipment = new LinkedHashMap<>();
        for (TrackingRow row : rows) {
            byShipment.computeIfAbsent(row.shipmentId(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<TrackingRow> shipmentRows : byShipment.values()) {
            TrackingRow first = shipmentRows.getFirst();
            if (shipmentRows.size() == 1) {
                acceptTrackingRow(batchId, first, context);
                continue;
            }
            if (shipmentRows.stream().anyMatch(row -> !"SHIPPED".equals(row.result()))
                    || shipmentRows.stream().map(TrackingRow::carrier).distinct().count() != 1
                    || shipmentRows.stream().map(TrackingRow::trackingNo).distinct().count() != 1
                    || shipmentRows.stream().map(TrackingRow::shippedAt).distinct().count() != 1
                    || shipmentRows.stream().map(TrackingRow::orderId).distinct().count() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_MULTI_ITEM_SHIPMENT_MISMATCH",
                        "同一 Shipment 的多履约明细必须完整发货并使用同一运单");
            }
            CarrierPrefixMatcher.Carrier carrier = internalCarrier(first.carrier());
            ShipmentTrackingAcceptance acceptance = shipmentTrackingService.acceptShipment(
                    new ShipmentTrackingBatchCommand(
                            batchId,
                            first.shipmentId(),
                            first.orderId(),
                            shipmentRows.stream()
                                    .map(row -> new ShipmentTrackingBatchCommand.Item(
                                            row.fulfillmentId(), row.orderLineId(), row.shippedQuantity()))
                                    .toList(),
                            carrier.code(),
                            carrier.name(),
                            first.trackingNo(),
                            first.shippedAt(),
                            Map.of("rows", shipmentRows.stream().map(TrackingRow::cells).toList()),
                            "第三方履约文件整批回传"),
                    context);
            if (acceptance.conflicted()) {
                throw BusinessException.conflict("TRACKING_CONFLICT", "同一 Shipment 已存在不同运单");
            }
        }
    }

    private void acceptTrackingRow(long batchId, TrackingRow row, CommandContext context) {
        CarrierPrefixMatcher.Carrier carrier = row.carrier() == null ? null : internalCarrier(row.carrier());
        shipmentTrackingService.accept(new ShipmentTrackingCommand(
                batchId, row.shipmentId(), row.fulfillmentId(), row.orderLineId(), row.orderId(), row.result(),
                row.shippedQuantity(), carrier == null ? null : carrier.code(),
                carrier == null ? null : carrier.name(), row.trackingNo(), row.shippedAt(), row.failureReason(), row.cells()), context);
    }

    /**
     * 回传文件的格式分派：先认人读八列（v2，2026-08-27 起的下发格式），认不出退回
     * 精确 24 列（v1 兼容输入）。两套格式都以同一份 {@code fulfillment_export_items}
     * 为对照事实，产出同一种 {@link TrackingRow}。
     */
    private List<TrackingRow> parseAndValidate(ExportHeader export, byte[] bytes) {
        return isHumanFormat(bytes)
                ? parseAndValidateHuman(export, bytes)
                : parseAndValidateThirdParty(export, bytes);
    }

    private ExportHeader identifyExport(byte[] bytes) {
        return isHumanFormat(bytes) ? identifyHumanExport(bytes) : identifyThirdPartyExport(bytes);
    }

    /** 表头是否是人读八列。只读第一行，不动数据区。 */
    private boolean isHumanFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            return false;
        }
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var header = workbook.getSheetAt(0).getRow(0);
            if (header == null
                    || header.getLastCellNum() != ProviderFileService.HUMAN_THIRD_PARTY_HEADERS.size()) {
                return false;
            }
            for (int index = 0; index < ProviderFileService.HUMAN_THIRD_PARTY_HEADERS.size(); index++) {
                if (!ProviderFileService.HUMAN_THIRD_PARTY_HEADERS.get(index)
                        .equals(formatter.formatCellValue(header.getCell(index)).strip())) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 人读格式的导出定位：没有「导出批次号」列，改用**出库单号**——它是系统签发的
     * 稳定业务号，每行都有。全部出库单号必须落在同一个 THIRD_PARTY 导出里，
     * 跨导出混填直接拒绝（人把两份清单拼一张表回传，系统不猜哪行归谁）。
     */
    private ExportHeader identifyHumanExport(byte[] bytes) {
        Set<String> outboundNos = new LinkedHashSet<>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_SHEET_SET_INVALID", "回传文件必须且只能保留一个发货清单工作表");
            }
            var sheet = workbook.getSheetAt(0);
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                String outboundNo = formatter.formatCellValue(row.getCell(0)).strip();
                if (!outboundNo.isEmpty()) outboundNos.add(outboundNo);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unprocessable("TRACKING_READ_FAILED", "无法读取履约返回文件");
        }
        if (outboundNos.isEmpty()) {
            throw BusinessException.unprocessable("TRACKING_ROW_MISSING", "回传文件没有任何出库单号");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(outboundNos.size(), "?"));
        List<ExportHeader> exports = jdbc.query(
                "SELECT DISTINCT fe.id, fe.fulfillment_provider_id, fe.export_batch_no, fe.export_kind"
                        + " FROM app.fulfillment_export_items fei"
                        + " JOIN app.fulfillment_exports fe ON fe.id = fei.fulfillment_export_id"
                        + " WHERE fe.export_kind = 'THIRD_PARTY' AND fei.outbound_order_no IN (" + placeholders + ")",
                (rs, rowNum) -> new ExportHeader(
                        rs.getLong("id"), rs.getLong("fulfillment_provider_id"),
                        rs.getString("export_batch_no"), rs.getString("export_kind")),
                outboundNos.toArray());
        if (exports.isEmpty()) {
            throw BusinessException.unprocessable(
                    "TRACKING_EXPORT_NOT_FOUND", "出库单号不属于任何第三方发货清单");
        }
        if (exports.size() > 1) {
            throw BusinessException.unprocessable(
                    "TRACKING_EXPORT_AMBIGUOUS", "回传文件混合了多个发货清单批次，请按批次分开回传");
        }
        return exports.getFirst();
    }

    /**
     * 人读八列的解析与校验。对照事实仍是导出明细：行数必须一致；
     * 身份列（出库单号/收件人/电话/地址/品名/数量）逐行与导出时的指令一致；
     * 运单号与快递公司必填——这份清单发出去就是为了这两格回来。
     *
     * <p>行匹配按（出库单号+品名+数量）配对而不是按行序：人会排序、会插行，
     * 24 列那套「严格行序」在人读格式上必然误伤。同键多行按出现顺序依次配对。
     */
    private List<TrackingRow> parseAndValidateHuman(ExportHeader export, byte[] bytes) {
        List<ExpectedExportLine> expected = expected(export.id());
        Map<String, java.util.Deque<ExpectedExportLine>> byKey = new LinkedHashMap<>();
        for (ExpectedExportLine line : expected) {
            byKey.computeIfAbsent(humanKey(
                            text(line.outputCells().get("出库单号")),
                            text(line.outputCells().get("品名"))),
                    ignored -> new java.util.ArrayDeque<>()).add(line);
        }
        List<TrackingRow> result = new ArrayList<>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            int dataRows = 0;
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                Map<String, String> cells = new LinkedHashMap<>();
                for (int column = 0; column < ProviderFileService.HUMAN_THIRD_PARTY_HEADERS.size(); column++) {
                    String header = ProviderFileService.HUMAN_THIRD_PARTY_HEADERS.get(column);
                    cells.put(header, "数量".equals(header)
                            ? ExcelCellValues.exactCount(row.getCell(column), formatter).strip()
                            : formatter.formatCellValue(row.getCell(column)).strip());
                }
                if (cells.values().stream().allMatch(String::isEmpty)) continue;
                if (row.getLastCellNum() > ProviderFileService.HUMAN_THIRD_PARTY_HEADERS.size()) {
                    throw BusinessException.unprocessable(
                            "TRACKING_ROW_COLUMN_INVALID", "回传文件数据行不得超出发货清单八列");
                }
                dataRows++;
                String key = humanKey(cells.get("出库单号"), cells.get("品名"));
                java.util.Deque<ExpectedExportLine> candidates = byKey.get(key);
                if (candidates == null || candidates.isEmpty()) {
                    throw BusinessException.unprocessable(
                            "TRACKING_ROW_UNMATCHED",
                            "第 " + (index + 1) + " 行与发货清单对不上（出库单号/品名被改动）");
                }
                ExpectedExportLine line = candidates.pollFirst();
                requireHumanIdentity(index + 1, cells, "收件人姓名", text(line.outputCells().get("收件人")));
                requireHumanIdentity(index + 1, cells, "电话", text(line.outputCells().get("电话")));
                requireHumanIdentity(index + 1, cells, "收货地址", text(line.outputCells().get("地址")));
                String trackingNo = cells.get("运单号");
                String carrier = cells.get("快递公司");
                if (trackingNo.isEmpty() || carrier.isEmpty()) {
                    throw BusinessException.unprocessable(
                            "TRACKING_REQUIRED",
                            "第 " + (index + 1) + " 行发货结果必须填快递公司与运单号");
                }
                // 数量列在人读格式里承载「实发」：与指令相等 = 全部发出；改小 = 部分发货
                //（履约方少发时的自然写法就是把数量改成实发数）；改大或非法直接拒绝。
                int shipped;
                try {
                    shipped = CountQuantity.fromPositiveFileValue(cells.get("数量"));
                } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException exception) {
                    throw BusinessException.unprocessable(
                            "TRACKING_QUANTITY_INVALID", "第 " + (index + 1) + " 行数量不是有效数字");
                }
                if (shipped > line.instructedQuantity()) {
                    throw BusinessException.unprocessable(
                            "TRACKING_QUANTITY_INVALID",
                            "第 " + (index + 1) + " 行实发数量必须为不超过请求数量的正整数");
                }
                String rowResult = shipped == line.instructedQuantity() ? "SHIPPED" : "PARTIAL";
                // 兼容下游既有键位：收件人 / 礼包分组标识 取导出指令原值；「结果」是人读八列
                // 没有的列，把解析推导值一并落进 raw_cells——business_results 读模型
                // （raw_cells->>'结果' 聚合）才能与上传响应的内存计数保持同一口径。
                cells.put("收件人", text(line.outputCells().get("收件人")));
                cells.put("礼包分组标识", text(line.outputCells().get("礼包分组标识")));
                cells.put("结果", rowResult);
                result.add(new TrackingRow(
                        index + 1, cells, rowResult, line,
                        shipped, carrier, trackingNo, null, null));
            }
            if (dataRows != expected.size()) {
                throw BusinessException.unprocessable(
                        "TRACKING_ROW_SET_MISMATCH", "回传行数与发货清单明细不一致");
            }
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unprocessable("TRACKING_READ_FAILED", "无法读取履约返回文件");
        }
    }

    private String humanKey(String outboundNo, String productName) {
        return outboundNo + "\u001f" + productName;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private void requireHumanIdentity(int rowNo, Map<String, String> cells, String header, String expected) {
        if (!Objects.equals(cells.get(header), expected == null ? "" : expected.strip())) {
            throw BusinessException.unprocessable(
                    "TRACKING_IMMUTABLE_FIELD_CHANGED",
                    "第 " + rowNo + " 行改动了发货清单的身份列: " + header);
        }
    }

    private List<TrackingRow> parseAndValidateThirdParty(ExportHeader export, byte[] bytes) {
        if (bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw BusinessException.unprocessable("TRACKING_CONTAINER_INVALID", "第三方履约返回必须是真实 XLSX");
        }
        List<TrackingRow> result = new ArrayList<>();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_SHEET_SET_INVALID", "回传文件必须且只能保留一个发货清单工作表");
            }
            var sheet = workbook.getSheetAt(0);
            var headerRow = sheet.getRow(0);
            if (headerRow == null
                    || headerRow.getLastCellNum() != ProviderFileService.THIRD_PARTY_HEADERS.size()) {
                throw BusinessException.unprocessable("TRACKING_HEADER_INVALID", "回传文件必须保持精确 24 列及顺序");
            }
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
                if (row.getLastCellNum() > ProviderFileService.THIRD_PARTY_HEADERS.size()) {
                    throw BusinessException.unprocessable(
                            "TRACKING_ROW_COLUMN_INVALID", "回传文件数据行必须保持精确 24 列");
                }
                Map<String, String> cells = new LinkedHashMap<>();
                for (int column = 0; column < ProviderFileService.THIRD_PARTY_HEADERS.size(); column++) {
                    String header = ProviderFileService.THIRD_PARTY_HEADERS.get(column);
                    boolean count = "请求发货数量".equals(header) || "实际发货数量".equals(header);
                    cells.put(header, count
                            ? ExcelCellValues.exactCount(row.getCell(column), formatter).strip()
                            : formatter.formatCellValue(row.getCell(column)).strip());
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

    /** 只读识别导出：先验证精确 24 列，再用首行「导出批次号」定位唯一 THIRD_PARTY 导出。 */
    private ExportHeader identifyThirdPartyExport(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw BusinessException.unprocessable("TRACKING_CONTAINER_INVALID", "第三方履约返回必须是真实 XLSX");
        }
        String batchNo;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_SHEET_SET_INVALID", "回传文件必须且只能保留一个发货清单工作表");
            }
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() != ProviderFileService.THIRD_PARTY_HEADERS.size()) {
                throw BusinessException.unprocessable("TRACKING_HEADER_INVALID", "回传文件必须保持精确 24 列及顺序");
            }
            for (int index = 0; index < ProviderFileService.THIRD_PARTY_HEADERS.size(); index++) {
                if (!ProviderFileService.THIRD_PARTY_HEADERS.get(index)
                        .equals(formatter.formatCellValue(header.getCell(index)).strip())) {
                    throw BusinessException.unprocessable("TRACKING_HEADER_INVALID", "回传文件必须保持精确 24 列及顺序");
                }
            }
            var first = sheet.getRow(1);
            batchNo = first == null ? "" : formatter.formatCellValue(first.getCell(0)).strip();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw BusinessException.unprocessable("TRACKING_READ_FAILED", "无法读取履约返回文件");
        }
        if (batchNo.isBlank()) {
            throw BusinessException.unprocessable("TRACKING_EXPORT_BATCH_MISSING", "回传文件缺少导出批次号");
        }
        List<ExportHeader> exports = jdbc.query(
                """
                SELECT id, fulfillment_provider_id, export_batch_no, export_kind
                FROM app.fulfillment_exports WHERE export_batch_no=?
                """,
                (resultSet, rowNum) -> new ExportHeader(
                        resultSet.getLong("id"),
                        resultSet.getLong("fulfillment_provider_id"),
                        resultSet.getString("export_batch_no"),
                        resultSet.getString("export_kind")),
                batchNo);
        if (exports.isEmpty()) {
            throw BusinessException.unprocessable("TRACKING_EXPORT_NOT_FOUND", "回传文件不属于任何已登记履约导出");
        }
        ExportHeader export = exports.getFirst();
        if (!"THIRD_PARTY".equals(export.exportKind())) {
            throw BusinessException.unprocessable("JD_TRACKING_TEMPLATE_GATE", "当前缺少京东官方回传 golden 样表");
        }
        return export;
    }

    /** 与正式接收保持同一 Shipment 聚合门禁，但不写任何业务事实。 */
    private void validateShipmentGroups(List<TrackingRow> rows) {
        Map<Long, List<TrackingRow>> byShipment = new LinkedHashMap<>();
        for (TrackingRow row : rows) {
            byShipment.computeIfAbsent(row.shipmentId(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<TrackingRow> shipmentRows : byShipment.values()) {
            if (shipmentRows.size() == 1) {
                continue;
            }
            if (shipmentRows.stream().anyMatch(row -> !"SHIPPED".equals(row.result()))
                    || shipmentRows.stream().map(TrackingRow::carrier).distinct().count() != 1
                    || shipmentRows.stream().map(TrackingRow::trackingNo).distinct().count() != 1
                    || shipmentRows.stream().map(TrackingRow::shippedAt).distinct().count() != 1
                    || shipmentRows.stream().map(TrackingRow::orderId).distinct().count() != 1) {
                throw BusinessException.unprocessable(
                        "TRACKING_MULTI_ITEM_SHIPMENT_MISMATCH",
                        "同一 Shipment 的多履约明细必须完整发货并使用同一运单");
            }
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
        int quantity;
        try {
            quantity = CountQuantity.fromPositiveFileValue(cells.get("实际发货数量"));
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException exception) {
            throw BusinessException.unprocessable("TRACKING_QUANTITY_INVALID", "实际发货数量非法");
        }
        if (quantity > line.instructedQuantity()) {
            throw BusinessException.unprocessable("TRACKING_QUANTITY_INVALID", "实际数量必须为正整数且不超过指令数");
        }
        if (("SHIPPED".equals(result) && quantity != line.instructedQuantity())
                || ("PARTIAL".equals(result) && quantity >= line.instructedQuantity())) {
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

    private Long generateSourceReturn(long sourceBatchId, Long trackingBatchId, String operator) {
        jdbc.queryForObject(
                "SELECT id FROM app.import_batches WHERE id=? AND batch_type='SOURCE_ORDER' FOR UPDATE",
                Long.class,
                sourceBatchId);
        List<Long> existingFinal = jdbc.queryForList(
                """
                SELECT sre.id FROM app.source_return_exports sre
                WHERE sre.import_batch_id=? AND sre.is_final
                  AND NOT EXISTS (
                      SELECT 1 FROM app.source_return_export_invalidations invalidation
                      WHERE invalidation.source_return_export_id=sre.id)
                ORDER BY sre.id
                """,
                Long.class,
                sourceBatchId);
        if (!existingFinal.isEmpty()) {
            return existingFinal.getFirst();
        }
        SourceBatch source = sourceBatch(sourceBatchId);
        if (source.fileRef() != null && source.fileRef().startsWith("structured://")) {
            // 结构化（在线 JSON 拉取）批次没有原始工作簿可回写：彩食鲜/聚福宝等结构化渠道的
            // 发货回传走各自 Connector 的在线 source-sync 通道（彩食鲜由
            // CaishixianShipmentArtifactFactory 结构化分支重建回填工作簿）。不加此闸门时
            // fileStore.read("structured://…") 会在京东回填收口路径里抛异常，波及发货主流程。
            return null;
        }
        if (holdMultiPartitionSourceReturns(sourceBatchId)) {
            return null;
        }
        if ("WANQI".equals(source.channel())) {
            return null;
        }
        List<ReturnRow> returns = returnRows(sourceBatchId);
        int acceptedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status='ACCEPTED'",
                Integer.class,
                sourceBatchId);
        boolean hasUnfinishedPartition = Boolean.TRUE.equals(jdbc.queryForObject(
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
                SELECT EXISTS (
                    SELECT 1
                    FROM raw_line_links rll
                    JOIN app.raw_import_rows rir ON rir.id=rll.raw_row_id AND rir.status='ACCEPTED'
                    LEFT JOIN app.fulfillments f ON f.order_line_id=rll.order_line_id
                    WHERE f.id IS NULL
                       OR f.outcome NOT IN ('FULLY_FULFILLED', 'PARTIALLY_FULFILLED', 'CANCELLED')
                       OR EXISTS (
                           SELECT 1 FROM app.review_cases rc
                           WHERE rc.order_line_id=rll.order_line_id AND rc.status='OPEN'
                       )
                )
                """,
                Boolean.class,
                sourceBatchId,
                sourceBatchId));
        boolean hasMultiShipmentFollowup = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.review_cases rc
                    JOIN app.raw_import_rows rir ON rir.order_line_id=rc.order_line_id
                    WHERE rir.import_batch_id=?
                      AND rc.reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP'
                      AND rc.status='OPEN')
                """,
                Boolean.class,
                sourceBatchId));
        // 批次里还有「未定论」的行时不出回填文件。
        //
        // 部分确认（跳过阻断行先发能发的）之后，acceptedRows 只数已接收行，就绪的那几行发完
        // 就会满足 returns.size()==acceptedRows——此时若照常生成，出去的就是一份只含首批的
        // is_final 文件。excel-closed-loop-spec.md 的后续回传条款写得很直白：「不得生成只含
        // 首批的 is_final=true 文件」。而且数据库层面这份文件一旦推送成功就再也不能失效
        // （V41 触发器 validate_source_return_invalidation 明令禁止），等于把半份结果钉死。
        //
        // 所以待定行（NEED_REVIEW 待复核 / RECEIVED 处理中）留着就先不出文件：等它们被修好
        // 转 ACCEPTED（补做确认后一并发货）或被判定 REJECTED（明确不发了），批次定论后再一次
        // 出齐。REJECTED 不在此列——那是已经做出的决定，对应行在原表里留空正是正确的回填结果。
        // 良性行不算「待定」——它们永远不会被修好，扣着回传就是永久扣着。
        // SOURCE_ORDER_ALREADY_FULFILLED（来源侧已发货）落的是 NEED_REVIEW 而非 REJECTED
        // （SourceFileParser#withError → SourceImportService 的 valid()?RECEIVED:NEED_REVIEW），
        // 且 2026-08-28 生产实证：这类行既不生成 review_case、也没有任何行级处置端点，
        // 没有任何机制能让它离开 NEED_REVIEW。若把它算作待定，飞象/中汇只要导过一次
        // 「全部订单」（必然混进历史已发单），该批次的回传文件就永久不出——运单号再也回不到
        // 来源平台，且全程无错误、无日志，静默失败。
        // 判据与 SourceImportService#confirm、SourceBatchConfirmReadiness 的良性口径同源：
        // 从不建单（order_line_id 恒为 NULL）即无事可做。
        boolean hasUndecidedRows = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.raw_import_rows
                    WHERE import_batch_id=? AND status IN ('NEED_REVIEW', 'RECEIVED')
                      AND NOT (order_line_id IS NULL
                               AND error_code IN ('ORDER_ALREADY_EXISTS',
                                                  'SOURCE_ORDER_ALREADY_FULFILLED')))
                """,
                Boolean.class,
                sourceBatchId));
        if (hasUnfinishedPartition
                || hasMultiShipmentFollowup
                || hasUndecidedRows
                || acceptedRows == 0
                || returns.size() != acceptedRows) {
            return null;
        }
        ParsedSourceFile original = sourceFileParser.parse(fileStore.read(source.fileRef()));
        Map<String, ReturnRow> byCoordinate = returns.stream().collect(java.util.stream.Collectors.toMap(
                row -> row.sheetIndex() + ":" + row.rowIndex(), row -> row));
        // 每行真正被回填改动的列。工作簿回写只碰这些列——把整行重写一遍会把
        // 原表里的公式（合计=D2*H2、SUM 合计行）覆盖成解析时读到的公式**原文文字**，
        // 生产实证 2026-08-26：还回去的表里合计列全变成了红色的 "D2*H2" 字样。
        Map<String, java.util.Set<String>> changedByCoordinate = new java.util.HashMap<>();
        List<ParsedSourceRow> rendered = original.rows().stream().map(row -> {
            ReturnRow fill = byCoordinate.get(row.sheetIndex() + ":" + row.rowIndex());
            if (fill == null) {
                return row;
            }
            Map<String, String> cells = new LinkedHashMap<>(row.rawCells());
            normalizeSourceCount(source.channel(), row, cells);
            switch (source.channel()) {
                case "CAISHIXIAN" -> {
                    cells.put("发货数量", Integer.toString(sourceQuantityCell(fill)));
                    cells.put("物流公司代码", fill.sourceCarrier());
                    cells.put("物流单号", fill.trackingNo());
                    cells.put("错误原因", "");
                }
                case "JUFUBAO" -> {
                    cells.put("是否发完", "是");
                    cells.put("发货数量", Integer.toString(sourceQuantityCell(fill)));
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
                case "ZHONGHUI" -> {
                    // 中汇回填：发货状态/物流单号必写；原表带「物流公司」列时一并回填——
                    // 文案取渠道词表 carrier_mappings（中汇 = 京东快递，2026-08-27 用户裁决），
                    // 不硬编码。原表没有该列时不新增（平台模板说了算）。
                    if (cells.containsKey("发货状态")) {
                        cells.put("发货状态", "已发货");
                    }
                    if (cells.containsKey("物流公司")) {
                        cells.put("物流公司", fill.sourceCarrier());
                    }
                    cells.put("物流单号", fill.trackingNo());
                }
                case "WANGQI", "DAZHE" -> {
                    if (cells.containsKey("物流单号") && cells.containsKey("物流公司")) {
                        // 大者 v2（11 列往返表）：物流公司/物流单号 两列本来就是留给我们填的。
                        // 写 v1 的列名（快递单号/快递公司/订单商品状态）会因表头不存在而被
                        // 追加成新列——生产实证 2026-08-26：运单号进了追加列，原两列还是空的。
                        cells.put("物流公司", fill.sourceCarrier());
                        cells.put("物流单号", fill.trackingNo());
                    } else {
                        cells.put("订单商品状态", "已发货");
                        cells.put("快递单号", fill.trackingNo());
                        cells.put("快递公司", fill.sourceCarrier());
                    }
                }
                default -> throw new IllegalStateException("unsupported source return channel");
            }
            java.util.Set<String> changed = cells.entrySet().stream()
                    .filter(cell -> !java.util.Objects.equals(cell.getValue(), row.rawCells().get(cell.getKey())))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            sourceCountColumns(source.channel()).stream()
                    .filter(cells::containsKey)
                    .forEach(changed::add);
            changedByCoordinate.put(row.sheetIndex() + ":" + row.rowIndex(), changed);
            return copyWithCells(row, cells);
        }).toList();
        byte[] file = "FEIXIANG".equals(source.channel())
                ? trueCsv(source, rendered)
                : sourceWorkbook(source, rendered, changedByCoordinate);
        // 扩展名由产物格式的单一真源决定，下载与企微投递读同一份判定。
        String suffix = SourceReturnArtifactFormat.of(source.channel(), source.templateVersion()).extension();
        ContentAddressedFileStore.StoredFile stored = fileStore.put("source-return-exports", file, suffix);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0)+1 FROM app.source_return_exports WHERE import_batch_id=?",
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
                true,
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

    /**
     * 一个来源行对应多个履约分片时，原模板单行无法无损表达多 Shipment/Tracking。
     * 创建人工后续事项并失败关闭，绝不拿 legacy 第一分片生成 final 回填。
     */
    private boolean holdMultiPartitionSourceReturns(long sourceBatchId) {
        List<MultiPartitionSourceRow> rows = jdbc.query(
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
                SELECT rir.id raw_row_id, rir.order_id, count(DISTINCT rll.order_line_id) partition_count
                FROM app.raw_import_rows rir
                JOIN raw_line_links rll ON rll.raw_row_id=rir.id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                GROUP BY rir.id, rir.order_id
                HAVING count(DISTINCT rll.order_line_id)>1
                ORDER BY rir.id
                """,
                (resultSet, rowNum) -> new MultiPartitionSourceRow(
                        resultSet.getLong("raw_row_id"),
                        resultSet.getLong("order_id"),
                        resultSet.getInt("partition_count")),
                sourceBatchId,
                sourceBatchId,
                sourceBatchId);
        for (MultiPartitionSourceRow row : rows) {
            jdbc.update(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, status, responsible_team, reason_code,
                         order_id, import_batch_id, raw_import_row_id, detail)
                    VALUES (?, 'SOURCE_FOLLOWUP', 'OPEN', 'FULFILLMENT_OPS',
                            'MULTI_SHIPMENT_SOURCE_FOLLOWUP', ?, ?, ?, jsonb_build_object(
                                'message', '来源礼包行包含多个履约分片，原模板单行无法自动表达多运单',
                                'partition_count', ?))
                    ON CONFLICT (case_no) DO NOTHING
                    """,
                    "RC-MIXED-SOURCE-" + row.rawRowId(),
                    row.orderId(),
                    sourceBatchId,
                    row.rawRowId(),
                    row.partitionCount());
        }
        return !rows.isEmpty();
    }

    /** 京东运单回填后，尝试为 Shipment 所属来源批次生成唯一最终原格式文件。 */
    @Transactional
    public List<Long> finalizeReadySourceReturnsForShipment(long shipmentId, String operator) {
        return finalizeReadySourceReturnsForShipment(shipmentId, null, operator);
    }

    /** 派生任务入口；与 Tracking 事实使用不同事务。 */
    @Transactional
    public List<Long> finalizeReadySourceReturnsForShipment(
            long shipmentId, Long trackingBatchId, String operator) {
        List<Long> sourceBatchIds = sourceBatchIdsForShipment(shipmentId);
        List<Long> result = new ArrayList<>();
        for (long sourceBatchId : sourceBatchIds) {
            Long returnId = generateSourceReturn(sourceBatchId, trackingBatchId, operator);
            if (returnId != null) result.add(returnId);
        }
        return List.copyOf(result);
    }

    public List<String> sourceReturnIdsForShipment(long shipmentId) {
        List<String> result = new ArrayList<>();
        for (long sourceBatchId : sourceBatchIdsForShipment(shipmentId)) {
            result.addAll(jdbc.query(
                    """
                    SELECT sre.id
                    FROM app.source_return_exports sre
                    WHERE sre.import_batch_id=? AND sre.is_final
                      AND NOT EXISTS (
                          SELECT 1 FROM app.source_return_export_invalidations invalidation
                          WHERE invalidation.source_return_export_id=sre.id)
                    ORDER BY sre.id
                    """,
                    (resultSet, rowNum) -> resultSet.getString(1),
                    sourceBatchId));
        }
        return List.copyOf(result);
    }

    List<String> generatedSourceReturnIdsForTrackingBatch(long trackingBatchId) {
        return generatedSourceReturnIds(trackingBatchId);
    }

    private List<Long> sourceBatchIdsForShipment(long shipmentId) {
        // jd-real-sdk-switch 06：SDK 直连路由（05）的 shipment 没有 fulfillment_export_items，
        // 通过 shipment_items → fulfillments → raw_import_rows 反查来源批次；文件路由保持原路径。
        return jdbc.query(
                """
                SELECT DISTINCT import_batch_id FROM (
                    SELECT rir.import_batch_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                    JOIN app.order_lines ol ON ol.id=rirol.order_line_id
                    JOIN app.fulfillments f ON f.order_line_id=ol.id
                    JOIN app.shipment_items si ON si.fulfillment_id=f.id
                    WHERE si.shipment_id=?
                    UNION
                    SELECT rir.import_batch_id
                    FROM app.fulfillment_export_items fei
                    JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                    WHERE fei.shipment_id=?
                ) batches
                ORDER BY import_batch_id
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                shipmentId,
                shipmentId);
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

    private byte[] sourceWorkbook(
            SourceBatch source, List<ParsedSourceRow> rows, Map<String, java.util.Set<String>> changedByCoordinate) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fileStore.read(source.fileRef())));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // 只写回填真正改动的列；原表其余单元格（含公式、汇总行、格式）一律不碰。
            // 渠道回填需要新增的列（如中汇「物流单号」）不在原表头中：
            // 按首个出现顺序在表头末尾追加列，同一来源行的数据写入对应新列。
            Map<Integer, Map<String, Integer>> appendedBySheet = new java.util.HashMap<>();
            for (ParsedSourceRow parsed : rows) {
                java.util.Set<String> changed = changedByCoordinate.get(parsed.sheetIndex() + ":" + parsed.rowIndex());
                if (changed == null || changed.isEmpty()) {
                    continue;
                }
                var sheet = workbook.getSheetAt(parsed.sheetIndex());
                var header = sheet.getRow(0);
                var data = sheet.getRow(parsed.rowIndex() - 1);
                Map<String, Integer> columnsByName = new LinkedHashMap<>();
                for (int column = 0; column < header.getLastCellNum(); column++) {
                    columnsByName.putIfAbsent(
                            sourceFileParser.normalizeHeader(formatter.formatCellValue(header.getCell(column))), column);
                }
                Map<String, Integer> appended = appendedBySheet.computeIfAbsent(
                        parsed.sheetIndex(), ignored -> new LinkedHashMap<>());
                for (String name : changed) {
                    Integer column = columnsByName.get(name);
                    if (column == null) {
                        column = appended.computeIfAbsent(
                                name, ignored -> Integer.valueOf(header.getLastCellNum()));
                        if (header.getCell(column) == null) header.createCell(column);
                        header.getCell(column).setCellValue(name);
                    }
                    if (data.getCell(column) == null) data.createCell(column);
                    if (isSourceCountColumn(source.channel(), name)) {
                        data.getCell(column).setCellValue(CountQuantity.fromPositiveFileValue(
                                parsed.rawCells().get(name)));
                    } else {
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
                SELECT sre.id, sre.import_batch_id, sre.version_no, sre.is_final, sre.template_version,
                       sre.tracking_cutoff_at, sre.file_sha256, sre.generated_at, sre.push_status,
                       invalidation.reason_code invalidation_reason
                FROM app.source_return_exports sre
                LEFT JOIN app.source_return_export_invalidations invalidation
                  ON invalidation.source_return_export_id=sre.id
                WHERE sre.import_batch_id=? ORDER BY sre.version_no
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
                    String invalidationReason = resultSet.getString("invalidation_reason");
                    result.put("valid", invalidationReason == null);
                    result.put("invalidation_reason", invalidationReason);
                    result.put("push_status", resultSet.getString("push_status"));
                    return result;
                }, sourceBatchId);
    }

    ProviderFileService.FileDownload downloadSourceReturn(long returnId, CommandContext context) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT sre.file_ref, sre.template_version, source.effective_source_channel,
                       sre.version_no, ib.original_file_name,
                       invalidation.id invalidation_id
                FROM app.source_return_exports sre
                JOIN app.import_batches ib ON ib.id=sre.import_batch_id
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=sre.import_batch_id
                LEFT JOIN app.source_return_export_invalidations invalidation
                  ON invalidation.source_return_export_id=sre.id
                WHERE sre.id=?
                """, returnId);
        if (rows.isEmpty()) throw BusinessException.notFound("来源回填文件不存在");
        if (rows.getFirst().get("invalidation_id") != null) {
            throw BusinessException.conflict("SOURCE_RETURN_INVALIDATED", "该来源回填文件已因来源归因纠正而失效");
        }
        String channel = rows.getFirst().get("effective_source_channel").toString();
        String templateVersion = rows.getFirst().get("template_version").toString();
        SourceReturnArtifactFormat format = SourceReturnArtifactFormat.of(channel, templateVersion);
        String suffix = format.extension();
        String contentType = format.contentType();
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).requestId(context.requestId()).traceId(context.traceId())
                .operator(context.operator()).actorType(AuditActorType.HUMAN)
                .service("source-return-export").operation("file.download")
                .requestPayload(Map.of("export_id", returnId)).httpStatus(200).businessCode("FILE_DOWNLOADED"));
        // 回填文件还给来源平台，平台可能按文件名识别归档：以原始文件名为基名，只追加后缀。
        // 扩展名以实际产物为准（飞象是 csv，其余 xlsx），原名扩展名不符时不跟随原名。
        Object originalFileName = rows.getFirst().get("original_file_name");
        return new ProviderFileService.FileDownload(
                SourceReturnFileNaming.fileName(
                        originalFileName == null ? null : originalFileName.toString(),
                        SourceChannelDisplayNames.displayName(channel),
                        ((Number) rows.getFirst().get("version_no")).intValue(),
                        suffix),
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
        row.put("settlement_missing", false);
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
                       fei.order_line_component_id,
                       fei.outbound_order_no, fei.instructed_quantity, fei.output_cells::text output_cells,
                       COALESCE(olc.quantity_per_bundle, 1) component_quantity_per_bundle,
                       s.order_id
                FROM app.fulfillment_export_items fei
                JOIN app.shipments s ON s.id=fei.shipment_id
                LEFT JOIN app.order_line_components olc ON olc.id=fei.order_line_component_id
                WHERE fei.fulfillment_export_id=? ORDER BY fei.export_line_no
                """,
                (resultSet, rowNum) -> new ExpectedExportLine(
                        resultSet.getInt("export_line_no"), resultSet.getLong("shipment_id"),
                        resultSet.getLong("fulfillment_id"), resultSet.getLong("order_line_id"),
                        (Long) resultSet.getObject("order_line_component_id"),
                        resultSet.getLong("order_id"), resultSet.getString("outbound_order_no"),
                        resultSet.getInt("instructed_quantity"),
                        (Integer) resultSet.getObject("component_quantity_per_bundle"),
                        jsonMap(resultSet.getString("output_cells"))),
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
                SELECT source.effective_source_channel, ib.template_version, ib.file_ref
                FROM app.import_batches ib
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                WHERE ib.id=?
                """,
                (resultSet, rowNum) -> new SourceBatch(
                        resultSet.getString("effective_source_channel"), resultSet.getString("template_version"),
                        resultSet.getString("file_ref")), sourceBatchId);
    }

    Long regenerateSourceReturnAfterAttributionCorrection(long sourceBatchId, String operator) {
        List<Long> trackingBatchIds = jdbc.queryForList(
                """
                SELECT generated_from_tracking_batch_id
                FROM app.source_return_exports
                WHERE import_batch_id=? AND generated_from_tracking_batch_id IS NOT NULL
                ORDER BY version_no DESC LIMIT 1
                """,
                Long.class,
                sourceBatchId);
        return generateSourceReturn(
                sourceBatchId,
                trackingBatchIds.isEmpty() ? null : trackingBatchIds.getFirst(),
                operator);
    }

    private List<ReturnRow> returnRows(long sourceBatchId) {
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
                SELECT rir.id raw_row_id, rir.sheet_index, rir.row_index, rll.order_line_id,
                       s.id shipment_id, s.shipment_sequence, si.shipped_quantity,
                       ol.mapping_multiplier_snapshot,
                       t.tracking_number, f.outcome, f.cancelled_quantity,
                       COALESCE(
                           NULLIF(btrim((cc.config->'carrier_mappings')->>t.logistics_company_code), ''),
                           NULLIF(btrim(t.logistics_company_name), ''),
                           t.logistics_company_code) source_carrier
                FROM app.raw_import_rows rir
                JOIN raw_line_links rll ON rll.raw_row_id=rir.id
                JOIN app.order_lines ol ON ol.id=rll.order_line_id
                JOIN app.fulfillments f ON f.order_line_id=rll.order_line_id
                JOIN app.shipment_items si ON si.fulfillment_id=f.id AND si.shipped_quantity>0
                JOIN app.shipments s ON s.id=si.shipment_id
                JOIN app.trackings t ON t.shipment_id=s.id
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=rir.import_batch_id
                JOIN app.connector_configs cc ON cc.source_channel=source.effective_source_channel
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                  AND s.shipment_sequence=(SELECT MIN(s2.shipment_sequence) FROM app.shipments s2
                                           JOIN app.shipment_items si2 ON si2.shipment_id=s2.id
                                           WHERE si2.fulfillment_id=f.id AND si2.shipped_quantity>0)
                ORDER BY rir.sheet_index, rir.row_index
                """,
                (resultSet, rowNum) -> {
                    String carrier = resultSet.getString("source_carrier");
                    return new ReturnRow(
                            resultSet.getLong("raw_row_id"), resultSet.getInt("sheet_index"),
                            resultSet.getInt("row_index"), resultSet.getLong("order_line_id"),
                            resultSet.getLong("shipment_id"), resultSet.getInt("shipment_sequence"),
                            resultSet.getInt("shipped_quantity"),
                            resultSet.getObject("mapping_multiplier_snapshot", Integer.class), carrier,
                            resultSet.getString("tracking_number"), resultSet.getString("outcome"),
                            resultSet.getObject("cancelled_quantity", Integer.class));
                }, sourceBatchId, sourceBatchId, sourceBatchId);
    }

    private ParsedSourceRow copyWithCells(ParsedSourceRow row, Map<String, String> cells) {
        return new ParsedSourceRow(
                row.sheetName(), row.sheetIndex(), row.rowIndex(), cells, row.sourceOrderRef(), row.sourceLineRef(),
                row.sourceCustomerRef(), row.customerName(), row.receiverName(), row.receiverPhone(), row.receiverAddress(),
                row.receiverProvince(), row.receiverCity(), row.receiverDistrict(), row.sourceSkuRef(), row.productName(),
                row.specification(), row.unit(), row.quantity(), row.orderedAt(), row.settlementMethod(), row.remark(),
                row.errorCode(), row.errorMessage());
    }

    private CarrierPrefixMatcher.Carrier internalCarrier(String providerCarrier) {
        return carrierMatcher.resolveStated(providerCarrier).orElseThrow(() ->
                BusinessException.unprocessable("CARRIER_MAPPING", "未配置内部快递映射: " + providerCarrier));
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
                SELECT COUNT(DISTINCT order_line_id) FILTER (WHERE raw_cells->>'结果'='SHIPPED') shipped,
                       COUNT(DISTINCT order_line_id) FILTER (WHERE raw_cells->>'结果'='PARTIAL') partial,
                       COUNT(DISTINCT order_line_id) FILTER (WHERE raw_cells->>'结果'='FAILED') failed
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
            int lineNo, long shipmentId, long fulfillmentId, long orderLineId, Long orderLineComponentId,
            long orderId, String outboundOrderNo, int instructedQuantity,
            Integer componentQuantityPerBundle, Map<String, Object> outputCells) {}
    private record TrackingRow(
            int rowIndex, Map<String, String> cells, String result, ExpectedExportLine line,
            Integer shippedQuantity, String carrier, String trackingNo, Instant shippedAt, String failureReason) {
        long shipmentId() { return line.shipmentId(); }
        long fulfillmentId() { return line.fulfillmentId(); }
        long orderLineId() { return line.orderLineId(); }
        long orderId() { return line.orderId(); }
        String outboundOrderNo() { return line.outboundOrderNo(); }
    }
    record ParsedTrackingFile(long exportId, String exportBatchNo, List<ParsedTrackingRow> rows) {
        ParsedTrackingFile {
            rows = List.copyOf(rows);
        }
    }
    record TrackingUploadResult(Map<String, Object> body, List<Long> sourceReturnTaskIds) {
        TrackingUploadResult {
            sourceReturnTaskIds = List.copyOf(sourceReturnTaskIds);
        }
    }
    record ParsedTrackingRow(
            int rowIndex,
            long shipmentId,
            long fulfillmentId,
            long orderLineId,
            long orderId,
            String receiverName,
            String result,
            Integer shippedQuantity,
            String carrierCode,
            String carrierName,
            String trackingNo,
            String failureReason,
            int instructedQuantity,
            Map<String, String> cells) {
        ParsedTrackingRow {
            cells = Map.copyOf(cells);
        }
    }
    private record SourceBatch(String channel, String templateVersion, String fileRef) {}
    private record MultiPartitionSourceRow(long rawRowId, long orderId, int partitionCount) {}
    /**
     * 来源平台数量列的取值：内部数量按建单时冻结的渠道乘数还原为来源份数，且必须是整数。
     *
     * <p>规格换算（{@code source_channel_skus.quantity_multiplier}）只用于把来源份数折成京东计数件，
     * 属于京东侧事实——来源平台按自己的销售份数记账，看到的数量必须与其下单口径一致。
     * 仅在真正写该列的渠道（彩食鲜/聚福宝）换算；不写该列的渠道不受影响。
     *
     * <p>除不尽意味着实发件数不足整份来源销售单位，来源表格无法表达该状态。
     * 此时失败关闭：向合作平台少报或多报发货量都是实质错误，不做四舍五入。
     */
    private static int sourceQuantityCell(ReturnRow row) {
        int internal = row.shippedQuantity();
        int factor = row.mappingMultiplier() == null || row.mappingMultiplier() <= 0
                ? 1
                : row.mappingMultiplier();
        if (internal <= 0 || internal % factor != 0) {
            throw BusinessException.unprocessable(
                    "SOURCE_RETURN_QUANTITY_NOT_SOURCE_UNIT",
                    "实发 " + internal + " 件无法按渠道乘数 " + factor
                            + " 还原为来源整数份数，来源回传表格无法表达该部分发货状态");
        }
        return internal / factor;
    }

    private static void normalizeSourceCount(
            String channel, ParsedSourceRow row, Map<String, String> cells) {
        for (String column : sourceCountColumns(channel)) {
            if (cells.containsKey(column) && !"发货数量".equals(column)) {
                cells.put(column, row.quantity().toString());
                return;
            }
        }
    }

    private static boolean isSourceCountColumn(String channel, String column) {
        return sourceCountColumns(channel).contains(column);
    }

    private static List<String> sourceCountColumns(String channel) {
        return switch (channel) {
            case "CAISHIXIAN" -> List.of("下单数量", "发货数量");
            case "JUFUBAO" -> List.of("数量", "发货数量");
            case "FEIXIANG" -> List.of("可发货数量", "商品数量");
            case "ZHONGHUI" -> List.of("件数");
            case "WANGQI", "DAZHE" -> List.of("数量", "商品数量");
            case "WANQI" -> List.of("购买数量");
            default -> List.of();
        };
    }

    private record ReturnRow(
            long rawRowId, int sheetIndex, int rowIndex, long orderLineId, long shipmentId, int shipmentSequence,
            int shippedQuantity, Integer mappingMultiplier, String sourceCarrier, String trackingNo,
            String fulfillmentOutcome, Integer cancelledQuantity) {}
}
