package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceChannelSkuRepository extends JpaRepository<SourceChannelSku, Long> {

    Optional<SourceChannelSku> findBySourceChannelAndSourceSkuRef(
            SourceChannel sourceChannel, String sourceSkuRef);

    boolean existsBySourceChannelAndSourceSkuRef(SourceChannel sourceChannel, String sourceSkuRef);

    Page<SourceChannelSku> findBySourceChannel(SourceChannel sourceChannel, Pageable pageable);
}
