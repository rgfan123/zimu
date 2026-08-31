package cn.zimu.fulfillment.rawmaterial;

import static cn.zimu.fulfillment.rawmaterial.RawMaterialReadException.Code.RAW_MATERIAL_CONTRACT_DRIFT;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialReadException.Code.RAW_MATERIAL_NOT_CONFIGURED;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialReadException.Code.RAW_MATERIAL_UNAUTHORIZED;
import static cn.zimu.fulfillment.rawmaterial.RawMaterialReadException.Code.RAW_MATERIAL_UNAVAILABLE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * yuanliaokc REST 只读客户端：OAuth2 password 登录换短时 JWT，再取实时结存。
 *
 * <p>失败一律收敛到四类稳定错误码（{@link RawMaterialReadException.Code}）：
 * 网络/超时→UNAVAILABLE；登录被拒或令牌两次被拒→UNAUTHORIZED；响应结构不符→CONTRACT_DRIFT
 * ——宁可停下，绝不猜着解析。令牌缓存进程内复用；上游 401 时作废重登一次再试，
 * 覆盖令牌自然过期，不引入本地对 exp 的猜测。
 */
@Component
public class YuanliaokcReadClient implements YuanliaokcReadGateway {

    private static final Logger log = LoggerFactory.getLogger(YuanliaokcReadClient.class);
    private static final int MAX_RESPONSE_BYTES = 2_097_152;

    private final YuanliaokcGatewayProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final AtomicReference<String> cachedToken = new AtomicReference<>();

    @Autowired
    public YuanliaokcReadClient(YuanliaokcGatewayProperties properties, ObjectMapper mapper) {
        this(
                properties,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    YuanliaokcReadClient(
            YuanliaokcGatewayProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public List<YuanliaokcStockRow> stock(String keyword) {
        requireConfigured();
        String token = cachedToken.get();
        if (token == null) {
            token = login();
            cachedToken.set(token);
        }
        HttpResponse<byte[]> response = send(stockRequest(token, keyword));
        if (response.statusCode() == 401) {
            // 令牌自然过期或被上游作废：重登一次。再次 401 才定性为鉴权失败。
            cachedToken.set(null);
            token = login();
            cachedToken.set(token);
            response = send(stockRequest(token, keyword));
            if (response.statusCode() == 401) {
                throw new RawMaterialReadException(
                        RAW_MATERIAL_UNAUTHORIZED, "上游拒绝了只读令牌（重登后仍 401）");
            }
        }
        if (response.statusCode() == 403) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_UNAUTHORIZED, "上游拒绝了只读账号的访问（403）");
        }
        if (response.statusCode() != 200) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_UNAVAILABLE, "上游结存接口异常状态 " + response.statusCode());
        }
        return parseRows(bodyJson(response));
    }

    private void requireConfigured() {
        if (!properties.isReady()) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_NOT_CONFIGURED, "本部署未开放原料库存只读接入");
        }
    }

    private String login() {
        String form = "username=" + URLEncoder.encode(properties.getUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(properties.getPassword(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(resolve("/api/auth/login"))
                .timeout(properties.getReadTimeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_UNAUTHORIZED, "上游拒绝了只读账号登录（" + response.statusCode() + "）");
        }
        if (response.statusCode() != 200) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_UNAVAILABLE, "上游登录接口异常状态 " + response.statusCode());
        }
        JsonNode body = bodyJson(response);
        JsonNode token = body.path("access_token");
        if (!token.isTextual() || token.asText().isBlank()) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_CONTRACT_DRIFT, "登录响应缺少 access_token 文本字段");
        }
        return token.asText();
    }

    private HttpRequest stockRequest(String token, String keyword) {
        StringBuilder path = new StringBuilder("/api/stock?only_in_stock=true");
        if (keyword != null && !keyword.isBlank()) {
            path.append("&keyword=").append(URLEncoder.encode(keyword.trim(), StandardCharsets.UTF_8));
        }
        return HttpRequest.newBuilder(resolve(path.toString()))
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
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
                throw new RawMaterialReadException(
                        RAW_MATERIAL_CONTRACT_DRIFT,
                        "上游响应超过 " + MAX_RESPONSE_BYTES + " 字节上限");
            }
            return response;
        } catch (IOException e) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_UNAVAILABLE, "上游不可达或超时: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RawMaterialReadException(RAW_MATERIAL_UNAVAILABLE, "上游调用被中断", e);
        }
    }

    private JsonNode bodyJson(HttpResponse<byte[]> response) {
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_CONTRACT_DRIFT, "上游响应不是合法 JSON", e);
        }
    }

    private List<YuanliaokcStockRow> parseRows(JsonNode body) {
        if (!body.isArray()) {
            throw new RawMaterialReadException(
                    RAW_MATERIAL_CONTRACT_DRIFT, "结存响应不是数组，契约已漂移");
        }
        List<YuanliaokcStockRow> rows = new ArrayList<>(body.size());
        for (JsonNode node : body) {
            rows.add(parseRow(node));
        }
        return List.copyOf(rows);
    }

    private YuanliaokcStockRow parseRow(JsonNode node) {
        return new YuanliaokcStockRow(
                requiredLong(node, "material_id"),
                requiredText(node, "material_code"),
                requiredText(node, "material_name"),
                optionalText(node, "category"),
                optionalText(node, "spec"),
                requiredText(node, "preferred_display_unit"),
                optionalLong(node, "piece_count"),
                requiredNonNegativeDecimal(node, "current_kg"),
                requiredNonNegativeDecimal(node, "available_kg"),
                requiredNonNegativeDecimal(node, "frozen_kg"),
                requiredLong(node, "batch_count"),
                optionalText(node, "earliest_expiry"),
                requiredText(node, "status"));
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw drift(field, node);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw drift(field, node);
        }
        return value.asText();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw drift(field, node);
        }
        return value.asLong();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw drift(field, node);
        }
        return value.asLong();
    }

    private static BigDecimal requiredNonNegativeDecimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw drift(field, node);
        }
        BigDecimal decimal = value.decimalValue();
        if (decimal.signum() < 0) {
            // 重量结存不可能为负；出现即上游口径变了，停下而不是渲染一个负库存。
            throw drift(field, node);
        }
        return decimal;
    }

    private static RawMaterialReadException drift(String field, JsonNode row) {
        // 不回显整行数据：日志留字段名即可，避免把上游业务数据倒进错误消息。
        return new RawMaterialReadException(
                RAW_MATERIAL_CONTRACT_DRIFT, "结存行缺少必填字段或类型不符: " + field);
    }
}
