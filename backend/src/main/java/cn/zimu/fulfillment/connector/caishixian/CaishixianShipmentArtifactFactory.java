package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.sync.SourceShipmentArtifactFactory;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.file.ContentAddressedFileStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 从原始彩食鲜工作簿与 raw-row lineage 构造单 Shipment 上传产物。 */
@Component
public class CaishixianShipmentArtifactFactory implements SourceShipmentArtifactFactory {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_UPLOAD_BYTES = 1024 * 1024;
    private static final Instant FIXED_CORE_TIMESTAMP = Instant.parse("2000-01-01T00:00:00Z");
    private static final LocalDateTime FIXED_ZIP_ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final List<String> REQUIRED_HEADERS = List.of(
            "主订单编号", "子订单编号", "采购单号", "供应商编码", "站点编码", "收货人",
            "联系电话", "省", "市", "区", "详细地址", "物流要求编码", "物流要求名称",
            "商品编号", "商品名称", "下单数量", "订单备注", "发货数量", "物流公司代码",
            "物流单号", "vip订单标识", "错误原因");

    private final JdbcTemplate jdbc;
    private final ContentAddressedFileStore files;

    public CaishixianShipmentArtifactFactory(JdbcTemplate jdbc, ContentAddressedFileStore files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.CAISHIXIAN;
    }

    @Override
    public SourceShipmentArtifact prepare(SourceSyncFacts facts) {
        if (facts.sourceChannel() != SourceChannel.CAISHIXIAN) {
            throw new IllegalArgumentException("彩食鲜上传产物工厂收到错误渠道");
        }
        List<ArtifactRow> rows = jdbc.query(
                """
                WITH raw_line_links AS (
                    SELECT rir.id AS raw_row_id, rir.order_line_id
                    FROM app.raw_import_rows rir
                    WHERE rir.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                )
                SELECT DISTINCT ib.file_ref, rir.sheet_index, rir.row_index,
                       si.shipped_quantity, ol.mapping_multiplier_snapshot
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id
                JOIN app.import_batches ib ON ib.id=o.source_import_batch_id
                JOIN app.shipment_items si ON si.shipment_id=s.id
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN raw_line_links rll ON rll.order_line_id=ol.id
                JOIN app.raw_import_rows rir
                  ON rir.id=rll.raw_row_id AND rir.import_batch_id=ib.id AND rir.status='ACCEPTED'
                WHERE s.id=? AND rir.raw_cells->>'source_line_ref'=?
                ORDER BY rir.sheet_index, rir.row_index
                """,
                (rs, rowNum) -> new ArtifactRow(
                        rs.getString("file_ref"),
                        rs.getInt("sheet_index"),
                        rs.getInt("row_index"),
                        rs.getBigDecimal("shipped_quantity"),
                        rs.getBigDecimal("mapping_multiplier_snapshot")),
                facts.shipmentId(),
                facts.sourceLineRef());
        if (rows.isEmpty()) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_SHIPMENT_ARTIFACT_UNAVAILABLE",
                    "未找到该 Shipment 的彩食鲜原始工作簿行");
        }
        String fileRef = rows.getFirst().fileRef();
        if (rows.stream().anyMatch(row -> !fileRef.equals(row.fileRef()))) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_SHIPMENT_ARTIFACT_AMBIGUOUS",
                    "同一 Shipment 的彩食鲜来源行不属于同一原始工作簿");
        }
        List<RowFill> fills = rows.stream()
                .map(row -> new RowFill(
                        row.sheetIndex(),
                        row.rowIndex(),
                        sourceQuantity(row.shippedQuantity(), row.multiplier()),
                        facts.carrierOutputValue(),
                        facts.trackingNumber()))
                .toList();
        byte[] rendered = render(files.read(fileRef), fills);
        if (rendered.length > MAX_UPLOAD_BYTES) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_SHIPMENT_ARTIFACT_TOO_LARGE",
                    "单 Shipment 彩食鲜回填文件超过 1 MiB，禁止在线上传");
        }
        return new SourceShipmentArtifact(
                "caishixian-shipment-" + facts.shipmentId() + ".xlsx",
                CONTENT_TYPE,
                rendered,
                sha256(rendered));
    }

    static byte[] render(byte[] original, List<RowFill> fills) {
        if (fills == null || fills.isEmpty()) {
            throw new IllegalArgumentException("彩食鲜 Shipment 回填行不能为空");
        }
        try (var source = WorkbookFactory.create(new ByteArrayInputStream(original));
                var outputWorkbook = new XSSFWorkbook();
                var output = new ByteArrayOutputStream()) {
            requirePassiveXlsx(source);
            Map<Integer, CellStyle> copiedStyles = new HashMap<>();
            Map<Integer, List<RowFill>> bySheet = new LinkedHashMap<>();
            fills.stream()
                    .sorted(Comparator.comparingInt(RowFill::sheetIndex).thenComparingInt(RowFill::rowIndex))
                    .forEach(fill -> bySheet.computeIfAbsent(fill.sheetIndex(), ignored -> new ArrayList<>()).add(fill));
            for (Map.Entry<Integer, List<RowFill>> entry : bySheet.entrySet()) {
                if (entry.getKey() < 0 || entry.getKey() >= source.getNumberOfSheets()) {
                    throw new IllegalArgumentException("彩食鲜来源 sheet_index 越界");
                }
                var sourceSheet = source.getSheetAt(entry.getKey());
                var sourceHeader = sourceSheet.getRow(0);
                if (sourceHeader == null) {
                    throw new IllegalArgumentException("彩食鲜来源工作簿缺少表头");
                }
                Map<String, Integer> columns = columns(sourceHeader);
                var targetSheet = outputWorkbook.createSheet(sourceSheet.getSheetName());
                copyRow(sourceHeader, targetSheet.createRow(0), outputWorkbook, copiedStyles);
                for (int column = 0; column < sourceHeader.getLastCellNum(); column++) {
                    targetSheet.setColumnWidth(column, sourceSheet.getColumnWidth(column));
                }
                int targetRowIndex = 1;
                for (RowFill fill : entry.getValue()) {
                    Row sourceRow = sourceSheet.getRow(fill.rowIndex() - 1);
                    if (sourceRow == null) {
                        throw new IllegalArgumentException("彩食鲜来源 row_index 不存在");
                    }
                    Row targetRow = targetSheet.createRow(targetRowIndex++);
                    copyRow(sourceRow, targetRow, outputWorkbook, copiedStyles);
                    set(targetRow, columns.get("发货数量"), fill.shippedSourceQuantity());
                    set(targetRow, columns.get("物流公司代码"), fill.carrierCode());
                    set(targetRow, columns.get("物流单号"), fill.trackingNumber());
                    set(targetRow, columns.get("错误原因"), "");
                }
            }
            var coreProperties = outputWorkbook.getProperties().getCoreProperties();
            coreProperties.setCreated(Optional.of(Date.from(FIXED_CORE_TIMESTAMP)));
            coreProperties.setModified(Optional.of(Date.from(FIXED_CORE_TIMESTAMP)));
            outputWorkbook.write(output);
            return normalizeOoxmlZip(output.toByteArray());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("彩食鲜单 Shipment 回填工作簿生成失败", exception);
        }
    }

    static byte[] normalizeOoxmlZip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entries.putIfAbsent(entry.getName(), input.readAllBytes()) != null) {
                    throw new IllegalArgumentException("OOXML ZIP 包含重复 entry: " + entry.getName());
                }
            }
        }
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            zip.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                var normalized = new ZipEntry(entry.getKey());
                normalized.setTimeLocal(FIXED_ZIP_ENTRY_TIME);
                zip.putNextEntry(normalized);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }

    private static Map<String, Integer> columns(Row header) {
        List<String> normalizedHeaders = new ArrayList<>();
        for (int index = 0; index < header.getLastCellNum(); index++) {
            Cell cell = header.getCell(index);
            normalizedHeaders.add(normalize(cell == null ? "" : cell.toString()));
        }
        if (!normalizedHeaders.equals(REQUIRED_HEADERS)) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_TEMPLATE_MISMATCH",
                    "彩食鲜来源工作簿不是已捕获的精确 22 列发货模板");
        }
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < normalizedHeaders.size(); index++) {
            result.put(normalizedHeaders.get(index), index);
        }
        return result;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static void copyRow(
            Row source,
            Row target,
            Workbook targetWorkbook,
            Map<Integer, CellStyle> copiedStyles) {
        target.setHeight(source.getHeight());
        for (int index = 0; index < REQUIRED_HEADERS.size(); index++) {
            Cell sourceCell = source.getCell(index);
            if (sourceCell == null) {
                continue;
            }
            Cell targetCell = target.createCell(index);
            copyCell(sourceCell, targetCell, targetWorkbook, copiedStyles);
        }
    }

    private static void copyCell(
            Cell source,
            Cell target,
            Workbook targetWorkbook,
            Map<Integer, CellStyle> copiedStyles) {
        CellStyle sourceStyle = source.getCellStyle();
        if (sourceStyle != null) {
            CellStyle targetStyle = copiedStyles.computeIfAbsent(
                    (int) sourceStyle.getIndex(),
                    ignored -> {
                        CellStyle style = targetWorkbook.createCellStyle();
                        style.cloneStyleFrom(sourceStyle);
                        return style;
                    });
            target.setCellStyle(targetStyle);
        }
        CellType type = source.getCellType();
        switch (type) {
            case STRING -> target.setCellValue(source.getStringCellValue());
            case NUMERIC -> target.setCellValue(source.getNumericCellValue());
            case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
            case FORMULA -> throw BusinessException.unprocessable(
                    "CAISHIXIAN_TEMPLATE_UNSAFE",
                    "彩食鲜来源工作簿包含公式，禁止复制到在线上传产物");
            case ERROR -> target.setCellErrorValue(source.getErrorCellValue());
            case BLANK, _NONE -> target.setBlank();
        }
    }

    private static void set(Row row, int column, String value) {
        Cell cell = row.getCell(column);
        if (cell == null) {
            cell = row.createCell(column);
        }
        cell.setCellValue(value == null ? "" : value);
    }

    private static void requirePassiveXlsx(Workbook workbook) {
        if (!(workbook instanceof XSSFWorkbook xssf)) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_TEMPLATE_UNSAFE",
                    "彩食鲜在线上传只接受无宏 xlsx 工作簿");
        }
        if (xssf.isMacroEnabled() || !xssf.getExternalLinksTable().isEmpty()) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_TEMPLATE_UNSAFE",
                    "彩食鲜来源工作簿包含宏或外部链接，禁止在线上传");
        }
    }

    private static String sourceQuantity(BigDecimal shipped, BigDecimal multiplier) {
        if (shipped == null || shipped.signum() <= 0 || multiplier == null || multiplier.signum() <= 0) {
            throw BusinessException.unprocessable(
                    "SOURCE_RETURN_QUANTITY_NOT_SOURCE_UNIT",
                    "彩食鲜 Shipment 缺少可精确换算的来源数量");
        }
        try {
            return shipped.divide(multiplier).setScale(0, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException exception) {
            throw BusinessException.unprocessable(
                    "SOURCE_RETURN_QUANTITY_NOT_SOURCE_UNIT",
                    "彩食鲜 Shipment 实发量不能精确换算为整数来源份数");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    record RowFill(
            int sheetIndex,
            int rowIndex,
            String shippedSourceQuantity,
            String carrierCode,
            String trackingNumber) {}

    private record ArtifactRow(
            String fileRef,
            int sheetIndex,
            int rowIndex,
            BigDecimal shippedQuantity,
            BigDecimal multiplier) {}
}
