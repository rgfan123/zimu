package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.order.domain.OrderLineComponent;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineComponentRepository extends JpaRepository<OrderLineComponent, Long> {

    List<OrderLineComponent> findByOrderLineIdIn(Collection<Long> orderLineIds);

    void deleteByOrderLineId(Long orderLineId);
}
