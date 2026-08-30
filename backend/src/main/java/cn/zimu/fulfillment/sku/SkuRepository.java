package cn.zimu.fulfillment.sku;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkuRepository extends JpaRepository<Sku, Long> {

    Optional<Sku> findBySkuCode(String skuCode);

    Optional<Sku> findByProductIdAndFulfillmentProviderIdAndSpecificationAndUnit(
            Long productId, Long fulfillmentProviderId, String specification, String unit);

    Page<Sku> findByFulfillmentProviderId(Long fulfillmentProviderId, Pageable pageable);

    /**
     * 商品名/规格/SKU 编码/条码大小写不敏感模糊检索（pattern 为 % 包裹的查询词），按 id 升序。
     *
     * <p>条码（69 码）纳入检索的由来：2026-08-28 业务同事拿 69 码在企微问「这个商品数据库里没有吗」，
     * 系统答「没有」——码就在库里，只是检索看不见它。{@code barcode} 可空，
     * JPQL 中 {@code lower(null) LIKE ...} 求值为 null（非 true），无条码行不会被任意词误命中。
     * 该方法继续服务 REST 既有契约；Agent 多条件收窄走 {@link #searchFiltered}。
     */
    @Query("""
            SELECT s FROM Sku s
            JOIN Product p ON p.id = s.productId
            WHERE (:pattern IS NULL
                   OR lower(p.productName) LIKE lower(:pattern)
                   OR lower(s.specification) LIKE lower(:pattern)
                   OR lower(s.skuCode) LIKE lower(:pattern)
                   OR lower(s.barcode) LIKE lower(:pattern))
              AND (:providerId IS NULL OR s.fulfillmentProviderId = :providerId)
            ORDER BY s.id
            """)
    Page<Sku> search(@Param("pattern") String pattern, @Param("providerId") Long providerId, Pageable pageable);

    /**
     * Agent 侧多条件只读检索。query 模糊匹配商品名、规格、SKU 编码和条码；其余参数精确匹配，
     * 所有非空条件之间为“与”。显式 CAST 保证 PostgreSQL 能为 null 参数确定类型。
     */
    @Query(
            value = """
                    SELECT s.* FROM app.skus s
                    JOIN app.products p ON p.id = s.product_id
                    WHERE (CAST(:pattern AS text) IS NULL
                           OR lower(p.product_name) LIKE lower(CAST(:pattern AS text))
                           OR lower(s.specification) LIKE lower(CAST(:pattern AS text))
                           OR lower(s.sku_code) LIKE lower(CAST(:pattern AS text))
                           OR lower(s.barcode) LIKE lower(CAST(:pattern AS text)))
                      AND (CAST(:providerId AS bigint) IS NULL
                           OR s.fulfillment_provider_id = CAST(:providerId AS bigint))
                      AND (CAST(:barcode AS text) IS NULL OR s.barcode = CAST(:barcode AS text))
                      AND (CAST(:skuCode AS text) IS NULL OR s.sku_code = CAST(:skuCode AS text))
                      AND (CAST(:categoryId AS bigint) IS NULL
                           OR p.category_id = CAST(:categoryId AS bigint))
                      AND (CAST(:tag AS text) IS NULL
                           OR (p.tags IS NOT NULL AND jsonb_exists(p.tags, CAST(:tag AS text))))
                      AND (CAST(:active AS boolean) IS NULL OR s.active = CAST(:active AS boolean))
                    ORDER BY s.id
                    """,
            countQuery = """
                    SELECT count(*) FROM app.skus s
                    JOIN app.products p ON p.id = s.product_id
                    WHERE (CAST(:pattern AS text) IS NULL
                           OR lower(p.product_name) LIKE lower(CAST(:pattern AS text))
                           OR lower(s.specification) LIKE lower(CAST(:pattern AS text))
                           OR lower(s.sku_code) LIKE lower(CAST(:pattern AS text))
                           OR lower(s.barcode) LIKE lower(CAST(:pattern AS text)))
                      AND (CAST(:providerId AS bigint) IS NULL
                           OR s.fulfillment_provider_id = CAST(:providerId AS bigint))
                      AND (CAST(:barcode AS text) IS NULL OR s.barcode = CAST(:barcode AS text))
                      AND (CAST(:skuCode AS text) IS NULL OR s.sku_code = CAST(:skuCode AS text))
                      AND (CAST(:categoryId AS bigint) IS NULL
                           OR p.category_id = CAST(:categoryId AS bigint))
                      AND (CAST(:tag AS text) IS NULL
                           OR (p.tags IS NOT NULL AND jsonb_exists(p.tags, CAST(:tag AS text))))
                      AND (CAST(:active AS boolean) IS NULL OR s.active = CAST(:active AS boolean))
                    """,
            nativeQuery = true)
    Page<Sku> searchFiltered(
            @Param("pattern") String pattern,
            @Param("providerId") Long providerId,
            @Param("barcode") String barcode,
            @Param("skuCode") String skuCode,
            @Param("categoryId") Long categoryId,
            @Param("tag") String tag,
            @Param("active") Boolean active,
            Pageable pageable);
}
