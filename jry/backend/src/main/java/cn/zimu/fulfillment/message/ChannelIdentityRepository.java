package cn.zimu.fulfillment.message;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelIdentityRepository extends JpaRepository<ChannelIdentity, Long> {

    Optional<ChannelIdentity> findByCorpIdAndAccessTypeAndChannelIdentity(
            String corpId, String accessType, String channelIdentity);

    List<ChannelIdentity> findByCustomerId(Long customerId);
}
