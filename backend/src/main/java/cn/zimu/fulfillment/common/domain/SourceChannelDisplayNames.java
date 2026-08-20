package cn.zimu.fulfillment.common.domain;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.Map;

/** 来源渠道内部技术键与中文业务显示名的唯一转换边界。 */
public final class SourceChannelDisplayNames {

    private static final Map<SourceChannel, String> NAMES = Map.of(
            SourceChannel.CAISHIXIAN, "彩食鲜",
            SourceChannel.DAZHE, "大者",
            SourceChannel.JUFUBAO, "聚福宝",
            SourceChannel.FEIXIANG, "飞象",
            SourceChannel.ZHONGHUI, "中汇",
            SourceChannel.WANGQI, "大者",
            SourceChannel.WANQI, "万齐",
            SourceChannel.WECOM, "企业微信");

    private SourceChannelDisplayNames() {}

    public static String displayName(String technicalKey) {
        if (technicalKey == null) {
            return null;
        }
        return displayName(SourceChannel.valueOf(technicalKey));
    }

    public static String displayName(SourceChannel channel) {
        return NAMES.get(channel);
    }

    public static SourceChannel fromDisplayName(String displayName) {
        return switch (displayName) {
            case "大者" -> SourceChannel.DAZHE;
            case "万齐" -> SourceChannel.WANQI;
            default -> throw BusinessException.unprocessable(
                    "SOURCE_ATTRIBUTION_CHANNEL_UNSUPPORTED", "当前不支持该来源渠道归因");
        };
    }
}
