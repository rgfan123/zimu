package cn.zimu.fulfillment.connector.zhonghui;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 真实中汇好泰 PMS 客户端（JDK HttpClient，无外部依赖）。接口细节来自抓包整理的
 * {@code pms_openapi.md}：
 *
 * <ul>
 *   <li>登录态通过自定义请求头 {@code auth: Bearer <JWT>} 传递（不是标准 Authorization）；</li>
 *   <li>全部响应为 {@code {code, msg, data}} 信封，{@code code == 0} 表示成功；</li>
 *   <li>创建商品为 {@code PUT /api/a1/cms/goodsInfo}，上传图片为 {@code multipart} 字段
 *       {@code uploadFile}。</li>
 * </ul>
 *
 * <p>传输层失败抛 {@link PmsTransportException}（由批量上传服务逐商品收口为失败项）；业务失败
 * （HTTP 2xx 但 {@code code != 0}）返回带失败标记的结果对象。登录密码在审计中一律打码。
 */
@Service
@ConditionalOnProperty(name = "app.zhonghui-pms.client-mode", havingValue = "REAL")
public class ZhonghuiPmsHttpClient implements ZhonghuiPmsService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String AUTH_HEADER = "auth";
    private static final String CAPTCHA_PATH = "/api/a1/cms/captcha";
    private static final String LOGIN_PATH = "/api/a1/cms/login";
    private static final String BRANDS_PATH = "/api/a1/cms/merchantBrand/usable";
    private static final String CERTIFICATIONS_PATH = "/api/a1/cms/goodsInfo/certificationList";
    private static final String LOGISTICS_PATH = "/api/a1/cms/logistics";
    private static final String GOODS_LIST_PATH = "/api/a1/cms/goodsInfos";
    private static final String UPLOAD_PATH = "/api/a1/cms/upload/imgs";
    private static final String CREATE_GOODS_PATH = "/api/a1/cms/goodsInfo";

    private final ZhonghuiPmsProperties properties;
    private final ZhonghuiPmsSession session;
    private final AuditLogService auditLogService;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public ZhonghuiPmsHttpClient(
            ZhonghuiPmsProperties properties,
            ZhonghuiPmsSession session,
            AuditLogService auditLogService,
            ObjectMapper mapper) {
        this.properties = properties;
        this.session = session;
        this.auditLogService = auditLogService;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean authenticated() {
        return session.authenticated();
    }

    @Override
    public CaptchaView captcha() {
        JsonNode root = request("captcha", HttpRequest.newBuilder()
                .uri(uri(CAPTCHA_PATH))
                .GET()
                .build(), Map.of());
        if (root == null || !codeOk(root)) {
            return new CaptchaView("", "");
        }
        JsonNode data = root.path("data");
        return new CaptchaView(
                data.path("captchaNo").asText(""),
                data.path("img").asText(""));
    }

    @Override
    public LoginView login(LoginCommand command) {
        properties.requireExternalWritesEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("UserName", command.username());
        body.put("PassWord", command.password());
        body.put("AuthCode", command.authCode());
        body.put("CaptchaNo", command.captchaNo());
        JsonNode root = request("login", HttpRequest.newBuilder()
                .uri(uri(LOGIN_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build(), sanitizedLoginBody(command));
        if (root == null || !codeOk(root)) {
            String message = root == null ? "PMS 服务暂时不可用" : msg(root);
            return new LoginView(false, "PMS_LOGIN_FAILED", message);
        }
        String token = root.path("data").path("token").asText("");
        if (token.isBlank()) {
            return new LoginView(false, "PMS_LOGIN_FAILED", "登录响应缺少 token");
        }
        session.set(token);
        return new LoginView(true, "OK", "");
    }

    @Override
    public List<BrandView> usableBrands() {
        JsonNode root = request("usableBrands", HttpRequest.newBuilder()
                .uri(uri(BRANDS_PATH))
                .header(AUTH_HEADER, bearer())
                .GET()
                .build(), Map.of());
        if (root == null || !codeOk(root)) {
            return List.of();
        }
        List<BrandView> brands = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            brands.add(new BrandView(
                    textOrNull(item.path("brandId")),
                    item.path("brandName").asText("")));
        }
        return brands;
    }

    @Override
    public List<CertificationView> certifications() {
        JsonNode root = request("certifications", HttpRequest.newBuilder()
                .uri(uri(CERTIFICATIONS_PATH))
                .header(AUTH_HEADER, bearer())
                .GET()
                .build(), Map.of());
        if (root == null || !codeOk(root)) {
            return List.of();
        }
        List<CertificationView> certifications = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            certifications.add(new CertificationView(
                    textOrNull(item.path("certificationId")),
                    item.path("certificationName").asText(""),
                    item.path("commencementDate").asText(""),
                    item.path("inspectionEndDate").asText("")));
        }
        return certifications;
    }

    @Override
    public String uploadImage(byte[] bytes, String contentType) {
        properties.requireExternalWritesEnabled();
        String boundary = "----ZimuPms" + Long.toHexString(System.nanoTime());
        JsonNode root = request("uploadImage", HttpRequest.newBuilder()
                .uri(uri(UPLOAD_PATH))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header(AUTH_HEADER, bearer())
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, bytes, contentType)))
                .build(), Map.of("uploadFile", "[" + bytes.length + " bytes]"));
        if (root == null || !codeOk(root)) {
            throw new PmsTransportException(root == null ? "PMS 服务暂时不可用" : msg(root));
        }
        String url = root.path("data").asText("");
        if (url.isBlank()) {
            throw new PmsTransportException("图片上传响应缺少图片 URL");
        }
        return url;
    }

    @Override
    public GoodsCreateResult createGoods(GoodsCreateCommand command) {
        properties.requireExternalWritesEnabled();
        Map<String, Object> body = goodsBody(command);
        JsonNode root = request("createGoods", HttpRequest.newBuilder()
                .uri(uri(CREATE_GOODS_PATH))
                .header("Content-Type", "application/json")
                .header(AUTH_HEADER, bearer())
                .PUT(HttpRequest.BodyPublishers.ofString(json(body)))
                .build(), body);
        if (root == null) {
            return new GoodsCreateResult(false, "PMS_HTTP_ERROR", "PMS 服务暂时不可用");
        }
        if (!codeOk(root)) {
            return new GoodsCreateResult(false, "PMS_BUSINESS_ERROR", msg(root));
        }
        return new GoodsCreateResult(true, "OK", "");
    }

    @Override
    public List<LogisticsView> logistics() {
        JsonNode root = request("logistics", HttpRequest.newBuilder()
                .uri(uri(LOGISTICS_PATH))
                .header(AUTH_HEADER, bearer())
                .GET()
                .build(), Map.of());
        if (root == null || !codeOk(root)) {
            return List.of();
        }
        List<LogisticsView> logistics = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            logistics.add(new LogisticsView(
                    textOrNull(item.path("logistId")),
                    item.path("logistName").asText("")));
        }
        return logistics;
    }

    @Override
    public GoodsVerifyView queryGoods(String goodsItem, String goodsName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsName", goodsName == null ? "" : goodsName);
        body.put("goodsItem", "");
        body.put("goodSaleSta", -1);
        body.put("goodsSta", -1);
        body.put("pageNo", 1);
        body.put("pageSize", 20);
        body.put("orderProperty", "updateDate");
        body.put("orderDirection", "desc");
        JsonNode root = request("queryGoods", HttpRequest.newBuilder()
                .uri(uri(GOODS_LIST_PATH))
                .header("Content-Type", "application/json")
                .header(AUTH_HEADER, bearer())
                .POST(HttpRequest.BodyPublishers.ofString(json(body)))
                .build(), body);
        if (root == null || !codeOk(root)) {
            return null;
        }
        JsonNode list = root.path("data").path("GoodsInfoList");
        for (JsonNode row : list) {
            // goodsItem 由本系统写入（= SKU 编码），是跨查询最精确的匹配键。
            if (goodsItem != null && goodsItem.equals(row.path("goodsItem").asText(""))) {
                return new GoodsVerifyView(
                        textOrNull(row.path("goodsId")),
                        row.path("goodsStaStr").asText(""),
                        row.path("goodSaleStaStr").asText(""));
            }
        }
        return null;
    }

    private Map<String, Object> goodsBody(GoodsCreateCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goodsName", command.goodsName());
        body.put("thirdId", command.thirdId());
        body.put("goodDescr", command.goodDescr());
        body.put("goodsItem", command.goodsItem());
        body.put("goodsTax", command.goodsTax());
        body.put("photoStr", command.photoStr());
        body.put("details", command.details());
        body.put("desc", command.desc());
        body.put("jdParam", command.jdParam());
        body.put("attrFlag", command.attrFlag());
        body.put("AttrAndStock", command.attrAndStock());
        body.put("banSaleFlag", command.banSaleFlag());
        body.put("limitAreaTempId", command.limitAreaTempId());
        body.put("saleLimit", command.saleLimit());
        body.put("goodsPrice", command.goodsPrice());
        body.put("weight", command.weight());
        body.put("goodsNum", command.goodsNum());
        body.put("supplyPrice", command.supplyPrice());
        body.put("goodsBar", command.goodsBar());
        body.put("saleUnit", command.saleUnit());
        body.put("specsName", command.specsName());
        body.put("noReasonReturnDay", command.noReasonReturnDay());
        body.put("goodsPurchaseMultiplier", command.goodsPurchaseMultiplier());
        body.put("certificationType", command.certificationType());
        body.put("certificationId", command.certificationId());
        body.put("jdSkuId", command.jdSkuId());
        body.put("brandId", command.brandId());
        body.put("logisticsCarrier", command.logisticsCarrier());
        body.put("logisticsCarrierDescription", command.logisticsCarrierDescription());
        body.put("producingArea", command.producingArea());
        body.put("specialisedIds", command.specialisedIds());
        body.put("origincountry", command.origincountry());
        return body;
    }

    /** 发送请求并返回解析后的 JSON；传输失败抛 PmsTransportException（已审计）。 */
    private JsonNode request(String operation, HttpRequest request, Object auditPayload) {
        Instant startedAt = Instant.now();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.body());
            audit(operation, auditPayload, response.statusCode(), codeOk(root) ? "OK" : "PMS_BUSINESS_ERROR",
                    root, startedAt);
            return root;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            audit(operation, auditPayload, 502, "PMS_HTTP_ERROR", null, startedAt);
            throw new PmsTransportException("PMS 请求被中断");
        } catch (Exception exception) {
            audit(operation, auditPayload, 502, "PMS_HTTP_ERROR", null, startedAt);
            throw new PmsTransportException("PMS 服务暂时不可用，请稍后重试");
        }
    }

    private void audit(String operation, Object payload, int httpStatus, String businessCode,
            JsonNode response, Instant startedAt) {
        RequestContext context = RequestContext.current();
        auditLogService.record(new AuditLogService.AuditCommand()
                .requestId(context == null ? null : context.getRequestId())
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getOperator() == null ? "zhonghui-pms" : context.getOperator())
                .actorType(AuditActorType.SYSTEM)
                .service("zhonghui.pms")
                .operation(operation)
                .requestPayload(payload == null ? Map.of() : payload)
                .responsePayload(response == null ? Map.of("error", "transport failure") : response)
                .httpStatus(httpStatus)
                .businessCode(businessCode)
                .latencyMs((int) Duration.between(startedAt, Instant.now()).toMillis()));
    }

    private String bearer() {
        return "Bearer " + session.token();
    }

    private URI uri(String path) {
        String base = properties.getBaseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalized + path);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new PmsTransportException("请求序列化失败");
        }
    }

    private byte[] multipartBody(String boundary, byte[] bytes, String contentType) {
        String extension = switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"uploadFile\"; filename=\"goods" + extension + "\"\r\n"
                + "Content-Type: " + (contentType == null || contentType.isBlank() ? "image/jpeg" : contentType) + "\r\n"
                + "\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + bytes.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(bytes, 0, body, headBytes.length, bytes.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + bytes.length, tailBytes.length);
        return body;
    }

    /** 登录审计载荷：密码打码，绝不落审计。 */
    private Map<String, Object> sanitizedLoginBody(LoginCommand command) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("UserName", command.username());
        safe.put("PassWord", "********");
        safe.put("AuthCode", command.authCode());
        safe.put("CaptchaNo", command.captchaNo());
        return safe;
    }

    private boolean codeOk(JsonNode root) {
        return root != null && root.path("code").asInt(-1) == 0;
    }

    private String msg(JsonNode root) {
        return root == null ? "" : root.path("msg").asText("");
    }

    private String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** PMS 传输层失败（HTTP/网络/超时/响应结构非法），异常消息不携带任何密钥或请求体。 */
    static final class PmsTransportException extends RuntimeException {
        PmsTransportException(String message) {
            super(message, null, false, false);
        }
    }
}
