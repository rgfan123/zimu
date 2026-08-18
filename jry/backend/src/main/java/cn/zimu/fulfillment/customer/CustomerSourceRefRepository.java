package cn.zimu.fulfillment.customer;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerSourceRefRepository extends JpaRepository<CustomerSourceRef, Long> {

    Optional<CustomerSourceRef> findBySourceChannelAndSourceCustomerRef(
            SourceChannel sourceChannel, String sourceCustomerRef);
}
