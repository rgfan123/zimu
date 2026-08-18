package cn.zimu.fulfillment.common.version;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderVersionRepository extends JpaRepository<OrderVersion, Long> {

    List<OrderVersion> findByOrderIdOrderByVersionNoAsc(Long orderId);
}
