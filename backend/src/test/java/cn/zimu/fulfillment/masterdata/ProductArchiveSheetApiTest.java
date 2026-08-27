package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

/**
 * 商品档案·成本表全列留存（V63）：读接口必须**保持原表列序**，空单元格保位，灌库幂等。
 *
 * <p>列序是这张表存在的理由——所以断言不是「包含这些列」而是 {@code containsExactly}：任何一次
 * 把 fields 从 jsonb 数组改成 jsonb 对象的「优化」，都会被 PostgreSQL 的对象键重排打穿这条断言。
 * 故意用长短不一、且字典序与原表列序不一致的列头来放大这种重排。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductArchiveSheetApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SHA = "e185b33fb5e856e9bdc324d6f4af8278ffb6937db3b09c4405f849208c2c86e4";

    /** 原表列序：字典序会把「产品名称」排到最后、「B」排到最前，与此顺序不同——正是要防的重排。 */
    private static final List<String> COLUMN_ORDER =
            List.of("产品名称", "规格（g）", "国条", "线下供货成本/份", "售价", "（AK 列无表头）", "总成本/KG");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void archiveSheetKeepsOriginalColumnOrderAndIsIdempotentPerSourceRow() {
        String productId = createProduct("P-ARCHIVE-SHEET-01", "档案成本表商品");

        insertRow(productId, 54, "新西兰羔羊羊颈排", "58.3867368421053", "78");
        insertRow(productId, 55, "新西兰羔羊肉卷", "14.9797894736842", "25");
        // 幂等：同一 (source_file_sha256, row_no) 重灌不产生第二行。
        insertRow(productId, 54, "新西兰羔羊羊颈排", "58.3867368421053", "78");

        ResponseEntity<List> response =
                http.getForEntity("/api/v1/products/" + productId + "/archive-sheet", List.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = response.getBody();
        assertThat(rows).as("幂等灌库后该商品应恰有两行成本表档案").hasSize(2);
        assertThat(rows).extracting(row -> row.get("row_no")).containsExactly(54, 55);
        assertThat(rows.get(0))
                .containsEntry("product_name", "新西兰羔羊羊颈排")
                .containsEntry("source_file_sha256", SHA)
                .containsEntry("sheet_name", "成品")
                .containsEntry("source_file_name", "A产品成本核算26.3.29.xlsx");

        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> fields = (List<Map<String, Object>>) row.get("fields");
            assertThat(fields)
                    .as("fields 必须按原表列序返回（数组序 == 列序），不得被 jsonb 对象键重排洗掉")
                    .extracting(field -> field.get("name"))
                    .containsExactlyElementsOf(COLUMN_ORDER);
            assertThat(fields)
                    .extracting(field -> field.get("column"))
                    .containsExactly("A", "C", "D", "AI", "AJ", "AK", "AP");
            assertThat(fields.get(2))
                    .as("空单元格保留元素、value 记 null，保证同一列在每行的下标恒定")
                    .containsEntry("value", null);
        }

        List<Map<String, Object>> first = (List<Map<String, Object>>) rows.get(0).get("fields");
        assertThat(first.get(3))
                .as("AI 线下供货成本/份 = 成本（按份 500g 口径），原值原样留存")
                .containsEntry("value", "58.3867368421053");
        assertThat(first.get(4))
                .as("AJ 售价 = 不含运费售价，只留档不入 retail_price")
                .containsEntry("value", "78");
        assertThat(rows.get(0).get("extra_cells")).isEqualTo(List.of());
    }

    @Test
    void archiveSheetIsEmptyForUnmatchedProductAndMissingProductIsNotFound() {
        String productId = createProduct("P-ARCHIVE-SHEET-02", "尚未挂接的商品");

        ResponseEntity<List> empty =
                http.getForEntity("/api/v1/products/" + productId + "/archive-sheet", List.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getBody())
                .as("绝大多数成本行还没有确定无争议的商品可挂，读不到不是错误")
                .isEmpty();

        ResponseEntity<Map> missing =
                http.getForEntity("/api/v1/products/999999999/archive-sheet", Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** 直灌档案行：档案由成本表一次性灌库，应用侧没有写接口，测试按生产同款幂等语句插入。 */
    private void insertRow(String productId, int rowNo, String productName, String cost, String price) {
        String fields = """
                [{"column":"A","name":"产品名称","value":"%s"},
                 {"column":"C","name":"规格（g）","value":"1000"},
                 {"column":"D","name":"国条","value":null},
                 {"column":"AI","name":"线下供货成本/份","value":"%s"},
                 {"column":"AJ","name":"售价","value":"%s"},
                 {"column":"AK","name":"（AK 列无表头）","value":"0.26"},
                 {"column":"AP","name":"总成本/KG","value":"81.21"}]
                """.formatted(productName, cost, price);
        jdbc.update(
                """
                INSERT INTO app.product_archive_sheets (
                    source_file_name, source_file_sha256, sheet_name, row_no,
                    product_name, fields, matched_product_id)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (source_file_sha256, row_no) DO NOTHING
                """,
                "A产品成本核算26.3.29.xlsx", SHA, "成品", rowNo, productName, fields,
                Long.parseLong(productId));
    }

    private String createProduct(String productCode, String productName) {
        Map<String, Object> categoryPage = http.getForEntity("/api/v1/categories?size=20", Map.class).getBody();
        Map<String, Object> category = ((List<Map<String, Object>>) categoryPage.get("items")).get(0);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("product_code", productCode);
        request.put("product_name", productName);
        request.put("category_id", category.get("id"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "archive-sheet-" + productCode);
        headers.set("X-Request-Id", "req-archive-sheet-" + productCode);
        headers.set("X-Operator", "ops-archive-sheet");
        ResponseEntity<Map> created = http.exchange(
                "/api/v1/products", HttpMethod.POST, new HttpEntity<>(request, headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").toString();
    }
}
