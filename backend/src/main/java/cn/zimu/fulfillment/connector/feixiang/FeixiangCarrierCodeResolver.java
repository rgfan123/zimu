package cn.zimu.fulfillment.connector.feixiang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把内部物流公司映射成飞象平台的 {@code express_code}。
 *
 * <p><b>这是一个会静默污染既有通道的陷阱。</b>{@code connector_configs} 里飞象登记的
 * {@code carrier_mappings.JD = "京东物流"} 是<b>显示名</b>，它同时被回填 CSV 的「物流公司」
 * 列使用（今天在生产跑着的那条人工上传路径）；而 {@code ajaxSendOrderProduct} 要的是
 * <b>代码</b> {@code jingdong}。就地把显示名改成代码会把 CSV 一起改坏，所以这里走一个
 * <b>平级新键</b> {@code carrier_api_codes}，两者并存、互不覆盖。
 *
 * <p>来源展示翻译不是物流公司白名单。调用方优先传 {@code carrier_mappings} 的显示值；
 * 没维护显示翻译时会传内部标准 {@code carrier_code}。前者按值反查唯一内部代码，后者
 * 直接作为 {@code carrier_api_codes} 的键，再取平台代码。显示值反查到多于 1 个仍判歧义——
 * <b>绝不回落成显示名</b>，那会让一个平台看不懂的中文串被发出去。
 */
@Component
public class FeixiangCarrierCodeResolver {

    /** 平台物流公司代码字符集；中文显示名会在这里被挡下。 */
    private static final String API_CODE_PATTERN = "^[0-9a-z_]{1,32}$";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FeixiangCarrierCodeResolver(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 解析结果；{@code expressCode} 为空表示未映射，调用方必须据此置 carrierMapped=false。 */
    public record Resolution(String expressCode, String businessCode, String message) {

        public boolean resolved() {
            return expressCode != null && !expressCode.isBlank();
        }

        static Resolution ok(String expressCode) {
            return new Resolution(expressCode, "OK", "已命中飞象平台物流公司代码");
        }

        static Resolution failed(String businessCode, String message) {
            return new Resolution(null, businessCode, message);
        }
    }

    /**
     * @param carrierOutputValue 渠道显示翻译，缺失时为内部标准物流公司代码
     */
    public Resolution resolve(String carrierOutputValue) {
        String display = carrierOutputValue == null ? "" : carrierOutputValue.trim();
        if (display.isEmpty()) {
            return Resolution.failed("FEIXIANG_CARRIER_REQUIRED", "正式物流公司事实缺失");
        }
        JsonNode config;
        try {
            List<String> rows = jdbc.query(
                    "SELECT config::text FROM app.connector_configs WHERE source_channel = 'FEIXIANG'",
                    (rs, rowNum) -> rs.getString(1));
            if (rows.isEmpty() || rows.getFirst() == null) {
                return Resolution.failed(
                        "FEIXIANG_CARRIER_CONFIG_MISSING", "飞象 Connector 配置缺失，无法解析平台物流公司代码");
            }
            config = objectMapper.readTree(rows.getFirst());
        } catch (Exception exception) {
            return Resolution.failed(
                    "FEIXIANG_CARRIER_CONFIG_UNAVAILABLE", "飞象 Connector 配置读取失败，未提交任何平台请求");
        }
        return resolveFrom(config, display);
    }

    /** 纯解析，便于单测穷举映射缺失、歧义与非法代码三种形状。 */
    static Resolution resolveFrom(JsonNode config, String carrierOutputValue) {
        String display = carrierOutputValue == null ? "" : carrierOutputValue.trim();
        JsonNode mappings = config == null ? null : config.path("carrier_mappings");
        List<String> internalCodes = new ArrayList<>();
        if (mappings != null && mappings.isObject()) {
            if (mappings.has(display)) {
                internalCodes.add(display);
            } else {
                java.util.Iterator<Map.Entry<String, JsonNode>> fields = mappings.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    JsonNode value = entry.getValue();
                    if (value != null && value.isTextual() && display.equals(value.asText().trim())) {
                        internalCodes.add(entry.getKey());
                    }
                }
            }
        }
        if (internalCodes.isEmpty() && display.matches("^[A-Z0-9_-]{1,64}$")) {
            internalCodes.add(display);
        }
        if (internalCodes.isEmpty()) {
            return Resolution.failed(
                    "FEIXIANG_CARRIER_UNMAPPED", "正式物流公司未命中飞象 carrier_mappings");
        }
        if (internalCodes.size() != 1) {
            return Resolution.failed(
                    "FEIXIANG_CARRIER_AMBIGUOUS",
                    "多个内部物流公司映射到同一个飞象显示名，无法确定平台代码");
        }
        JsonNode apiCodes = config.path("carrier_api_codes");
        if (!apiCodes.isObject()) {
            return Resolution.failed(
                    "FEIXIANG_CARRIER_API_CODE_MISSING",
                    "飞象 Connector 未配置 carrier_api_codes，在线回传缺少平台物流公司代码");
        }
        JsonNode apiCode = apiCodes.path(internalCodes.getFirst());
        String code = apiCode.isTextual() ? apiCode.asText().trim() : "";
        if (code.isEmpty()) {
            return Resolution.failed(
                    "FEIXIANG_CARRIER_API_CODE_MISSING",
                    "飞象 carrier_api_codes 未覆盖该物流公司，未提交任何平台请求");
        }
        if (!code.matches(API_CODE_PATTERN)) {
            // 配置里误填了显示名（中文）时在这里被拒，而不是被发到平台。
            return Resolution.failed(
                    "FEIXIANG_CARRIER_API_CODE_INVALID",
                    "飞象 carrier_api_codes 的值不是合法平台代码（小写英数），拒绝使用");
        }
        return Resolution.ok(code);
    }
}
