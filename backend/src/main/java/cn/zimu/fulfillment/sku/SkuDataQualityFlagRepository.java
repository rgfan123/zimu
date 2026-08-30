package cn.zimu.fulfillment.sku;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuDataQualityFlagRepository extends JpaRepository<SkuDataQualityFlag, Long> {

    List<SkuDataQualityFlag> findBySkuIdInAndActiveTrueOrderBySkuIdAscFlagCodeAsc(Collection<Long> skuIds);
}
