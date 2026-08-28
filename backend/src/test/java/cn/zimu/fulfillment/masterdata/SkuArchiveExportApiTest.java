package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 商品档案导出：每个 active SKU 一行，成本表 A..AU 严格保位。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuArchiveExportApiTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> FIXED_HEADERS =
            List.of("SKU编码", "商品名称", "京东EMG编号", "品类", "规格", "单位", "条码", "履约方");
    private static final List<String> ARCHIVE_HEADERS = List.of(
            "产品名称", "产品状态", "规格（g）", "国条", "品牌", "肉类", "原料", "供应渠道",
            "包装形式", "加工要求", "净含量/g", "加工规格/g", "原料成本kg/元", "核算成本 /份",
            "原料利润", "成本+原料利润/kg", "人工费", "人工 占比", "修割损耗率", "损耗成本/KG",
            "损耗后 成本/KG", "加工后 成本/KG", "加工后 成本/份", "盒/袋", "贴纸/腰封", "膜",
            "签", "泡沫箱/纸箱+冰袋", "耗材/KG", "耗材/份", "耗材 占比", "含耗材 成本/份",
            "物流（原料进货）/kg", "物流（成品送货）/kg", "线下供货成本/份", "售价",
            "（AK 列无表头）", "账期比例", "账期费用/份", "扣点", "扣点费用/份", "总成本/KG",
            "扣完成本/份", "供货价", "毛利率", "促销价格", "大促");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void skuLevelArchiveMatchExportsAllFortySevenFieldsAndUtf8BusinessDateFilename() throws Exception {
        CatalogRow catalog = createCatalog("SKU", "JD_WAREHOUSE");
        jdbc.update(
                "INSERT INTO app.provider_skus"
                        + "(fulfillment_provider_id, sku_id, provider_sku_code) VALUES (?, ?, ?)",
                catalog.providerId(), catalog.skuId(), "EMG-EXPORT-SKU-001");
        insertArchive("a".repeat(64), 201, catalog.skuId(), null, "SKU值");

        ResponseEntity<byte[]> response = download();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        String expectedFilename = "子牧商品档案"
                + LocalDate.now(SHANGHAI).format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        assertThat(ContentDisposition.parse(disposition).getFilename()).isEqualTo(expectedFilename);
        assertThat(disposition).contains("filename*=UTF-8''");

        try (XSSFWorkbook workbook = workbook(response)) {
            Sheet sheet = workbook.getSheetAt(0);
            Long activeSkuCount = jdbc.queryForObject(
                    "SELECT count(*) FROM app.skus WHERE active", Long.class);
            assertThat(sheet.getLastRowNum()).isEqualTo(activeSkuCount.intValue());
            assertHeaders(sheet.getRow(0));
            Row row = rowBySkuCode(sheet, catalog.skuCode());
            assertThat(text(row, 2)).isEqualTo("EMG-EXPORT-SKU-001");
            assertArchiveValues(row, "SKU值");
        }
    }

    @Test
    void productLevelArchiveMatchFallsBackWhenMatchedSkuIdIsNull() throws Exception {
        CatalogRow catalog = createCatalog("PRODUCT", "THIRD_PARTY");
        insertArchive("b".repeat(64), 202, null, catalog.productId(), "商品值");

        try (XSSFWorkbook workbook = workbook(download())) {
            Row row = rowBySkuCode(workbook.getSheetAt(0), catalog.skuCode());
            assertThat(text(row, 1)).isEqualTo("导出测试商品-PRODUCT");
            assertThat(text(row, 2)).isEmpty();
            assertArchiveValues(row, "商品值");
        }
    }

    @Test
    void unmatchedSkuExportsFortySevenBlankCellsAndNeverLeaksSystemPrices() throws Exception {
        CatalogRow catalog = createCatalog("UNMATCHED", "THIRD_PARTY");
        insertArchive("c".repeat(64), 203, null, null, "未挂接档案值");

        try (XSSFWorkbook workbook = workbook(download())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row = rowBySkuCode(sheet, catalog.skuCode());
            assertThat(row.getLastCellNum()).isEqualTo((short) 55);
            for (int column = 8; column < 55; column++) {
                assertThat(row.getCell(column).getCellType())
                        .as("未挂接 SKU 的档案列 %s 应为空单元格", column - 7)
                        .isEqualTo(CellType.BLANK);
            }
            List<String> allCells = new ArrayList<>();
            for (Row workbookRow : sheet) {
                for (Cell cell : workbookRow) {
                    allCells.add(cell.toString());
                }
            }
            assertThat(allCells)
                    .doesNotContain("99999.11", "88888.22", "77777.33")
                    .doesNotContain("—", "0");
        }
    }

    private CatalogRow createCatalog(String suffix, String providerType) {
        Map<String, Object> provider = jdbc.queryForMap(
                "SELECT id, provider_code, provider_name FROM app.fulfillment_providers "
                        + "WHERE provider_type = ? AND active ORDER BY id LIMIT 1",
                providerType);
        Long categoryId = jdbc.queryForObject("SELECT id FROM app.categories ORDER BY id LIMIT 1", Long.class);
        Long productId = jdbc.queryForObject(
                """
                INSERT INTO app.products (
                    product_code, product_name, category_id, purchase_price, retail_price, other_cost)
                VALUES (?, ?, ?, 99999.11, 88888.22, 77777.33)
                RETURNING id
                """,
                Long.class,
                "P-EXPORT-" + suffix,
                "导出测试商品-" + suffix,
                categoryId);
        Long skuId = jdbc.queryForObject(
                """
                INSERT INTO app.skus (
                    product_id, fulfillment_provider_id, specification, unit, barcode,
                    purchase_price, retail_price)
                VALUES (?, ?, '500g*2袋', '袋', ?, 99999.11, 88888.22)
                RETURNING id
                """,
                Long.class,
                productId,
                ((Number) provider.get("id")).longValue(),
                "BARCODE-" + suffix);
        String skuCode = jdbc.queryForObject(
                "SELECT sku_code FROM app.skus WHERE id = ?", String.class, skuId);
        return new CatalogRow(
                productId,
                skuId,
                ((Number) provider.get("id")).longValue(),
                skuCode);
    }

    private void insertArchive(
            String sha, int rowNo, Long matchedSkuId, Long matchedProductId, String valuePrefix) throws Exception {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (int index = 0; index < ARCHIVE_HEADERS.size(); index++) {
            fields.add(Map.of(
                    "column", excelColumn(index),
                    "name", ARCHIVE_HEADERS.get(index),
                    "value", valuePrefix + "-" + String.format("%02d", index + 1)));
        }
        jdbc.update(
                """
                INSERT INTO app.product_archive_sheets (
                    source_file_name, source_file_sha256, sheet_name, row_no,
                    product_name, fields, matched_sku_id, matched_product_id)
                VALUES ('商品档案导出测试.xlsx', ?, '成品', ?, ?, ?::jsonb, ?, ?)
                """,
                sha,
                rowNo,
                valuePrefix,
                objectMapper.writeValueAsString(fields),
                matchedSkuId,
                matchedProductId);
    }

    private ResponseEntity<byte[]> download() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
        return http.exchange(
                "/api/v1/skus/export",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
    }

    private static XSSFWorkbook workbook(ResponseEntity<byte[]> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return new XSSFWorkbook(new ByteArrayInputStream(response.getBody()));
    }

    private static void assertHeaders(Row header) {
        assertThat(header.getLastCellNum()).isEqualTo((short) 55);
        assertThat(cells(header, 0, 8)).containsExactlyElementsOf(FIXED_HEADERS);
        assertThat(cells(header, 8, 55)).containsExactlyElementsOf(ARCHIVE_HEADERS);
        assertThat(text(header, 44)).isEqualTo("（AK 列无表头）");
    }

    private static void assertArchiveValues(Row row, String prefix) {
        assertThat(row.getLastCellNum()).isEqualTo((short) 55);
        assertThat(cells(row, 8, 55))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 47)
                        .mapToObj(index -> prefix + "-" + String.format("%02d", index))
                        .toList());
    }

    private static Row rowBySkuCode(Sheet sheet, String skuCode) {
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && skuCode.equals(text(row, 0))) {
                return row;
            }
        }
        throw new AssertionError("导出中找不到 SKU " + skuCode);
    }

    private static List<String> cells(Row row, int fromInclusive, int toExclusive) {
        List<String> values = new ArrayList<>();
        for (int column = fromInclusive; column < toExclusive; column++) {
            values.add(text(row, column));
        }
        return values;
    }

    private static String text(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? "" : cell.toString();
    }

    private static String excelColumn(int zeroBasedIndex) {
        int value = zeroBasedIndex + 1;
        StringBuilder column = new StringBuilder();
        while (value > 0) {
            value--;
            column.insert(0, (char) ('A' + value % 26));
            value /= 26;
        }
        return column.toString();
    }

    private record CatalogRow(long productId, long skuId, long providerId, String skuCode) {}
}
