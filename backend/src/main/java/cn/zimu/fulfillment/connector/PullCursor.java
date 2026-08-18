package cn.zimu.fulfillment.connector;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 在线拉取游标（ticket 01，签名见设计文档 §4.1）。
 *
 * <p>watermark 与 pageToken 分离：watermark 是上次成功拉取的位点（增量语义，
 * 各平台自有，见各 Connector 票）；pageToken 是单次分页游标（聚福宝
 * next_page_token，首页 "1"/null）。</p>
 */
public record PullCursor(
        String watermark,
        String pageToken,
        OffsetDateTime since,
        OffsetDateTime until,
        Map<String, String> extra) {

    public static PullCursor initial(OffsetDateTime since, OffsetDateTime until) {
        return new PullCursor(null, null, since, until, Map.of());
    }

    public PullCursor withPageToken(String nextPageToken) {
        return new PullCursor(watermark, nextPageToken, since, until, extra);
    }

    public PullCursor withWatermark(String newWatermark) {
        return new PullCursor(newWatermark, null, since, until, extra);
    }
}
