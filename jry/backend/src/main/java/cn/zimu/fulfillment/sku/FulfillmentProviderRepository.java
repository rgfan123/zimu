package cn.zimu.fulfillment.sku;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentProviderRepository extends JpaRepository<FulfillmentProvider, Long> {

    boolean existsByProviderCode(String providerCode);

    Optional<FulfillmentProvider> findByProviderCode(String providerCode);
}
