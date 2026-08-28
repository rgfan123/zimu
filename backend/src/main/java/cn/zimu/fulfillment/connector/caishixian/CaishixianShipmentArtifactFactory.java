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

/**
 * 从彩食鲜来源血缘构造单 Shipment 上传产物（22 列回填工作簿）。
 *
 * <p>两条来源分支：
 * <ul>
 *   <li><b>Excel 导入批次</b>（file_ref 指向留存的原始工作簿）：从原始工作簿复制目标行，
 *       只回填发货列——既有行为一字未动；</li>
 *   <li><b>结构化批次</b>（在线 JSON 拉取，file_ref 为 {@code structured://…} 占位，没有
 *       原始工作簿可复制）：按 raw_cells 快照（拉取时从 orderList/orderDetail 白名单落库）
 *       + Shipment 发货事实重建同样的 22 列模板。「站点编码」为 JSON 契约已知缺失列，
 *       落空串——平台是否接受空站点编码只能生产验证（研究文档唯一一次失败样例的原因是
 *       回填列全空，与站点编码无关）。收货人/联系电话取 Shipment 事实（快照里是掩码），
 *       上传前 Connector 仍会与平台当前收货信息核对。</li>
 * </ul>
 */
@Component
public class CaishixianShipmentArtifactFactory implements SourceShipmentArtifactFactory {

    private static final String CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_UPLOAD_BYTES = 1024 * 1024;
    private static final Instant FIXED_CORE_TIMESTAMP = Instant.parse("2000-01-01T00:00:00Z");
    private static final LocalDateTime FIXED_ZIP_ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    /** 结构化导入批次的 file_ref 前缀（SourceImportService.importStructured 写入）。 */
    static final String STRUCTURED_FILE_REF_PREFIX = "structured://";
    private static final List<String> REQUIRED_HEADERS = List.of(
            "主订单编号", "子订单编号", "采购单号", "供应商编码", "站点编码", "收货人",
            "联系电话", "省", "市", "区", "详细地址", "物流要求编码", "物流要求名称",
            "商品编号", "商品名称", "下单数量", "订单备注", "发货数量", "物流公司代码",
            "物流单号", "vip订单标识", "错误原因");

    private static final com.fasterxml.jackson.databind.ObjectMapper RAW_CELLS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
                       si.shipped_quantity, ol.mapping_multiplier_snapshot,
                       rir.raw_cells::text AS raw_cells
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id
                JOIN app.import_batches ib ON ib.id=o.source_import_batch_id
                JOIN app.shipment_items si ON si.shipment_id=s.id
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN raw_line_links rll ON rll.order_line_id=ol.id
                JOIN app.raw_import_rows rir
                  ON rir.id=rll.raw_row_id AND rir.import_batch_id=ib.id AND rir.status='ACCEPTED'
                -- 子单号回退与 SourceLineRefFallback 同源：Excel 链路的 raw_cells 是平台原始列，
                -- 没有规范键；raw_cells 受 protect_raw_import_row() 保护不可回填，只能读侧回退。
                WHERE s.id=? AND COALESCE(NULLIF(rir.raw_cells->>'source_line_ref', ''),
                                          NULLIF(rir.raw_cells->>'子订单编号', ''))=?
                ORDER BY rir.sheet_index, rir.row_index
                """,
                (rs, rowNum) -> new ArtifactRow(
                        rs.getString("file_ref"),
                        rs.getInt("sheet_index"),
                        rs.getInt("row_index"),
                        rs.getBigDecimal("shipped_quantity"),
                        rs.getBigDecimal("mapping_multiplier_snapshot"),
                        rs.getString("raw_cells")),
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
        byte[] rendered;
        if (fileRef.startsWith(STRUCTURED_FILE_REF_PREFIX)) {
            // 结构化（JSON 拉取）批次：没有原始工作簿字节，从 raw_cells 快照 + 发货事实重建。
            rendered = renderStructured(facts, rows);
        } else {
            List<RowFill> fills = rows.stream()
                    .map(row -> new RowFill(
                            row.sheetIndex(),
                            row.rowIndex(),
                            sourceQuantity(row.shippedQuantity(), row.multiplier()),
                            facts.carrierOutputValue(),
                            facts.trackingNumber()))
                    .toList();
            rendered = render(files.read(fileRef), fills);
        }
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

    /**
     * 结构化分支：按 CaishixianOrderTransform 落库的快照键重建 22 列模板行。
     * 收货人/联系电话取 Shipment 事实（快照中已掩码不可用；Connector 上传前仍会与平台
     * 当前收货信息核对）；省/市/区/详细地址、单号与商品列取快照明文；「站点编码」JSON
     * 契约缺失，落空串。发货三列与 Excel 分支同源（实发量按映射倍数换算回来源份数）。
     */
    private byte[] renderStructured(SourceSyncFacts facts, List<ArtifactRow> rows) {
        List<List<String>> lines = new ArrayList<>();
        for (ArtifactRow row : rows) {
            com.fasterxml.jackson.databind.JsonNode cells = parseRawCells(row.rawCells());
            com.fasterxml.jackson.databind.JsonNode snapshot = cells.path("snapshot");
            int itemIndex = cells.path("item_index").asInt(-1);
            com.fasterxml.jackson.databind.JsonNode goods = snapshot.path("goods").path(itemIndex);
            if (snapshot.isMissingNode() || !goods.isObject()) {
                throw BusinessException.unprocessable(
                        "CAISHIXIAN_SHIPMENT_SNAPSHOT_INCOMPLETE",
                        "彩食鲜结构化来源快照缺少商品行，无法重建回填工作簿");
            }
            String shippedSourceQuantity = sourceQuantity(row.shippedQuantity(), row.multiplier());
            lines.add(List.of(
                    snapshot.path("主订单编号").asText(""),
                    snapshot.path("子订单编号").asText(""),
                    snapshot.path("采购单号").asText(""),
                    snapshot.path("供应商编码").asText(""),
                    "", // 站点编码：JSON 契约已知缺失（快照 site_code_missing 标记）
                    blankTo(facts.receiverName()),
                    blankTo(facts.receiverPhone()),
                    snapshot.path("省").asText(""),
                    snapshot.path("市").asText(""),
                    snapshot.path("区").asText(""),
                    snapshot.path("详细地址").asText(""),
                    snapshot.path("物流要求编码").asText(""),
                    snapshot.path("物流要求名称").asText(""),
                    goods.path("商品编号").asText(""),
                    goods.path("商品名称").asText(""),
                    goods.path("下单数量").asText(""),
                    snapshot.path("订单备注").asText(""),
                    shippedSourceQuantity,
                    blankTo(facts.carrierOutputValue()),
                    blankTo(facts.trackingNumber()),
                    snapshot.path("vip订单标识").asText(""),
                    "")); // 错误原因：回填列，上传时留空
        }
        return renderFromValues(lines);
    }

    private static com.fasterxml.jackson.databind.JsonNode parseRawCells(String rawCells) {
        try {
            return RAW_CELLS_MAPPER.readTree(rawCells == null ? "{}" : rawCells);
        } catch (Exception exception) {
            throw BusinessException.unprocessable(
                    "CAISHIXIAN_SHIPMENT_SNAPSHOT_INCOMPLETE",
                    "彩食鲜结构化来源快照不可解析，无法重建回填工作簿");
        }
    }

    private static String blankTo(String value) {
        return value == null ? "" : value.trim();
    }

    /** 由纯值行构建确定性 22 列工作簿（表头 + 数据行；打包口径与 render 一致）。 */
    static byte[] renderFromValues(List<List<String>> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("彩食鲜 Shipment 回填行不能为空");
        }
        try (var outputWorkbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = outputWorkbook.createSheet("待发货订单");
            Row header = sheet.createRow(0);
            for (int column = 0; column < REQUIRED_HEADERS.size(); column++) {
                header.createCell(column).setCellValue(REQUIRED_HEADERS.get(column));
            }
            int rowIndex = 1;
            for (List<String> line : lines) {
                if (line.size() != REQUIRED_HEADERS.size()) {
                    throw new IllegalArgumentException("彩食鲜结构化回填行列数与 22 列模板不符");
                }
                Row target = sheet.createRow(rowIndex++);
                for (int column = 0; column < line.size(); column++) {
                    target.createCell(column).setCellValue(line.get(column));
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
            BigDecimal multiplier,
            String rawCells) {}
}
