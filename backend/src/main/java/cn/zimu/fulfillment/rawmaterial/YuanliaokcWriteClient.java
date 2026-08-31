package cn.zimu.fulfillment.rawmaterial;

import static cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_CONTRACT_DRIFT;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_DISABLED;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_REJECTED;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_UNAUTHORIZED;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException.Code.RAW_MATERIAL_WRITE_UNAVAILABLE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * yuanliaokc REST 写客户端（镜像 {@code KehuzxMcpWriteClient} 的独立写身份纪律，传输面是 REST）。
 *
 * <p>写账号独立登录、令牌独立缓存，绝不复用只读账号的令牌——读写身份分离是平台不变式，
 * 上游按角色（require_writer/require_reviewer）授权，只有专用写账号能通过。失败收敛到
 * 五类稳定错误码（{@link RawMaterialWriteException.Code}）：未开写不发包；上游 4xx 带 detail
 * 原样透传成 UNPROCESSABLE 语义的 WRITE_REJECTED（改入参可重试）；401/403 归鉴权；
 * 5xx/网络归不可用；响应结构不符宁停不猜。
 */
@Component
public class YuanliaokcWriteClient implements YuanliaokcWriteGateway {

    private static final int MAX_RESPONSE_BYTES = 2_097_152;
    /** REJECTED 消息里 detail 的截断上限：透传语义，但不把上游整段 payload 倒进错误消息。 */
    private static final int MAX_DETAIL_CHARS = 500;

    /** 写侧契约漂移工厂：共享解析器抛写通道的漂移码。 */
    private static final Function<String, RuntimeException> WRITE_DRIFT =
            message -> new RawMaterialWriteException(RAW_MATERIAL_WRITE_CONTRACT_DRIFT, message);

    private final YuanliaokcGatewayProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final AtomicReference<String> cachedWriteToken = new AtomicReference<>();

    @Autowired
    public YuanliaokcWriteClient(YuanliaokcGatewayProperties properties, ObjectMapper mapper) {
        this(
                properties,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    YuanliaokcWriteClient(
            YuanliaokcGatewayProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public YuanliaokcInboundOrder createInboundOrder(JsonNode payload) {
        return YuanliaokcPayloadParser.inboundOrder(
                authorizedPostJson("/api/inbound-orders", payload, "入库单创建"), WRITE_DRIFT);
    }

    @Override
    public YuanliaokcInboundOrder approveInboundOrder(long orderId) {
        return YuanliaokcPayloadParser.inboundOrder(
                authorizedPostJson("/api/inbound-orders/" + orderId + "/approve", null, "入库单审批"),
                WRITE_DRIFT);
    }

    @Override
    public YuanliaokcScrapOrder createScrapOrder(JsonNode payload) {
        return YuanliaokcPayloadParser.scrapOrder(
                authorizedPostJson("/api/scrap-orders", payload, "报废单创建"), WRITE_DRIFT);
    }

    @Override
    public YuanliaokcScrapOrder approveScrapOrder(long orderId) {
        return YuanliaokcPayloadParser.scrapOrder(
                authorizedPostJson("/api/scrap-orders/" + orderId + "/approve", null, "报废单审批"),
                WRITE_DRIFT);
    }

    /**
     * 带写令牌生命周期的 POST 公共流程：未开写不发包 → 写账号登录 → 401 作废重登一次 →
     * 再拒才定性鉴权失败。业务 4xx 携带上游 detail 透传成 WRITE_REJECTED。
     */
    private JsonNode authorizedPostJson(String pathAndQuery, JsonNode payload, String interfaceLabel) {
        if (!properties.isWriteReady()) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_DISABLED, "本部署未开放原料库存写入（写开关或写凭据未配置）");
        }
        String token = cachedWriteToken.get();
        if (token == null) {
            token = loginWriter();
            cachedWriteToken.set(token);
        }
        HttpResponse<byte[]> response = send(postRequest(token, pathAndQuery, payload));
        if (response.statusCode() == 401) {
            // 写令牌自然过期或被上游作废：重登一次。再次 401 才定性为鉴权失败。
            cachedWriteToken.set(null);
            token = loginWriter();
            cachedWriteToken.set(token);
            response = send(postRequest(token, pathAndQuery, payload));
            if (response.statusCode() == 401) {
                throw new RawMaterialWriteException(
                        RAW_MATERIAL_WRITE_UNAUTHORIZED, "上游拒绝了写令牌（重登后仍 401）");
            }
        }
        if (response.statusCode() == 403) {
            // 403 = 写账号角色不足（非 writer/reviewer），是部署配置问题而非入参问题
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_UNAUTHORIZED, "上游拒绝了写账号的操作权限（403）");
        }
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            // 业务性 4xx（400 校验失败 / 404 单据不存在 / 422 载荷不合法…）：
            // detail 原样透传给调用方修正入参，语义是 UNPROCESSABLE，不是系统故障。
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_REJECTED,
                    "上游拒绝了" + interfaceLabel + "（" + response.statusCode() + "）："
                            + extractDetail(response));
        }
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_UNAVAILABLE,
                    "上游" + interfaceLabel + "接口异常状态 " + response.statusCode());
        }
        return bodyJson(response);
    }

    private String loginWriter() {
        String form = "username="
                + URLEncoder.encode(properties.getWriteUsername(), StandardCharsets.UTF_8)
                + "&password="
                + URLEncoder.encode(properties.getWritePassword(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(resolve("/api/auth/login"))
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_UNAUTHORIZED,
                    "上游拒绝了写账号登录（" + response.statusCode() + "）");
        }
        if (response.statusCode() != 200) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_UNAVAILABLE, "上游登录接口异常状态 " + response.statusCode());
        }
        JsonNode token = bodyJson(response).path("access_token");
        if (!token.isTextual() || token.asText().isBlank()) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_CONTRACT_DRIFT, "登录响应缺少 access_token 文本字段");
        }
        return token.asText();
    }

    private HttpRequest postRequest(String token, String pathAndQuery, JsonNode payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(pathAndQuery))
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (payload == null) {
            // approve 类端点无请求体
            return builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private URI resolve(String pathAndQuery) {
        URI base = properties.getEndpoint();
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        return URI.create(origin + pathAndQuery);
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.body() != null && response.body().length > MAX_RESPONSE_BYTES) {
                throw new RawMaterialWriteException(
                        RAW_MATERIAL_WRITE_CONTRACT_DRIFT,
                        "上游响应超过 " + MAX_RESPONSE_BYTES + " 字节上限");
            }
            return response;
        } catch (IOException e) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_UNAVAILABLE, "上游不可达或超时: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RawMaterialWriteException(RAW_MATERIAL_WRITE_UNAVAILABLE, "上游调用被中断", e);
        }
    }

    private JsonNode bodyJson(HttpResponse<byte[]> response) {
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new RawMaterialWriteException(
                    RAW_MATERIAL_WRITE_CONTRACT_DRIFT, "上游响应不是合法 JSON", e);
        }
    }

    /**
     * 提取 FastAPI 的 4xx detail：普通 HTTPException 是字符串，422 校验失败是数组，
     * 都转成截断后的文本。响应不是 JSON 时不升格为漂移——4xx 的定性已经明确是拒绝，
     * detail 只是给人看的佐证。
     */
    private String extractDetail(HttpResponse<byte[]> response) {
        String raw;
        try {
            JsonNode detail = mapper.readTree(response.body()).path("detail");
            raw = detail.isMissingNode() || detail.isNull()
                    ? ""
                    : detail.isTextual() ? detail.asText() : detail.toString();
        } catch (IOException notJson) {
            raw = "";
        }
        if (raw.isBlank()) {
            return "上游未提供 detail";
        }
        return raw.length() <= MAX_DETAIL_CHARS ? raw : raw.substring(0, MAX_DETAIL_CHARS) + "…";
    }
}
