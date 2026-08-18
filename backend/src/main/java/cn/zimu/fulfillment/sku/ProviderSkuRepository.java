package cn.zimu.fulfillment.sku;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderSkuRepository extends JpaRepository<ProviderSku, Long> {

    boolean existsByFulfillmentProviderIdAndProviderSkuCode(Long fulfillmentProviderId, String providerSkuCode);

    boolean existsByFulfillmentProviderIdAndSkuId(Long fulfillmentProviderId, Long skuId);

    Optional<ProviderSku> findByFulfillmentProviderIdAndSkuId(Long fulfillmentProviderId, Long skuId);

    Optional<ProviderSku> findByFulfillmentProviderIdAndProviderSkuCode(
            Long fulfillmentProviderId, String providerSkuCode);

    List<ProviderSku> findByFulfillmentProviderIdOrderByProviderSkuCodeAsc(Long fulfillmentProviderId);

    Page<ProviderSku> findByFulfillmentProviderId(Long fulfillmentProviderId, Pageable pageable);
}
