package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 拉取结果（ticket 01，签名见设计文档 §4.1）。 */
public record PullResult(
        SourceChannel channel,
        List<SourceOrderEnvelope> orders,
        String nextCursor,
        int pulledCount,
        OffsetDateTime pulledAt,
        PullStatus status,
        String businessCode,
        String message,
        ImportBatchReference importBatch) {

    /** 在不丢失现有调用方兼容性的前提下，保留无导入批次的拉取结果构造式。 */
    public PullResult(
            SourceChannel channel,
            List<SourceOrderEnvelope> orders,
            String nextCursor,
            int pulledCount,
            OffsetDateTime pulledAt,
            PullStatus status,
            String businessCode,
            String message) {
        this(channel, orders, nextCursor, pulledCount, pulledAt, status, businessCode, message, null);
    }

    /** 拉取后已生成的导入批次；供刷新界面进入人工整批确认，不代表已确认。 */
    public record ImportBatchReference(String id, String batchNo, Map<String, Object> rowCounts) {
        public ImportBatchReference {
            rowCounts = rowCounts == null ? Map.of() : Map.copyOf(rowCounts);
        }
    }

    public enum PullStatus {
        OK, PARTIAL, EMPTY, FAILED, CAPABILITY_UNAVAILABLE
    }

    public static PullResult unavailable(SourceChannel channel) {
        return new PullResult(channel, List.of(), null, 0, OffsetDateTime.now(),
                PullStatus.CAPABILITY_UNAVAILABLE, "CONNECTOR_CAPABILITY_UNAVAILABLE",
                "该渠道在线拉取尚未接入");
    }

    public static PullResult empty(SourceChannel channel, String nextCursor) {
        return new PullResult(channel, List.of(), nextCursor, 0, OffsetDateTime.now(),
                PullStatus.EMPTY, "OK", "无新数据");
    }

    public boolean ok() {
        return status == PullStatus.OK || status == PullStatus.EMPTY;
    }
}
