package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.PlatformConnector;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 唯一的 source-sync 渠道解析表；重复 channel 在启动期失败，禁止 silent last-wins。 */
@Component
public final class PlatformConnectorRegistry {

    private final Map<SourceChannel, PlatformConnector> connectors;

    public PlatformConnectorRegistry(List<PlatformConnector> candidates) {
        EnumMap<SourceChannel, PlatformConnector> collected = new EnumMap<>(SourceChannel.class);
        for (PlatformConnector candidate : candidates) {
            PlatformConnector previous = collected.putIfAbsent(candidate.channel(), candidate);
            if (previous != null) {
                throw new IllegalStateException("重复的 PlatformConnector channel: " + candidate.channel());
            }
        }
        this.connectors = Map.copyOf(collected);
    }

    public PlatformConnector require(SourceChannel channel) {
        PlatformConnector connector = connectors.get(channel);
        if (connector == null) {
            throw BusinessException.unprocessable(
                    "SOURCE_SYNC_CONNECTOR_UNAVAILABLE", "来源渠道没有可用 Connector: " + channel);
        }
        return connector;
    }
}
