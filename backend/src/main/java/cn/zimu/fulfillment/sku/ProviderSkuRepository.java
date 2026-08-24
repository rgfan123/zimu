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

    Optional<ProviderSku> findByFulfillmentProviderIdAndProviderSkuCode(
            Long fulfillmentProviderId, String providerSkuCode);

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
