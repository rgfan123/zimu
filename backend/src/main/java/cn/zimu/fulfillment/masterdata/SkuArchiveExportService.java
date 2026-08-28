package cn.zimu.fulfillment.masterdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将全部 active SKU 与其成本档案原序列导出为 xlsx。 */
@Service
public class SkuArchiveExportService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int ARCHIVE_COLUMN_COUNT = 47;
    private static final int AK_ARCHIVE_INDEX = 36;
    private static final String AK_PLACEHOLDER = "（AK 列无表头）";
    private static final String[] FIXED_HEADERS =
            {"SKU编码", "商品名称", "京东EMG编号", "品类", "规格", "单位", "条码", "履约方"};
    private static final int[] COLUMN_WIDTHS = {
        18, 28, 20, 14, 18, 10, 18, 16,
        24, 12, 14, 16, 14, 12, 18, 16, 16, 16, 14, 14, 18, 16, 14, 20,
        12, 14, 14, 16, 18, 18, 18, 12, 14, 10, 10, 24, 14, 14, 14, 18,
        20, 20, 20, 14, 18, 14, 16, 12, 16, 16, 16, 14, 14, 14, 12
    };
    private static final TypeReference<List<ProductArchiveSheet.Field>> ARCHIVE_FIELDS =
            new TypeReference<>() {};

    private static final String ROWS_SQL =
            """
            SELECT s.sku_code,
                   p.product_name,
                   jd_mapping.provider_sku_code AS jd_emg_no,
                   c.category_name,
                   s.specification,
                   s.unit,
                   s.barcode,
                   fp.provider_name,
                   archive.fields::text AS archive_fields
            FROM app.skus s
            JOIN app.products p ON p.id = s.product_id
            LEFT JOIN app.categories c ON c.id = p.category_id
            JOIN app.fulfillment_providers fp ON fp.id = s.fulfillment_provider_id
            LEFT JOIN LATERAL (
                SELECT ps.provider_sku_code
                FROM app.provider_skus ps
                JOIN app.fulfillment_providers jd ON jd.id = ps.fulfillment_provider_id
                WHERE ps.sku_id = s.id
                  AND ps.active
                  AND jd.active
                  AND jd.provider_type = 'JD_WAREHOUSE'
                ORDER BY ps.id
                LIMIT 1
            ) jd_mapping ON TRUE
            LEFT JOIN LATERAL (
                SELECT pas.fields
                FROM app.product_archive_sheets pas
                WHERE pas.matched_sku_id = s.id
                   OR (pas.matched_sku_id IS NULL AND pas.matched_product_id = s.product_id)
                ORDER BY CASE WHEN pas.matched_sku_id = s.id THEN 0 ELSE 1 END,
                         pas.source_file_sha256,
                         pas.row_no,
                         pas.id
                LIMIT 1
            ) archive ON TRUE
            WHERE s.active
            ORDER BY s.sku_code, s.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SkuArchiveExportService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ExportFile export() {
        List<String> archiveHeaders = archiveHeaders();
        List<ExportRow> rows = jdbc.query(ROWS_SQL, this::mapRow);
        return new ExportFile(filename(), workbook(archiveHeaders, rows));
    }

    private List<String> archiveHeaders() {
        List<String> storedHeaders = jdbc.query(
                """
                SELECT field ->> 'name' AS name
                FROM (
                    SELECT fields
                    FROM app.product_archive_sheets
                    WHERE jsonb_array_length(fields) = 47
                    ORDER BY source_file_sha256, row_no, id
                    LIMIT 1
                ) template
                CROSS JOIN LATERAL jsonb_array_elements(template.fields)
                    WITH ORDINALITY AS item(field, ordinal)
                ORDER BY item.ordinal
                LIMIT 47
                """,
                (rs, rowNumber) -> rs.getString("name"));
        List<String> headers = new ArrayList<>(ARCHIVE_COLUMN_COUNT);
        for (int index = 0; index < ARCHIVE_COLUMN_COUNT; index++) {
            String stored = index < storedHeaders.size() ? storedHeaders.get(index) : null;
            if (stored == null || stored.isBlank()) {
                headers.add(index == AK_ARCHIVE_INDEX ? AK_PLACEHOLDER : "");
            } else {
                headers.add(stored);
            }
        }
        return headers;
    }

    private ExportRow mapRow(ResultSet rs, int rowNumber) throws SQLException {
        return new ExportRow(
                new String[] {
                    rs.getString("sku_code"),
                    rs.getString("product_name"),
                    rs.getString("jd_emg_no"),
                    rs.getString("category_name"),
                    rs.getString("specification"),
                    rs.getString("unit"),
                    rs.getString("barcode"),
                    rs.getString("provider_name")
                },
                parseArchiveFields(rs.getString("archive_fields")));
    }

    private List<ProductArchiveSheet.Field> parseArchiveFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ARCHIVE_FIELDS);
        } catch (IOException exception) {
            throw new IllegalStateException("商品档案导出内容无法解析", exception);
        }
    }

    private byte[] workbook(List<String> archiveHeaders, List<ExportRow> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("商品档案");
            for (int index = 0; index < COLUMN_WIDTHS.length; index++) {
                sheet.setColumnWidth(index, COLUMN_WIDTHS[index] * 256);
            }

            XSSFFont bold = workbook.createFont();
            bold.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);
            Row header = sheet.createRow(0);
            int headerColumn = 0;
            for (String fixedHeader : FIXED_HEADERS) {
                Cell cell = header.createCell(headerColumn++);
                cell.setCellValue(fixedHeader);
                cell.setCellStyle(headerStyle);
            }
            for (String archiveHeader : archiveHeaders) {
                Cell cell = header.createCell(headerColumn++);
                cell.setCellValue(archiveHeader);
                cell.setCellStyle(headerStyle);
            }

            int rowNumber = 1;
            for (ExportRow exportRow : rows) {
                Row sheetRow = sheet.createRow(rowNumber++);
                int column = 0;
                for (String fixedCell : exportRow.fixedCells()) {
                    Cell cell = sheetRow.createCell(column++);
                    if (fixedCell != null) {
                        cell.setCellValue(fixedCell);
                    }
                }
                for (int archiveIndex = 0; archiveIndex < ARCHIVE_COLUMN_COUNT; archiveIndex++) {
                    Cell cell = sheetRow.createCell(column++);
                    if (archiveIndex < exportRow.archiveFields().size()) {
                        String value = exportRow.archiveFields().get(archiveIndex).value();
                        if (value != null) {
                            cell.setCellValue(value);
                        }
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("商品档案导出文件生成失败", exception);
        }
    }

    private static String filename() {
        return "子牧商品档案"
                + LocalDate.now(SHANGHAI).format(DateTimeFormatter.BASIC_ISO_DATE)
                + ".xlsx";
    }

    public record ExportFile(String filename, byte[] bytes) {}

    private record ExportRow(String[] fixedCells, List<ProductArchiveSheet.Field> archiveFields) {}
}
