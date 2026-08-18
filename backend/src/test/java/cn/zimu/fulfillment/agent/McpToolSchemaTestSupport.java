package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
/**
 * 测试助手：LangChain4j {@link JsonSchemaElement} → Jackson 树的确定性序列化，
 * 用于断言「注册表 schema 与 Agent 可见 schema 完全一致」（等价性测试不依赖
 * langchain4j 内部 JSON codec SPI，当前 classpath 无 provider）。
 */
public final class McpToolSchemaTestSupport {

    private McpToolSchemaTestSupport() {}

    public static JsonNode toJson(JsonSchemaElement element) {
        if (element instanceof JsonObjectSchema object) {
            ObjectNode node = base(object.description(), "object");
            ObjectNode properties = node.putObject("properties");
            object.properties().forEach((name, child) -> properties.set(name, toJson(child)));
            // 注册表 schema 恒带 required 数组；LangChain4j 恒保留（空数组也序列化），逐字段等价
            ArrayNode required = node.putArray("required");
            object.required().forEach(required::add);
            return node;
        }
        if (element instanceof JsonStringSchema schema) {
            return base(schema.description(), "string");
        }
        if (element instanceof JsonIntegerSchema schema) {
            return base(schema.description(), "integer");
        }
        if (element instanceof JsonNumberSchema schema) {
            return base(schema.description(), "number");
        }
        if (element instanceof JsonBooleanSchema schema) {
            return base(schema.description(), "boolean");
        }
        if (element instanceof JsonArraySchema schema) {
            ObjectNode node = base(schema.description(), "array");
            if (schema.items() != null) {
                node.set("items", toJson(schema.items()));
            }
            return node;
        }
        if (element instanceof JsonEnumSchema schema) {
            ObjectNode node = base(schema.description(), "string");
            ArrayNode values = node.putArray("enum");
            schema.enumValues().forEach(values::add);
            return node;
        }
        throw new IllegalStateException("测试不支持的元素类型: " + element.getClass());
    }

    private static ObjectNode base(String description, String type) {
        ObjectNode node = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.put("type", type);
        if (description != null && !description.isBlank()) {
            node.put("description", description);
        }
        return node;
    }

    /** 比较注册表 schema 与转换后 schema：结构（type/properties/required/items/enum）与描述逐字段一致。
     * 空 required 数组在语义上等于缺席（无必填约束），比较前递归归一化。 */
    public static void assertSchemaEquals(ObjectNode expected, JsonObjectSchema actual) {
        ObjectNode normalizedExpected = withoutEmptyRequired(expected.deepCopy());
        ObjectNode normalizedActual = withoutEmptyRequired((ObjectNode) toJson(actual));
        org.assertj.core.api.Assertions.assertThat(normalizedActual)
                .as("schema 等价性: %s", normalizedExpected)
                .isEqualTo(normalizedExpected);
    }

    private static ObjectNode withoutEmptyRequired(ObjectNode node) {
        ObjectNode result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        node.fields().forEachRemaining(entry -> {
            if ("required".equals(entry.getKey()) && entry.getValue().isArray() && entry.getValue().isEmpty()) {
                return;
            }
            if (entry.getValue().isObject()) {
                result.set(entry.getKey(), withoutEmptyRequired((ObjectNode) entry.getValue()));
            } else {
                result.set(entry.getKey(), entry.getValue());
            }
        });
        return result;
    }
}
