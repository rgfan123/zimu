package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 平台原始订单信封（ticket 01，签名见设计文档 §4.1）。
 *
 * <p>raw 为平台原始字段快照（审计与复核证据，入库前脱敏）；draft 为
 * transform 产物（CanonicalOrderInput），直接喂应用层用例。</p>
 */
public record SourceOrderEnvelope(
        SourceChannel channel,
        String sourceRef,
        String sourceLineRef,
        String sourceVersion,
        OffsetDateTime orderedAt,
        Map<String, Object> raw,
        CanonicalOrderInput draft) {
}
