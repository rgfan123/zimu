package cn.zimu.fulfillment.connector.jufubao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 聚福宝供应商后台单订单发货 HTTP 适配器。 */
@Component
public final class JufubaoHttpShipmentGateway implements JufubaoShipmentGateway {

    private static final String ORDER_QUERY_PATH = "/order-supplier/v1/orders/query";
    private static final String SHIPMENT_DETAIL_PATH = "/order-supplier/v1/logistics/sub-order-info";
    private static final String CARRIER_OPTIONS_PATH = "/order-public/v1/logistics-company/options";
    private static final String SHIPMENT_SUBMIT_PATH = "/order-supplier/v1/logistics/sub-order-send";
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 100;

    private final JufubaoSessionAdapter session;
    private final ObjectMapper mapper;

    public JufubaoHttpShipmentGateway(JufubaoSessionAdapter session, ObjectMapper mapper) {
        this.session = session;
        this.mapper = mapper;
    }

    @Override
    public void prepareWrite() {
        session.prepareWrite();
    }

    @Override
    public OrderState findOrder(String subOrderId) {
        String pageToken = "1";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tab", "no_delivery");
            body.put("filter", Map.of());
            body.put("page_token", pageToken);
            body.put("page_size", PAGE_SIZE);
            body.put("system", "supplier");
            JsonNode root = postJson(ORDER_QUERY_PATH, body);
            JsonNode list = root.path("list");
            if (!list.isArray()) {
                throw new JufubaoTransportException("订单查询响应缺少 list");
            }
            for (JsonNode order : list) {
                if (subOrderId.equals(order.path("sub_order_id").asText())) {
                    String status = order.path("order_status").asText("");
                    // 只要仍命中 no_delivery 列表就视为存在；状态字段仅供写前门禁，不可替代列表离开事实。
                    return new OrderState(subOrderId, status, true);
                }
            }
            String next = root.path("next_page_token").asText("");
            if (next.isBlank() || list.isEmpty()) {
                return OrderState.notPending(subOrderId);
            }
            pageToken = next;
        }
        throw new JufubaoTransportException("订单查询超过最大分页数");
    }

    @Override
    public ShipmentDetail shipmentDetail(String subOrderId) {
        String query = "?sub_order_id=" + encode(subOrderId) + "&system=supplier";
        JsonNode root = getJson(SHIPMENT_DETAIL_PATH + query);
        JsonNode payload = root.has("product_list") ? root : root.path("data");
        JsonNode productList = payload.path("product_list");
        if (!productList.isArray()) {
            throw new JufubaoTransportException("发货详情响应缺少 product_list");
        }
        List<ObjectNode> products = new ArrayList<>();
        for (JsonNode product : productList) {
            if (product instanceof ObjectNode object) {
                products.add(object.deepCopy());
            }
        }
        return new ShipmentDetail(products);
    }

    @Override
    public List<CarrierOption> carrierOptions() {
        JsonNode root = getJson(CARRIER_OPTIONS_PATH + "?system=supplier");
        JsonNode items = root.has("items") ? root.path("items") : root.path("data").path("items");
        if (!items.isArray()) {
            throw new JufubaoTransportException("物流公司字典响应缺少 items");
        }
        List<CarrierOption> options = new ArrayList<>();
        for (JsonNode item : items) {
            String label = item.path("label").asText("").trim();
            if (!label.isBlank() && item.path("value").canConvertToInt()) {
                options.add(new CarrierOption(label, item.path("value").asInt()));
            }
        }
        return List.copyOf(options);
    }

    @Override
    public SubmitResult submit(ShipmentCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_order_id", command.subOrderId());
        body.put("product_list_json", writeJson(command.products()));
        body.put("is_need_logistics", "Y");
        body.put("company_id", command.companyId());
        body.put("logistics_number", command.trackingNo());
        body.put("remarks", "");
        body.put("system", "supplier");

        HttpResponse<String> response;
        try {
            response = session.postWriteOnce(SHIPMENT_SUBMIT_PATH, writeJson(body));
        } catch (RuntimeException exception) {
            return SubmitResult.unknown("发货请求后未收到可确认响应", null);
        }
        if (response.statusCode() == 401) {
            return SubmitResult.unknown("发货请求返回未授权状态，无法确认平台是否产生效果", null);
        }
        JsonNode root;
        try {
            root = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
        } catch (Exception exception) {
            return SubmitResult.unknown("发货响应无法解析", null);
        }
        String platformRef = root.path("request_id").asText(null);
        String code = root.has("code") ? root.path("code").asText() : "";
        String message = root.path("message").asText("");
        if (response.statusCode() >= 500 || code.isBlank()) {
            return SubmitResult.unknown(message.isBlank() ? "聚福宝发货结果未知" : message, platformRef);
        }
        boolean accepted = code.equals("0") || code.equals("200");
        if (response.statusCode() == 400 || (response.statusCode() >= 200 && response.statusCode() < 300 && !accepted)) {
            return SubmitResult.rejected(code, message.isBlank() ? "聚福宝拒绝发货请求" : message, platformRef);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 只有抓包锁定的 HTTP 400 业务拒绝可安全释放幂等 claim；其余 3xx/4xx 结果保守对账。
            return SubmitResult.unknown(message.isBlank() ? "聚福宝发货结果未知" : message, platformRef);
        }
        return SubmitResult.accepted(platformRef);
    }

    private JsonNode postJson(String path, Object body) {
        HttpResponse<String> response = session.postJson(path, writeJson(body));
        return successfulJson(response, path);
    }

    private JsonNode getJson(String path) {
        HttpResponse<String> response = session.get(path);
        return successfulJson(response, path);
    }

    private JsonNode successfulJson(HttpResponse<String> response, String path) {
        if (response.statusCode() >= 400) {
            throw new JufubaoTransportException("聚福宝读请求失败: " + path);
        }
        try {
            return response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
        } catch (Exception exception) {
            throw new JufubaoTransportException("聚福宝读响应无法解析: " + path);
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new JufubaoTransportException("聚福宝请求无法序列化");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static final class JufubaoTransportException extends RuntimeException {
        JufubaoTransportException(String message) {
            super(message, null, false, false);
        }
    }
}
