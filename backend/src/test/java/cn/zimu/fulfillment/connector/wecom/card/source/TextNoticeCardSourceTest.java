package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TextNoticeCardSourceTest {

    @Test
    void shipmentResultDoesNotReadFactsOrScanWhenTextNoticeDeepLinkIsUnavailable() {
        // 不配置 DataSource：若 guard 之后仍访问数据库，本测试会立即失败。
        JdbcTemplate jdbc = new JdbcTemplate();
        ShipmentResultCardSource source = new ShipmentResultCardSource(
                jdbc, new WecomBusinessCardRouteProperties(), new CardDeepLinks(""));

        assertThat(source.render(4, 1)).isEmpty();
        assertThat(source.pending(OffsetDateTime.now().minusHours(1), 20)).isEmpty();
    }
}
