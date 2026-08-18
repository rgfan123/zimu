package cn.zimu.fulfillment.fulfillment;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentRepository extends JpaRepository<Fulfillment, Long> {

    List<Fulfillment> findByOrderLineIdIn(Collection<Long> orderLineIds);

    boolean existsByOrderLineId(Long orderLineId);

    void deleteByOrderLineId(Long orderLineId);
}
