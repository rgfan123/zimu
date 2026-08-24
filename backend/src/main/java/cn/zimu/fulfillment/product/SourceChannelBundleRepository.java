package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceChannelBundleRepository extends JpaRepository<SourceChannelBundle, Long> {

    boolean existsBySourceChannelAndSourceBundleRef(SourceChannel sourceChannel, String sourceBundleRef);

    boolean existsBySourceChannelAndSourceBarcode(SourceChannel sourceChannel, String sourceBarcode);

    Page<SourceChannelBundle> findBySourceChannel(SourceChannel sourceChannel, Pageable pageable);
}
