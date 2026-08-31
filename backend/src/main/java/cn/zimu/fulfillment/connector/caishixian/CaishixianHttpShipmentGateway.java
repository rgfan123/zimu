package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.ExternalWritePermit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 彩食鲜 Shipment 查询、multipart 上传与写后终态核验的 JDK HTTP Adapter。 */
@Component
public class CaishixianHttpShipmentGateway implements CaishixianShipmentGateway {

    private static final Logger log = LoggerFactory.getLogger(CaishixianHttpShipmentGateway.class);
    private static final String AUTH_HEADER = "login-token";
    private static final String OFFICIAL_BASE_URL = "https://wapi.freshfood.cn:443";
    private static final String SUPPLIER_HEADER = "supplier-code";
    private static final String DEFAULT_SUPPLIER_CODE = "20075684";
    private static final String ORIGIN = "https://scc.freshfood.cn";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final List<Integer> QUERY_STATUSES = List.of(3, 4, 5, 11, 6);

    private final CaishixianPullClient pullClient;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final int verificationAttempts;
    private final Duration verificationDelay;

    @Autowired
    public CaishixianHttpShipmentGateway(
            CaishixianPullClient pullClient,
            ObjectMapper mapper,
            @Value("${app.caishixian.verify-attempts:5}") int verificationAttempts,
            @Value("${app.caishixian.verify-delay:PT2S}") Duration verificationDelay) {
        this(
                pullClient,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                mapper,
                OFFICIAL_BASE_URL,
                verificationAttempts,
                verificationDelay);
    }

    CaishixianHttpShipmentGateway(
            CaishixianPullClient pullClient,
            HttpClient http,
            ObjectMapper mapper,
            String baseUrl,
            int verificationAttempts,
            Duration verificationDelay) {
        this.pullClient = pullClient;
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = validatedBaseUrl(baseUrl);
        this.verificationAttempts = Math.max(1, Math.min(20, verificationAttempts));
        this.verificationDelay = verificationDelay == null || verificationDelay.isNegative()
                ? Duration.ZERO
                : verificationDelay;
    }

    @Override
    public PlatformOrderSnapshot inspect(String sourceRef, String sourceLineRef) {
        Session session = login();
        for (int status : QUERY_STATUSES) {
            JsonNode order = findOrder(session, sourceRef, sourceLineRef, status);
            if (order == null) {
                continue;
            }
            String id = text(order, "id");
            JsonNode detail = id == null ? mapper.createObjectNode() : detail(session, id);
            return snapshot(order, detail, status);
        }
        return new PlatformOrderSnapshot(
                false, null, sourceRef, sourceLineRef, -1, "未在彩食鲜订单列表找到目标",
                null, null, null, null);
    }

    /**
     * 2026-08-18 实测的平台快递字典（27 码）。2026-08-31 生产实证：getExpress 开始要求
     * 未知的新参数（110511000「查询供应商快递信息,参数不能为空」，穷举常见参数名均被拒），
     * 每次检查都在这一步炸掉，把 11 单全部卡成 CHECK_UNAVAILABLE。快递代码字典属慢变
     * 参考数据：先试活接口（平台修好即自动恢复），失败回退本静态字典并 WARN——
     * 「承运商代码必须是平台认识的」这层保证由实测过的清单继续兜住，绝不静默放行未知码。
     */
    private static final List<CarrierOption> EXPRESS_FALLBACK_20260818 = List.of(
            new CarrierOption("YTO", "圆通速递"), new CarrierOption("JD", "京东物流"),
            new CarrierOption("SF", "顺丰速运"), new CarrierOption("HTKY", "百世快递"),
            new CarrierOption("ZTO", "中通快递"), new CarrierOption("STO", "申通快递"),
            new CarrierOption("YD", "韵达速递"), new CarrierOption("YZPY", "邮政快递包裹"),
            new CarrierOption("EMS", "EMS"), new CarrierOption("HHTT", "天天快递"),
            new CarrierOption("UC", "优速快递"), new CarrierOption("DBL", "德邦快递"),
            new CarrierOption("ZJS", "宅急送"), new CarrierOption("TNT", "TNT"),
            new CarrierOption("UPS", "UPS"), new CarrierOption("DHL", "DHL"),
            new CarrierOption("FEDEX", "FEDEX"), new CarrierOption("FEDEX_GJ", "FEDEX 国际"),
            new CarrierOption("JTSD", "极兔速递"), new CarrierOption("ZYE", "众邮快递"),
            new CarrierOption("ANE", "安能物流"), new CarrierOption("ANNTO", "安得物流"),
            new CarrierOption("OTHER", "其他"), new CarrierOption("FWX", "丰网速运"),
            new CarrierOption("KYSY", "跨越速运"), new CarrierOption("DNWL", "丹鸟物流"),
            new CarrierOption("YMDD", "壹米滴答"));

    @Override
    public List<CarrierOption> carrierOptions() {
        try {
            Session session = login();
            JsonNode root = postJson(session, "/scc/bbc/basicData/getExpress", Map.of());
            JsonNode data = root.path("data");
            if (data.isObject() && data.path("data").isArray()) {
                data = data.path("data");
            }
            if (!data.isArray()) {
                throw new GatewayException("彩食鲜物流公司字典响应缺少数组");
            }
            List<CarrierOption> options = new ArrayList<>();
            for (JsonNode item : data) {
                String code = firstText(item, "expressCode", "code", "shipperCode", "value");
                String name = firstText(item, "expressName", "name", "shipperName", "label");
                if (code != null && name != null) {
                    options.add(new CarrierOption(code, name));
                }
            }
            if (options.isEmpty()) {
                throw new GatewayException("彩食鲜物流公司字典为空");
            }
            return List.copyOf(options);
        } catch (GatewayException exception) {
            log.warn(
                    "彩食鲜快递字典接口不可用，回退 2026-08-18 静态字典（27 码）: {}",
                    exception.getMessage());
            return EXPRESS_FALLBACK_20260818;
        }
    }

    @Override
    public UploadAck upload(SourceShipmentArtifact artifact, ExternalWritePermit permit) {
        Objects.requireNonNull(permit, "彩食鲜外部写许可不能为空");
        Session session;
        try {
            session = login();
        } catch (RuntimeException exception) {
            return UploadAck.rejected("CAISHIXIAN_AUTH_FAILED", "彩食鲜登录失败，未上传回填文件");
        }
        String boundary = "----zimu-source-sync-" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, artifact);
        HttpRequest request = request(session, "/scc/bbc/order/importDeliverExcl")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(REQUEST_TIMEOUT.multipliedBy(2))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        // 登录、multipart 组装和请求构造均已完成；租约检查紧贴不可逆 HTTP send。
        permit.beforeExternalWrite();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            rotateToken(session, response);
            JsonNode root = parse(response.body());
            String code = root.path("code").asText("");
            if (response.statusCode() >= 500 || code.isBlank()) {
                return UploadAck.unknown("彩食鲜上传响应无法确认");
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return UploadAck.rejected("CAISHIXIAN_AUTH_FAILED", "彩食鲜鉴权拒绝上传");
            }
            if ("110511000".equals(code)) {
                return UploadAck.rejected(safeCode(code), safeMessage(root.path("message").asText("平台拒绝上传")));
            }
            if (response.statusCode() >= 400 || !codeOk(root)) {
                return new UploadAck(
                        UploadAck.Outcome.UNKNOWN,
                        safeCode(code),
                        "彩食鲜返回未验证业务码，必须查询订单终态");
            }
            return UploadAck.accepted(code);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return UploadAck.unknown("彩食鲜上传被中断");
        } catch (Exception exception) {
            return UploadAck.unknown("彩食鲜上传传输结果未知");
        }
    }

    @Override
    public Verification awaitVerified(String platformOrderId, String carrierCode, String trackingNumber) {
        Session session = login();
        PlatformOrderSnapshot last = null;
        for (int attempt = 1; attempt <= verificationAttempts; attempt++) {
            JsonNode detail = detail(session, platformOrderId);
            int status = detail.path("orderStatus").asInt(-1);
            boolean trackingMatched = packages(detail).stream().anyMatch(pack ->
                    carrierCode.equals(pack.carrierCode()) && trackingNumber.equals(pack.trackingNumber()));
            if (status == 4 && trackingMatched) {
                return Verification.verified(platformOrderId);
            }
            last = new PlatformOrderSnapshot(
                    true, platformOrderId, text(detail, "orderCode"), text(detail, "orderKey"), status,
                    text(detail, "orderStatusEnumName"), null, null, null, null);
            if (status == 6 || status == 11) {
                break;
            }
            if (attempt < verificationAttempts) {
                sleep(verificationDelay);
            }
        }
        int status = last == null ? -1 : last.orderStatus();
        return Verification.notVerified(
                platformOrderId,
                status,
                status == 3
                        ? "彩食鲜上传后目标订单仍为待发货"
                        : "彩食鲜写后状态或正式运单未能一致核验");
    }

    private PlatformOrderSnapshot snapshot(JsonNode order, JsonNode detail, int status) {
        int currentStatus = detail.path("orderStatus").isNumber()
                ? detail.path("orderStatus").asInt()
                : status;
        String currentStatusName = firstText(detail, "orderStatusEnumName", "orderStatusName");
        if (currentStatusName == null) currentStatusName = firstText(order, "orderStatusEnumName", "orderStatusName");
        String name = firstText(detail, "receiverName");
        if (name == null) name = firstText(order, "receiverName");
        String phone = firstText(detail, "receiverTelephone", "receiverPhone");
        if (phone == null) phone = firstText(order, "receiverTelephone", "receiverPhone");
        String address = joinAddress(detail);
        BigDecimal sendable = BigDecimal.ZERO;
        JsonNode products = detail.path("supplierOrderGoodsVo");
        if (!products.isArray()) {
            sendable = null;
        } else {
            for (JsonNode product : products) {
                BigDecimal count = decimal(product.path("count"));
                BigDecimal out = decimal(product.path("outCount"));
                if (count == null) {
                    sendable = null;
                    break;
                }
                sendable = sendable.add(count.subtract(out == null ? BigDecimal.ZERO : out));
            }
        }
        return new PlatformOrderSnapshot(
                true,
                text(order, "id"),
                firstText(order, "orderCode", "orderKey"),
                firstText(order, "orderKey", "orderCode"),
                currentStatus,
                currentStatusName,
                name,
                phone,
                address,
                sendable);
    }

    private JsonNode findOrder(Session session, String sourceRef, String sourceLineRef, int status) {
        LocalDate today = LocalDate.now(SHANGHAI);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("orderKey", sourceLineRef);
        body.put("orderStatus", Integer.toString(status));
        body.put("payTimeBegin", today.minusDays(180).toString());
        body.put("payTimeEnd", today.toString());
        body.put("pageNum", 1);
        body.put("pageSize", 100);
        JsonNode root = postJson(session, "/scc/bbc/order/orderList", body);
        JsonNode rows = root.path("data").path("data");
        if (!rows.isArray()) {
            return null;
        }
        for (JsonNode order : rows) {
            String key = firstText(order, "orderKey", "orderCode");
            String code = firstText(order, "orderCode", "orderKey");
            boolean mainOrderMatches = sourceRef == null || code == null || sourceRef.equals(code);
            if (sourceLineRef.equals(key) && mainOrderMatches) {
                return order;
            }
        }
        return null;
    }

    private JsonNode detail(Session session, String platformOrderId) {
        JsonNode root = getJson(
                session,
                "/scc/bbc/order/detail?id=" + URLEncoder.encode(platformOrderId, StandardCharsets.UTF_8));
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw new GatewayException("彩食鲜订单详情响应缺少 data");
        }
        return data;
    }

    private List<PackageSnapshot> packages(JsonNode detail) {
        JsonNode list = detail.path("goodsPackageList");
        if (!list.isArray()) {
            return List.of();
        }
        List<PackageSnapshot> result = new ArrayList<>();
        for (JsonNode item : list) {
            String carrier = firstText(item, "shipperCode", "carrierCode");
            String tracking = firstText(item, "logisticCode", "trackingNumber");
            if (carrier != null && tracking != null) {
                result.add(new PackageSnapshot(carrier, tracking));
            }
        }
        return result;
    }

    private JsonNode postJson(Session session, String path, Map<String, Object> body) {
        try {
            HttpRequest request = request(session, path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            return sendJson(session, request);
        } catch (GatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GatewayException("彩食鲜请求构造失败");
        }
    }

    private JsonNode getJson(Session session, String path) {
        return sendJson(session, request(session, path).GET().build());
    }

    private JsonNode sendJson(Session session, HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            rotateToken(session, response);
            JsonNode root = parse(response.body());
            if (response.statusCode() >= 400 || !codeOk(root)) {
                throw new GatewayException("彩食鲜只读接口返回失败");
            }
            return root;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayException("彩食鲜只读请求被中断");
        } catch (GatewayException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GatewayException("彩食鲜只读请求失败");
        }
    }

    private HttpRequest.Builder request(Session session, String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, */*")
                .header("Origin", ORIGIN)
                .header("Referer", ORIGIN + "/")
                .header(AUTH_HEADER, session.token())
                .header(SUPPLIER_HEADER, session.supplierCode());
    }

    private Session login() {
        CaishixianPullClient.LoginResult login = pullClient.login();
        if (login == null || !login.ok() || login.token() == null || login.token().isBlank()) {
            throw new GatewayException("彩食鲜登录失败");
        }
        String supplier = System.getenv("CSX_SUPPLIER_CODE");
        return new Session(login.token(), supplier == null || supplier.isBlank()
                ? DEFAULT_SUPPLIER_CODE
                : supplier.trim());
    }

    private void rotateToken(Session session, HttpResponse<?> response) {
        response.headers().firstValue(AUTH_HEADER)
                .filter(token -> !token.isBlank())
                .ifPresent(session::setToken);
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception exception) {
            throw new GatewayException("彩食鲜响应不是 JSON");
        }
    }

    private static boolean codeOk(JsonNode root) {
        String code = root.path("code").asText("");
        return "200000".equals(code) || "200".equals(code);
    }

    private static byte[] multipart(String boundary, SourceShipmentArtifact artifact) {
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + safeFilename(artifact.fileName()) + "\"\r\n"
                + "Content-Type: " + artifact.contentType() + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] content = artifact.content();
        byte[] body = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(suffix, 0, body, prefix.length + content.length, suffix.length);
        return body;
    }

    private static String safeFilename(String value) {
        String name = value == null ? "shipment.xlsx" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "shipment.xlsx" : name;
    }

    private static String joinAddress(JsonNode detail) {
        StringBuilder value = new StringBuilder();
        for (String field : List.of("receiverProvince", "receiverCity", "receiverDistrict", "receiverAddress")) {
            String part = text(detail, field);
            if (part != null && !part.isBlank()) {
                value.append(part.trim());
            }
        }
        return value.isEmpty() ? null : value.toString();
    }

    private static BigDecimal decimal(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        return value.asText().trim();
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9._-]{1,64}")
                ? code
                : "CAISHIXIAN_PLATFORM_REJECTED";
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "平台拒绝上传";
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private static String validatedBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("彩食鲜 API 地址非法", exception);
        }
        boolean official = OFFICIAL_BASE_URL.equals(normalized);
        boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()))
                && uri.getPort() > 0;
        if (!official && !loopback) {
            throw new IllegalArgumentException("彩食鲜 API 仅允许官方 HTTPS 地址；测试缝仅允许 loopback");
        }
        return normalized;
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) return;
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GatewayException("彩食鲜写后核验被中断");
        }
    }

    private record PackageSnapshot(String carrierCode, String trackingNumber) {}

    private static final class Session {
        private String token;
        private final String supplierCode;

        private Session(String token, String supplierCode) {
            this.token = token;
            this.supplierCode = supplierCode;
        }

        private String token() { return token; }
        private String supplierCode() { return supplierCode; }
        private void setToken(String token) { this.token = token; }
    }

    static final class GatewayException extends RuntimeException {
        GatewayException(String message) {
            super(message, null, false, false);
        }
    }
}
