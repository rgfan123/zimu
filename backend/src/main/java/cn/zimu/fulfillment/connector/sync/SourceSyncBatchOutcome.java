package cn.zimu.fulfillment.connector.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 批量来源回传结果；每行独立成功或失败，绝不以整批回滚折叠事实。 */
public record SourceSyncBatchOutcome(
        List<Item> items,
        int successCount,
        int failureCount) {

    public SourceSyncBatchOutcome(List<Item> items) {
        this(
                List.copyOf(items),
                (int) items.stream().filter(Item::success).count(),
                (int) items.stream().filter(item -> !item.success()).count());
    }

    public record Item(
            long shipmentId,
            boolean success,
            boolean replayed,
            int httpStatus,
            String businessCode,
            String message,
            SourceSyncOutcome outcome,
            JsonNode replayedBody) {}
}
