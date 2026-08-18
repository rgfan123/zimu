package cn.zimu.fulfillment.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderDraftLineRepository extends JpaRepository<OrderDraftLine, Long> {

    List<OrderDraftLine> findByOrderDraftIdOrderByLineNoAsc(Long orderDraftId);

    List<OrderDraftLine> findByOrderDraftIdInOrderByOrderDraftIdAscLineNoAsc(Collection<Long> orderDraftIds);
}
