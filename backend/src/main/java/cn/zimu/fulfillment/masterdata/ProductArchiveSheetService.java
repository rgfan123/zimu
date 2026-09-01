package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品档案·成本表全列留存的只读查询。
 *
 * <p>写入不走应用：档案由成本表一次性灌库（迁移 V63 + 幂等 INSERT），此处只负责按商品读出来，
 * 且**保持原表列序**——SQL 不对 fields 做任何 jsonb 重排，Jackson 按数组顺序反序列化成
 * {@code List}，一路有序到前端。
 */
@Service
public class ProductArchiveSheetService {

    private static final TypeReference<List<ProductArchiveSheet.Field>> FIELDS =
            new TypeReference<>() {};
    private static final TypeReference<List<ProductArchiveSheet.ExtraCell>> EXTRA_CELLS =
            new TypeReference<>() {};

    private static final String SELECT_COLUMNS =
            """
            SELECT id, source_file_name, source_file_sha256, sheet_name, row_no,
                   product_name, matched_product_id, fields, extra_cells, created_at
            FROM app.product_archive_sheets
            """;

    private static final String SEARCH_SELECT =
            """
            SELECT pas.product_name, pas.fields, pas.matched_sku_id, sku.sku_code
            FROM app.product_archive_sheets pas
            LEFT JOIN app.skus sku ON sku.id = pas.matched_sku_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductArchiveSheetService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 某商品挂接到的成本表行。挂接是可空的——绝大多数成本行还没有确定无争议的商品可挂，
     * 那些行留在库里等人工挂接，这里自然读不到，返回空列表而不是报错。
     */
    @Transactional(readOnly = true)
    public List<ProductArchiveSheet> byProduct(long productId) {
        return jdbc.query(
                SELECT_COLUMNS + """
                WHERE matched_product_id = ?
                ORDER BY source_file_sha256, row_no
                """,
                this::map,
                productId);
    }

    /**
     * 内部管理面的全保真列表查询：继续返回 {@link ProductArchiveSheet} 存储快照形状。
     * MCP 不调用此重载，避免把文件名、指纹、行号和列字母带到对外响应。
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductArchiveSheet> search(String query, int page, int size) {
        String pattern = blankToNull(query) == null ? null : "%" + query.trim() + "%";
        long total = pattern == null
                ? jdbc.queryForObject("SELECT count(*) FROM app.product_archive_sheets", Long.class)
                : jdbc.queryForObject(
                        "SELECT count(*) FROM app.product_archive_sheets WHERE product_name ILIKE ?",
                        Long.class,
                        pattern);
        List<ProductArchiveSheet> items = pattern == null
                ? jdbc.query(
                        SELECT_COLUMNS + """
                        ORDER BY source_file_sha256, row_no
                        LIMIT ? OFFSET ?
                        """,
                        this::map,
                        size,
                        (long) page * size)
                : jdbc.query(
                        SELECT_COLUMNS + """
                        WHERE product_name ILIKE ?
                        ORDER BY source_file_sha256, row_no
                        LIMIT ? OFFSET ?
                        """,
                        this::map,
                        pattern,
                        size,
                        (long) page * size);
        int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) total / (double) size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    /**
     * 按业务字段组合查询全部成本档案行（含未挂接 SKU 的行），分页，稳定序
     * {@code source_file_sha256, row_no}（同 {@link #byProduct}）。条码是标识符，必须精确匹配并返回全部命中行。
     *
     * <p>与 {@link #byProduct} 的区别：{@code byProduct} 只能读到已经人工挂接
     * （{@code matched_product_id} 已填）的行；本方法不按挂接过滤——「先搜成本表、
     * 再决定挂不挂」是这个检索存在的理由，挂接状态不该挡在搜索前面。
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductArchiveSummary> search(
            String query,
            String barcode,
            String brand,
            String meatType,
            String status,
            Boolean linked,
            int page,
            int size) {
        // 既有 REST 档案页维持全量口径（含停产/断货），行为不变。
        return search(query, barcode, brand, meatType, status, linked, true, page, size);
    }

    /**
     * @param includeDiscontinued false 时剔除明确不再销售的行（产品状态 停产/断货）。
     *     显式传 status 时以 status 为准，本开关不再叠加——「我就要看停产的」必须能查到。
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductArchiveSummary> search(
            String query,
            String barcode,
            String brand,
            String meatType,
            String status,
            Boolean linked,
            boolean includeDiscontinued,
            int page,
            int size) {
        SearchFilter filter = searchFilter(
                query, barcode, brand, meatType, status, linked, includeDiscontinued);
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM app.product_archive_sheets pas" + filter.sql(),
                Long.class,
                filter.arguments().toArray());
        List<Object> pageArguments = new ArrayList<>(filter.arguments());
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<ProductArchiveSummary> items = jdbc.query(
                SEARCH_SELECT + filter.sql() + """
                ORDER BY pas.source_file_sha256, pas.row_no
                LIMIT ? OFFSET ?
                """,
                this::mapSummary,
                pageArguments.toArray());
        // 与 Spring Data PageImpl 的口径一致：size<=0 记 1 页，否则按元素数/页大小取上界。
        int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) total / (double) size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    /** 档案表 B 列固定是产品状态；D/E/F 等列义见 addFieldFilter 调用点。 */
    private static final String STATUS_COLUMN = "B";
    private static final String STATUS_DISCONTINUED = "停产";
    private static final String STATUS_OUT_OF_STOCK = "断货";

    private static SearchFilter searchFilter(
            String query, String barcode, String brand, String meatType, String status, Boolean linked,
            boolean includeDiscontinued) {
        // 每段都以换行结尾：调用方紧接着拼 "ORDER BY ..."，任何一段漏换行都会粘成
        // "…ORDER BY" 语法错。这里包括基串本身——不带任何过滤条件时它就是最后一段。
        StringBuilder sql = new StringBuilder(" WHERE 1=1\n");
        List<Object> arguments = new ArrayList<>();
        String normalizedQuery = blankToNull(query);
        if (normalizedQuery != null) {
            sql.append(" AND pas.product_name ILIKE ?\n");
            arguments.add("%" + normalizedQuery.strip() + "%");
        }
        addFieldFilter(sql, arguments, "D", barcode);
        addFieldFilter(sql, arguments, "E", brand);
        addFieldFilter(sql, arguments, "F", meatType);
        addFieldFilter(sql, arguments, "B", status);
        if (linked != null) {
            sql.append(linked ? " AND pas.matched_sku_id IS NOT NULL\n" : " AND pas.matched_sku_id IS NULL\n");
        }
        if (!includeDiscontinued && blankToNull(status) == null) {
            // 只剔除「明确不再销售」的两个状态；空状态/研发/新品一律保留——档案里 45 行状态为空，
            // 按「没写状态就当不在售」会把在售商品也吞掉，宁可多给不可少给。
            sql.append("""
                     AND NOT EXISTS (
                         SELECT 1
                         FROM jsonb_array_elements(pas.fields) field
                         WHERE field->>'column' = ? AND field->>'value' IN (?, ?)
                     )
                    """);
            arguments.add(STATUS_COLUMN);
            arguments.add(STATUS_DISCONTINUED);
            arguments.add(STATUS_OUT_OF_STOCK);
        }
        return new SearchFilter(sql.toString(), List.copyOf(arguments));
    }

    private static void addFieldFilter(
            StringBuilder sql, List<Object> arguments, String column, String requestedValue) {
        String normalized = blankToNull(requestedValue);
        if (normalized == null) {
            return;
        }
        sql.append("""
                 AND EXISTS (
                     SELECT 1
                     FROM jsonb_array_elements(pas.fields) field
                     WHERE field->>'column' = ? AND field->>'value' = ?
                 )
                """);
        arguments.add(column);
        arguments.add(normalized.strip());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ProductArchiveSheet map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        Long matchedProductId = rs.getObject("matched_product_id", Long.class);
        return new ProductArchiveSheet(
                String.valueOf(rs.getLong("id")),
                rs.getString("source_file_name"),
                rs.getString("source_file_sha256"),
                rs.getString("sheet_name"),
                rs.getInt("row_no"),
                rs.getString("product_name"),
                matchedProductId == null ? null : String.valueOf(matchedProductId),
                parse(rs.getString("fields"), FIELDS),
                parse(rs.getString("extra_cells"), EXTRA_CELLS),
                createdAt == null ? null : createdAt.toInstant());
    }

    private ProductArchiveSummary mapSummary(ResultSet rs, int row) throws SQLException {
        List<ProductArchiveSheet.Field> fields = parse(rs.getString("fields"), FIELDS);
        Map<String, ProductArchiveSheet.Field> byColumn = new LinkedHashMap<>();
        fields.forEach(field -> byColumn.putIfAbsent(field.column(), field));
        List<ProductArchiveSummary.CostingField> costing = fields.stream()
                .filter(field -> !isIdentityColumn(field.column()))
                .map(field -> new ProductArchiveSummary.CostingField(field.name(), field.value()))
                .toList();
        Long matchedSkuId = rs.getObject("matched_sku_id", Long.class);
        return new ProductArchiveSummary(
                value(byColumn, "A", rs.getString("product_name")),
                value(byColumn, "E", null),
                value(byColumn, "C", null),
                value(byColumn, "D", null),
                value(byColumn, "F", null),
                value(byColumn, "G", null),
                value(byColumn, "B", null),
                matchedSkuId != null,
                matchedSkuId == null ? null : rs.getString("sku_code"),
                matchedSkuId == null ? null : String.valueOf(matchedSkuId),
                costing);
    }

    private static boolean isIdentityColumn(String column) {
        return column != null && column.length() == 1 && column.charAt(0) >= 'A' && column.charAt(0) <= 'G';
    }

    private static String value(
            Map<String, ProductArchiveSheet.Field> fields, String column, String fallback) {
        ProductArchiveSheet.Field field = fields.get(column);
        return field == null ? fallback : field.value();
    }

    private <T> List<T> parse(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw BusinessException.conflict(
                    "PRODUCT_ARCHIVE_SHEET_UNREADABLE", "商品档案成本表行内容无法解析");
        }
    }

    /** 读接口的存在性校验：商品不存在时给 404，而不是空数组冒充「这个商品没有档案」。 */
    @Transactional(readOnly = true)
    public void requireProduct(long productId) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM app.products WHERE id = ?", Integer.class, productId);
        if (found == null || found == 0) {
            throw BusinessException.notFound("商品不存在");
        }
    }

    private record SearchFilter(String sql, List<Object> arguments) {}
}
