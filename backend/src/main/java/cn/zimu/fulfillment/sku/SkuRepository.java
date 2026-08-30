package cn.zimu.fulfillment.sku;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkuRepository extends JpaRepository<Sku, Long> {

    Optional<Sku> findBySkuCode(String skuCode);

    Optional<Sku> findByProductIdAndFulfillmentProviderIdAndSpecificationAndUnit(
            Long productId, Long fulfillmentProviderId, String specification, String unit);

    Page<Sku> findByFulfillmentProviderId(Long fulfillmentProviderId, Pageable pageable);

    @Query(value = """
            SELECT lower(btrim(barcode))
            FROM app.skus
            WHERE active = TRUE AND barcode IS NOT NULL AND btrim(barcode) <> ''
              AND lower(btrim(barcode)) IN (:barcodes)
            GROUP BY lower(btrim(barcode))
            HAVING count(*) > 1
            """, nativeQuery = true)
    List<String> findDuplicateActiveBarcodesIn(@Param("barcodes") Collection<String> barcodes);

    /** 商品名/规格/SKU 编码大小写不敏感模糊检索（pattern 为 % 包裹的查询词），按 id 升序。 */
    @Query("""
            SELECT s FROM Sku s
            JOIN Product p ON p.id = s.productId
            WHERE (:pattern IS NULL
                   OR lower(p.productName) LIKE lower(:pattern)
                   OR lower(s.specification) LIKE lower(:pattern)
                   OR lower(s.skuCode) LIKE lower(:pattern))
              AND (:providerId IS NULL OR s.fulfillmentProviderId = :providerId)
            ORDER BY s.id
            """)
    Page<Sku> search(@Param("pattern") String pattern, @Param("providerId") Long providerId, Pageable pageable);

    /** readiness 原因筛选的有界游标扫描；Slice 不为每个分块重复执行 count。 */
    @Query("""
            SELECT s FROM Sku s
            JOIN Product p ON p.id = s.productId
            WHERE s.id > :afterId
              AND (:pattern IS NULL
                   OR lower(p.productName) LIKE lower(:pattern)
                   OR lower(s.specification) LIKE lower(:pattern)
                   OR lower(s.skuCode) LIKE lower(:pattern))
              AND (:providerId IS NULL OR s.fulfillmentProviderId = :providerId)
            ORDER BY s.id
            """)
    Slice<Sku> scanForReadiness(
            @Param("pattern") String pattern,
            @Param("providerId") Long providerId,
            @Param("afterId") Long afterId,
            Pageable pageable);
}
