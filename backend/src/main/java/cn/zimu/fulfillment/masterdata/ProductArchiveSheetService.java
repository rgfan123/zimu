package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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
                   product_name, fields, extra_cells, created_at
            FROM app.product_archive_sheets
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
     * 按商品名模糊检索全部成本表档案行（含未挂接商品的行），分页，稳定序
     * {@code source_file_sha256, row_no}（同 {@link #byProduct}）。
     *
     * <p>与 {@link #byProduct} 的区别：{@code byProduct} 只能读到已经人工挂接
     * （{@code matched_product_id} 已填）的行；本方法不按挂接过滤——「先搜成本表、
     * 再决定挂不挂」是这个检索存在的理由，挂接状态不该挡在搜索前面。
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
        // 与 Spring Data PageImpl 的口径一致：size<=0 记 1 页，否则按元素数/页大小取上界。
        int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) total / (double) size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ProductArchiveSheet map(ResultSet rs, int row) throws SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new ProductArchiveSheet(
                String.valueOf(rs.getLong("id")),
                rs.getString("source_file_name"),
                rs.getString("source_file_sha256"),
                rs.getString("sheet_name"),
                rs.getInt("row_no"),
                rs.getString("product_name"),
                parse(rs.getString("fields"), FIELDS),
                parse(rs.getString("extra_cells"), EXTRA_CELLS),
                createdAt == null ? null : createdAt.toInstant());
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
}
