package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WecomTrackingFileDraftCountContractTest {

    @Test
    void shipmentDraftTotalUsesInt64AcrossValidInt32Items() {
        var first = row(Integer.MAX_VALUE);
        var second = row(Integer.MAX_VALUE);

        assertThat(WecomTrackingFileDraftService.totalShippedQuantity(List.of(first, second)))
                .isEqualTo(4_294_967_294L);
    }

    private TrackingFileService.ParsedTrackingRow row(int shippedQuantity) {
        return new TrackingFileService.ParsedTrackingRow(
                1, 1L, 1L, 1L, 1L, "收货人", "SHIPPED", shippedQuantity,
                "JD", "京东物流", "JDVA-COUNT", null, shippedQuantity, Map.of());
    }
}
