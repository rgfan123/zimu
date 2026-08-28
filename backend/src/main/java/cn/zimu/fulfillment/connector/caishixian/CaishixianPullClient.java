package cn.zimu.fulfillment.connector.caishixian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 彩食鲜「待发货订单」在线拉取客户端（JSON 直连版）。
 *
 * <p>契约见 {@code docs/research/caishixian-scc-wapi-export-api.md}（2026-08-18 抓包实测）：
 * <ol>
 *   <li>登录：{@code POST /ucenter/login/scc}，body {@code {username,password,businessCode:"fe-web-scc"}}，
 *       登录成功后在<b>响应头</b>返回新 JWT（自定义头 {@code login-token}）；</li>
 *   <li>订单分页列表（§4.4）：{@code POST /scc/bbc/order/orderList}，body
 *       {@code {payTimeBegin,payTimeEnd,pageNum,pageSize,orderStatus:"3"}}，响应 data 含
 *       {@code pageNum/pageSize/totalNum/data[]/number{waitDepotNum,...}}；</li>
 *   <li>订单详情（§4.5）：{@code GET /scc/bbc/order/detail?id=<orderList.id>}，补收货地址
 *       （receiverProvince/City/District/Address）、商品明细（supplierOrderGoodsVo[]）等。</li>
 * </ol>
 *
 * <p>旧「导出任务」链路（exportDeliverExcl → 轮询 → 下载 xlsx）已整体移除：它把
 * {@code pageSize:10} 写死在导出参数里（超 10 单静默截断），且窗口是否生效在导出文件里
 * 完全不可观测。JSON 直连按 {@code totalNum} 真翻页取完，并把平台自报的
 * {@code number.waitDepotNum} 一起带回做拉取对账；导出通道的人工兜底仍然存在——
 * 平台后台手工导出的 xlsx 走既有文件上传导入路径，本类不再承载。</p>
 *
 * <p>业务请求必带头 {@code login-token: <JWT>} 与 {@code supplier-code: <供应商代码>}。
 * 凭据只走环境变量 {@code CSX_USERNAME} / {@code CSX_PASSWORD} / {@code CSX_SUPPLIER_CODE}，
 * 绝不落盘、不打日志。</p>
 *
 * <p>本接口是 Connector 与真实 HTTP 之间的可注入缝：生产用 {@link Http}（JDK HttpClient，
 * 无外部依赖），单元测试用 mock/stub，避免真实网络与合规红线。</p>
 */
public interface CaishixianPullClient {

    /** 登录结果；token 仅在 ok 时非空。businessCode 复用业务码（CREDENTIALS_REQUIRED / PLATFORM_AUTH_FAILED / OK）。 */
    record LoginResult(boolean ok, String businessCode, String message, String token) {
        public static LoginResult failed(String businessCode, String message) {
            return new LoginResult(false, businessCode, message, null);
        }
    }

    /** 登录（内部读取环境变量凭据；未配置/失败返回失败结果而非抛异常）。 */
    LoginResult login();

    /**
     * 订单列表单页快照（§4.4 实测响应 data 的白名单投影）。
     *
     * @param pageNum      平台回显的页码
     * @param totalNum     平台自报的窗口内订单总数（翻页终止条件的唯一权威）
     * @param orders       本页订单对象（data.data 原样 JsonNode，只读）
     * @param waitDepotNum 平台自报「当前待发货单数」（number.waitDepotNum）；平台没报时为
     *                     null（如实缺失，不造 0）——它是「窗口过滤是否吞单」的对账王牌
     * @param statusCounts number 里的全部整数计数（对账证据快照，键名与平台一致）
     */
    record OrderPage(
            int pageNum,
            int totalNum,
            List<JsonNode> orders,
            Integer waitDepotNum,
            Map<String, Integer> statusCounts) {
        public OrderPage {
            orders = orders == null ? List.of() : List.copyOf(orders);
            statusCounts = statusCounts == null ? Map.of() : Map.copyOf(statusCounts);
        }
    }

    /**
     * 拉取订单列表单页（POST /scc/bbc/order/orderList）。
     *
     * @param token    登录返回的 login-token
     * @param payBegin 支付开始日期 yyyy-MM-dd
     * @param payEnd   支付结束日期 yyyy-MM-dd（含）
     * @param pageNum  页码（从 1 起）
     * @param pageSize 页大小（与抓包观测一致用 10；翻页取完不依赖页大小）
     * @throws PullTransportException 传输/业务失败
     */
    OrderPage pullOrderPage(String token, String payBegin, String payEnd, int pageNum, int pageSize);

    /**
     * 拉取订单详情（GET /scc/bbc/order/detail?id=）。
     *
     * @param platformOrderId orderList 行的 {@code id}（平台内部主键，与 orderCode/orderKey
     *                        是三个不同身份，绝不混用）
     * @return 详情 data 节点
     * @throws PullTransportException 传输/业务失败（调用方按单降级，不废整批）
     */
    JsonNode pullOrderDetail(String token, String platformOrderId);

    /** 彩食鲜拉取传输/业务失败；消息不携带任何密钥或请求体。 */
    class PullTransportException extends RuntimeException {
        public PullTransportException(String message) {
            super(message, null, false, false);
        }

        public PullTransportException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** 生产实现：JDK {@link HttpClient}，无外部依赖。 */
    @Component("caishixianPullClient")
    class Http implements CaishixianPullClient {

        private static final Logger log = LoggerFactory.getLogger(Http.class);

        private static final String BASE_URL = "https://wapi.freshfood.cn";
        private static final String ORIGIN = "https://scc.freshfood.cn";
        private static final String AUTH_HEADER = "login-token";
        private static final String SUPPLIER_CODE_HEADER = "supplier-code";
        private static final String BUSINESS_CODE = "fe-web-scc";
        private static final String DEFAULT_SUPPLIER_CODE = "20075684";
        /** 订单状态筛选：3=待发货（抓包实测；语义基于单次观测，拉回的行会带 orderStatus 交叉验证）。 */
        private static final String ORDER_STATUS_WAIT_DEPOT = "3";
        private static final String RESULT_CODE_OK = "200000";
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

        private final HttpClient client;
        private final ObjectMapper mapper;

        public Http() {
            this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
        }

        /** 测试入口：注入 HttpClient 与 ObjectMapper（避免测试真连）。 */
        Http(HttpClient client, ObjectMapper mapper) {
            this.client = client;
            this.mapper = mapper;
        }

        @Override
        public LoginResult login() {
            String username = env("CSX_USERNAME");
            String password = env("CSX_PASSWORD");
            if (isBlank(username) || isBlank(password)) {
                return LoginResult.failed("CREDENTIALS_REQUIRED", "彩食鲜凭据未配置（CSX_USERNAME/CSX_PASSWORD）");
            }
            try {
                Map<String, Object> body = Map.of(
                        "username", username,
                        "password", password,
                        "businessCode", BUSINESS_CODE);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/ucenter/login/scc"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, */*")
                        .header("Origin", ORIGIN)
                        .header("Referer", ORIGIN + "/")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = parse(response.body());
                if (!codeOk(root)) {
                    return LoginResult.failed("PLATFORM_AUTH_FAILED",
                            "登录失败: " + messageOf(root, "HTTP " + response.statusCode()));
                }
                String token = response.headers().firstValue(AUTH_HEADER).orElse("");
                if (token.isBlank()) {
                    return LoginResult.failed("PLATFORM_AUTH_FAILED", "登录成功但响应头缺少 login-token");
                }
                return new LoginResult(true, "OK", "登录成功", token);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "彩食鲜登录被中断");
            } catch (Exception exception) {
                log.warn("彩食鲜登录失败", exception);
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "彩食鲜登录失败: " + safeMessage(exception));
            }
        }

        @Override
        public OrderPage pullOrderPage(String token, String payBegin, String payEnd, int pageNum, int pageSize) {
            // 与 §4.4 实测请求体逐字段一致；键序用 LinkedHashMap 固定，方便测试桩逐字节断言。
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("payTimeBegin", payBegin);
            body.put("payTimeEnd", payEnd);
            body.put("pageNum", pageNum);
            body.put("pageSize", pageSize);
            body.put("orderStatus", ORDER_STATUS_WAIT_DEPOT);
            JsonNode root = postJson("/scc/bbc/order/orderList", token, body);
            if (!codeOk(root)) {
                throw new PullTransportException("orderList 第 " + pageNum + " 页失败: " + messageOf(root, "响应异常"));
            }
            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw new PullTransportException("orderList 第 " + pageNum + " 页响应缺少 data 对象");
            }
            List<JsonNode> orders = new ArrayList<>();
            if (data.path("data").isArray()) {
                data.path("data").forEach(orders::add);
            }
            JsonNode number = data.path("number");
            Integer waitDepotNum = number.path("waitDepotNum").isNumber()
                    ? number.path("waitDepotNum").asInt()
                    : null;
            Map<String, Integer> statusCounts = new LinkedHashMap<>();
            if (number.isObject()) {
                number.properties().forEach(entry -> {
                    if (entry.getValue().isNumber()) {
                        statusCounts.put(entry.getKey(), entry.getValue().asInt());
                    }
                });
            }
            return new OrderPage(
                    data.path("pageNum").asInt(pageNum),
                    data.path("totalNum").asInt(0),
                    orders,
                    waitDepotNum,
                    statusCounts);
        }

        @Override
        public JsonNode pullOrderDetail(String token, String platformOrderId) {
            JsonNode root = getJson(
                    "/scc/bbc/order/detail?id=" + encode(platformOrderId), token);
            if (!codeOk(root) || !root.path("data").isObject()) {
                throw new PullTransportException("orderDetail 失败: " + messageOf(root, "响应异常"));
            }
            return root.path("data");
        }

        // ---------------------------------------------------------------- 传输工具

        private JsonNode getJson(String pathAndQuery, String token) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + pathAndQuery))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json, */*")
                    .header("Origin", ORIGIN)
                    .header("Referer", ORIGIN + "/")
                    .header(AUTH_HEADER, token)
                    .header(SUPPLIER_CODE_HEADER, supplierCode())
                    .GET()
                    .build();
            return parse(sendText(request));
        }

        private JsonNode postJson(String path, String token, Map<String, Object> body) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, */*")
                        .header("Origin", ORIGIN)
                        .header("Referer", ORIGIN + "/")
                        .header(AUTH_HEADER, token)
                        .header(SUPPLIER_CODE_HEADER, supplierCode())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                return parse(sendText(request));
            } catch (PullTransportException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new PullTransportException("请求构造失败", exception);
            }
        }

        private String sendText(HttpRequest request) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new PullTransportException("接口返回 HTTP " + response.statusCode());
                }
                return response.body();
            } catch (PullTransportException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("彩食鲜请求被中断");
            } catch (Exception exception) {
                throw new PullTransportException("彩食鲜请求失败: " + safeMessage(exception), exception);
            }
        }

        private JsonNode parse(String body) {
            try {
                return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            } catch (Exception exception) {
                throw new PullTransportException("彩食鲜响应解析失败");
            }
        }

        private boolean codeOk(JsonNode root) {
            return root != null && RESULT_CODE_OK.equals(root.path("code").asText(""));
        }

        private String messageOf(JsonNode root, String fallback) {
            if (root == null) {
                return fallback;
            }
            String message = root.path("message").asText("");
            return message.isBlank() ? fallback : message;
        }

        private String supplierCode() {
            String code = env("CSX_SUPPLIER_CODE");
            return isBlank(code) ? DEFAULT_SUPPLIER_CODE : code;
        }

        private static String env(String name) {
            String value = System.getenv(name);
            return value == null ? "" : value.trim();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String safeMessage(Throwable exception) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
