package cn.zimu.fulfillment.sku;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProviderSkuRepository extends JpaRepository<ProviderSku, Long> {

    interface JdProviderSkuCode {
        Long getSkuId();

        String getProviderSkuCode();
    }

    boolean existsByFulfillmentProviderIdAndProviderSkuCode(Long fulfillmentProviderId, String providerSkuCode);

    boolean existsByFulfillmentProviderIdAndSkuId(Long fulfillmentProviderId, Long skuId);

    Optional<ProviderSku> findByFulfillmentProviderIdAndSkuId(Long fulfillmentProviderId, Long skuId);

    List<ProviderSku> findBySkuIdIn(Collection<Long> skuIds);

    Optional<ProviderSku> findByFulfillmentProviderIdAndProviderSkuCode(
            Long fulfillmentProviderId, String providerSkuCode);

    /**
     * 精确外部码映射缺失时，识别同一履约方下已被其他外部码占用的同名同规格 SKU。
     * 用于权威目录在写入前报告映射码冲突，不把商品内部编号当外部匹配键。
     */
    @Query("""
            SELECT ps FROM ProviderSku ps
            JOIN Sku s ON s.id = ps.skuId
            JOIN cn.zimu.fulfillment.product.Product p ON p.id = s.productId
            WHERE ps.fulfillmentProviderId = :providerId
              AND p.productName = :productName
              AND s.specification = :specification
              AND s.unit = :unit
            ORDER BY ps.id
            """)
    List<ProviderSku> findCatalogIdentityConflicts(
            @Param("providerId") Long providerId,
            @Param("productName") String productName,
            @Param("specification") String specification,
            @Param("unit") String unit);

    List<ProviderSku> findByFulfillmentProviderIdOrderByProviderSkuCodeAsc(Long fulfillmentProviderId);

    Page<ProviderSku> findByFulfillmentProviderId(Long fulfillmentProviderId, Pageable pageable);

    /** SKU → 京东履约方商品编码（EMG 编号）；一 SKU 至多一条京东映射。 */
    @Query("""
            SELECT ps.skuId AS skuId, ps.providerSkuCode AS providerSkuCode
            FROM ProviderSku ps
            JOIN FulfillmentProvider fp ON fp.id = ps.fulfillmentProviderId
            WHERE ps.skuId IN :skuIds
              AND fp.providerType = cn.zimu.fulfillment.sku.ProviderType.JD_WAREHOUSE
              AND ps.active = true
              AND fp.active = true
            ORDER BY ps.skuId, ps.id
            """)
    List<JdProviderSkuCode> findJdProviderSkuCodes(@Param("skuIds") Collection<Long> skuIds);
}
