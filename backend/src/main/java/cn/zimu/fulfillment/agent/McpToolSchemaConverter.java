package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具 JSON Schema（Jackson {@link JsonNode}）→ LangChain4j {@link JsonSchemaElement} 转换。
 *
 * <p>只支持 {@code McpToolRegistry.schema/stringProperty/integerProperty/objectProperty/
 * arrayProperty} 产出的结构（object/string/integer/number/boolean/array，属性含 description、
 * 对象含 properties/required、数组含 items）；遇到未知类型或结构缺失时 fail-fast，让注册表
 * schema 与 Agent 描述的漂移在绑定期直接暴露，而不是静默丢失约束。
 */
public final class McpToolSchemaConverter {

    private static final Set<String> SUPPORTED_TYPES =
            Set.of("object", "string", "integer", "number", "boolean", "array");

    private McpToolSchemaConverter() {}

    /** 工具输入 schema 必须是对象类型；返回对应 LangChain4j 对象 schema。 */
    public static JsonObjectSchema toObjectSchema(JsonNode schema) {
        JsonSchemaElement element = toElement(schema, "工具输入 schema 必须是 object");
        if (!(element instanceof JsonObjectSchema object)) {
            throw new IllegalStateException("工具输入 schema 必须是 object，实际: " + element.getClass().getSimpleName());
        }
        return object;
    }

    private static JsonSchemaElement toElement(JsonNode node, String context) {
        if (node == null || !node.isObject() || !node.hasNonNull("type") || !node.get("type").isTextual()) {
            throw new IllegalStateException(context + " 缺少 type 字段: " + node);
        }
        String type = node.get("type").asText();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalStateException(context + " 含不支持的 JSON Schema 类型: " + type);
        }
        String description = textOrNull(node, "description");
        switch (type) {
            case "object" -> {
                return toObject(node, description);
            }
            case "string" -> {
                return JsonStringSchema.builder().description(description).build();
            }
            case "integer" -> {
                if (hasNumericBounds(node, context)) {
                    return JsonRawSchema.from(node.toString());
                }
                return JsonIntegerSchema.builder().description(description).build();
            }
            case "number" -> {
                if (hasNumericBounds(node, context)) {
                    return JsonRawSchema.from(node.toString());
                }
                return JsonNumberSchema.builder().description(description).build();
            }
            case "boolean" -> {
                return JsonBooleanSchema.builder().description(description).build();
            }
            case "array" -> {
                JsonNode items = node.get("items");
                if (items == null || items.isNull()) {
                    throw new IllegalStateException(context + " 数组类型缺少 items 定义");
                }
                return JsonArraySchema.builder()
                        .description(description)
                        .items(toElement(items, context + " 的 items"))
                        .build();
            }
            default -> throw new IllegalStateException("不支持的 JSON Schema 类型: " + type);
        }
    }

    private static JsonObjectSchema toObject(JsonNode node, String description) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder().description(description);
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            Map<String, JsonSchemaElement> converted = new java.util.LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                converted.put(entry.getKey(), toElement(entry.getValue(), "属性 " + entry.getKey()));
            }
            builder.addProperties(converted);
        }
        JsonNode required = node.get("required");
        if (required != null && required.isArray()) {
            java.util.List<String> names = new java.util.ArrayList<>();
            required.forEach(item -> {
                if (item != null && item.isTextual()) {
                    names.add(item.asText());
                }
            });
            builder.required(names);
        }
        return builder.build();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        return value.asText();
    }

    private static boolean hasNumericBounds(JsonNode node, String context) {
        boolean hasBounds = false;
        for (String field : java.util.List.of("minimum", "maximum")) {
            JsonNode value = node.get(field);
            if (value == null) {
                continue;
            }
            if (!value.isNumber()) {
                throw new IllegalStateException(context + " 的 " + field + " 必须是数字: " + value);
            }
            hasBounds = true;
        }
        return hasBounds;
    }
}
