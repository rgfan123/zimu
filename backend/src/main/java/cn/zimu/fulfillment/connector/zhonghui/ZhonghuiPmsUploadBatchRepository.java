package cn.zimu.fulfillment.connector.zhonghui;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ZhonghuiPmsUploadBatchRepository extends JpaRepository<ZhonghuiPmsUploadBatch, Long> {

    /** 批次号原子流水（V35 序列），保证唯一性。 */
    @Query(value = "SELECT nextval('app.zhonghui_pms_upload_batch_no_seq')", nativeQuery = true)
    long nextBatchNo();
}
