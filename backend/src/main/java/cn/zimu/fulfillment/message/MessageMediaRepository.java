package cn.zimu.fulfillment.message;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageMediaRepository extends JpaRepository<MessageMedia, Long> {

    Optional<MessageMedia> findByChannelMessageIdAndChannelMediaId(
            Long channelMessageId, String channelMediaId);

    List<MessageMedia> findByChannelMessageIdOrderByIdAsc(Long channelMessageId);
}
