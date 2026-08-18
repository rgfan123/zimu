package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.order.domain.OrderLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    List<OrderLine> findByOrderIdOrderByLineNoAsc(Long orderId);
}
