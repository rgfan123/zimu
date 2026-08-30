package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.zimu.fulfillment.connector.wecom.card.PreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource.RouteType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ShipmentResultCardSourceTest {

    @Test
    void 非单聊路由在attachments内再次拦截() {
        WecomBusinessCardRouteProperties properties = new WecomBusinessCardRouteProperties();
        WecomBusinessCardRouteProperties.Route group = new WecomBusinessCardRouteProperties.Route();
        group.setType(RouteType.GROUP);
        group.setChatId("wr-group");
        properties.setRoutes(Map.of(PreShipConfirmCard.DOMAIN, group));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ShipmentResultCardSource source = new ShipmentResultCardSource(
                jdbc,
                properties,
                mock(CardDeepLinks.class),
                new PendingListImageRenderer(),
                new ObjectMapper());

        assertThat(source.attachments(1L, 0L)).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void 超限后最后一行明写已截断() {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i <= ShipmentResultCardSource.RAW_TABLE_ROW_LIMIT; i++) {
            rows.add(new String[] {"字段" + i, "值" + i});
        }

        List<String[]> limited = ShipmentResultCardSource.limitRawTableRows(rows);

        assertThat(limited).hasSize(ShipmentResultCardSource.RAW_TABLE_ROW_LIMIT);
        assertThat(limited.getLast()[0]).isEqualTo(ShipmentResultCardSource.TRUNCATED_NOTICE);
        assertThat(limited.getLast()[0]).isEqualTo("已截断，完整数据见系统");
    }
}
