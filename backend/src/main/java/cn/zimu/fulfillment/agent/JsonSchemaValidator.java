package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户端 JSON Schema 校验（meta-agent-platform 01/04 决策）：模型输出统一校验兜底——
 * 即使供应商降级 json_object（DeepSeek 等不支持 json_schema），也由本校验器保证输出满足
 * 定义携带的 output_schema，失败映射 {@code AGENT_OUTPUT_INVALID}（不重试）。
 */
public final class JsonSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private JsonSchemaValidator() {}

    /**
     * 校验 JSON 是否满足 schema。
     *
     * @return null 表示合法；否则返回可读的校验错误摘要（不进审计/日志明文敏感信息）。
     * @throws IllegalStateException schema 本身非法（配置漂移，fail-fast）
     */
    public static String validate(String json, String schema) {
        try {
            JsonSchema jsonSchema = FACTORY.getSchema(schema);
            JsonNode node = MAPPER.readTree(json);
            Set<ValidationMessage> errors = jsonSchema.validate(node);
            if (errors.isEmpty()) {
                return null;
            }
            return errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("output_schema 非法（JSON Schema 解析失败）", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON Schema 校验执行失败", ex);
        }
    }
}
