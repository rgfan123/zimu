package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.dto.PageResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.Map;
import java.util.function.Function;

/** MCP 显式投影的小型 JSON 构造器；故意不接受任意 POJO/DTO。 */
final class McpProjectionSupport {

    private McpProjectionSupport() {}

    static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    static ArrayNode arrayNode() {
        return JsonNodeFactory.instance.arrayNode();
    }

    static <T> ObjectNode pageNode(PageResponse<T> page, Function<T, ? extends JsonNode> projector) {
        ObjectNode result = objectNode();
        ArrayNode items = result.putArray("items");
        page.items().forEach(item -> items.add(projector.apply(item)));
        result.put("page", page.page());
        result.put("size", page.size());
        result.put("total_elements", page.totalElements());
        result.put("total_pages", page.totalPages());
        return result;
    }

    static ObjectNode mapNode(Map<?, ?> values) {
        ObjectNode result = objectNode();
        if (values == null) {
            return result;
        }
        values.forEach((key, value) -> result.set(String.valueOf(key), valueNode(value)));
        return result;
    }

    static ArrayNode listNode(Iterable<?> values) {
        ArrayNode result = arrayNode();
        if (values != null) {
            values.forEach(value -> result.add(valueNode(value)));
        }
        return result;
    }

    static JsonNode valueNode(Object value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof String text) {
            return JsonNodeFactory.instance.textNode(text);
        }
        if (value instanceof Boolean flag) {
            return JsonNodeFactory.instance.booleanNode(flag);
        }
        if (value instanceof Integer number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Long number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Short number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Byte number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Double number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Float number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof BigDecimal number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof BigInteger number) {
            return JsonNodeFactory.instance.numberNode(number);
        }
        if (value instanceof Enum<?> enumValue) {
            return JsonNodeFactory.instance.textNode(enumValue.name());
        }
        if (value instanceof TemporalAccessor temporal) {
            return JsonNodeFactory.instance.textNode(temporal.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return mapNode(map);
        }
        if (value instanceof Iterable<?> iterable) {
            return listNode(iterable);
        }
        throw new IllegalArgumentException(
                "MCP 动态值不允许序列化 DTO/POJO: " + value.getClass().getName());
    }
}
