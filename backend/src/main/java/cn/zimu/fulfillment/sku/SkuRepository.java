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
}
