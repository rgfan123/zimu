package cn.zimu.fulfillment.common.event;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    List<OrderEvent> findByOrderIdOrderBySequenceNoAsc(Long orderId);
}
