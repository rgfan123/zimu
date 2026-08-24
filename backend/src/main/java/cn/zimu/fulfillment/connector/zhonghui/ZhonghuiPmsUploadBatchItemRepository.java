package cn.zimu.fulfillment.connector.zhonghui;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZhonghuiPmsUploadBatchItemRepository extends JpaRepository<ZhonghuiPmsUploadBatchItem, Long> {

    List<ZhonghuiPmsUploadBatchItem> findByBatchIdOrderById(Long batchId);

    Optional<ZhonghuiPmsUploadBatchItem> findByBatchIdAndSkuId(Long batchId, Long skuId);
}
