package cn.zimu.fulfillment.masterdata;

import java.time.Instant;
import java.util.List;

/**
 * 商品档案·成本表全列留存的一行（对应 {@code app.product_archive_sheets} 一行 = 成本表一行）。
 *
 * <p>{@link #fields} 是**有序列表**，顺序即原表列序（A..AU）——库里也刻意存成 jsonb 数组而不是
 * 对象，因为 PostgreSQL 的 jsonb 对象不保证键序。读侧不排序、不过滤、不重命名，原样端出去。
 *
 * <p>列语义（2026-08-27 用户拍板）：{@code AI 线下供货成本/份} = 成本；{@code AJ 售价} =
 * 不含运费的售价。本档案只留存不换算，也不回写 {@code skus.purchase_price / retail_price}。
 */
public record ProductArchiveSheet(
        String id,
        String sourceFileName,
        String sourceFileSha256,
        String sheetName,
        int rowNo,
        String productName,
        String matchedProductId,
        List<Field> fields,
        List<ExtraCell> extraCells,
        Instant createdAt) {

    /** 一个单元格：列字母 + 列头 + 文本值；空单元格保留元素、{@code value} 为 null 以保位。 */
    public record Field(String column, String name, String value) {}

    /** 表格正身右侧（AU 之后）的零散手工草稿格：没有表头，不是表的列，但不丢。 */
    public record ExtraCell(String column, String value) {}
}
