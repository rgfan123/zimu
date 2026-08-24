package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 渠道→单 Shipment 外部写产物工厂；无二进制产物的 API Connector 返回 empty。 */
@Component
public final class SourceShipmentArtifactRegistry {

    private final Map<SourceChannel, SourceShipmentArtifactFactory> factories;

    public SourceShipmentArtifactRegistry(List<SourceShipmentArtifactFactory> candidates) {
        EnumMap<SourceChannel, SourceShipmentArtifactFactory> collected = new EnumMap<>(SourceChannel.class);
        for (SourceShipmentArtifactFactory candidate : candidates) {
            SourceShipmentArtifactFactory previous = collected.putIfAbsent(candidate.channel(), candidate);
            if (previous != null) {
                throw new IllegalStateException("重复的 SourceShipmentArtifactFactory channel: " + candidate.channel());
            }
        }
        factories = Map.copyOf(collected);
    }

    public SourceShipmentArtifact prepare(SourceSyncFacts facts) {
        SourceShipmentArtifactFactory factory = factories.get(facts.sourceChannel());
        return factory == null ? SourceShipmentArtifact.empty() : factory.prepare(facts);
    }
}
