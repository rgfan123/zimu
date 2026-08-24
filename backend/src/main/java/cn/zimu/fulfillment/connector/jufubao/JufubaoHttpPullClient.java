package cn.zimu.fulfillment.connector.jufubao;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 聚福宝待发货订单 HTTP 读取适配器。 */
@Component
public final class JufubaoHttpPullClient implements JufubaoPullClient {

    private static final String ORDERS_QUERY_PATH = "/order-supplier/v1/orders/query";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 100;
    private static final TypeReference<Map<String, Object>> ORDER_MAP_TYPE = new TypeReference<>() {};

    private final JufubaoSessionAdapter session;
    private final ObjectMapper mapper;

    public JufubaoHttpPullClient(JufubaoSessionAdapter session, ObjectMapper mapper) {
        this.session = session;
        this.mapper = mapper;
    }

    @Override
    public LoginResult login() {
        return session.login();
    }

    @Override
    public List<Map<String, Object>> pullOrders(long startEpoch, long endEpoch) {
        List<Map<String, Object>> orders = new ArrayList<>();
        String pageToken = "1";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tab", "no_delivery");
            body.put("filter", Map.of(
                    "created_time_range",
                    Map.of("start_time", startEpoch, "end_time", endEpoch)));
            body.put("page_token", pageToken);
            body.put("page_size", PAGE_SIZE);
            body.put("system", "supplier");

            HttpResponse<String> response = session.postJson(ORDERS_QUERY_PATH, writeJson(body));
            if (response.statusCode() >= 400) {
                throw new PullTransportException("订单查询接口返回 HTTP " + response.statusCode());
            }
            JsonNode root = readJson(response.body());
            JsonNode list = root.path("list");
            if (!list.isArray()) {
                throw new PullTransportException("orders/query 异常响应（缺少 list 字段）");
            }
            for (JsonNode item : list) {
                if (item.isObject()) {
                    orders.add(mapper.convertValue(item, ORDER_MAP_TYPE));
                }
            }
            String next = root.path("next_page_token").asText("");
            if (next.isBlank() || list.isEmpty()) {
                return List.copyOf(orders);
            }
            pageToken = next;
        }
        throw new PullTransportException("订单查询超过最大分页数");
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new PullTransportException("聚福宝请求无法序列化");
        }
    }

    private JsonNode readJson(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception exception) {
            throw new PullTransportException("聚福宝响应解析失败");
        }
    }
}
