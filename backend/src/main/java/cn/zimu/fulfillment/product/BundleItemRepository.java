package cn.zimu.fulfillment.product;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BundleItemRepository extends JpaRepository<BundleItem, Long> {

    List<BundleItem> findByBundleIdOrderBySortNo(Long bundleId);
}
