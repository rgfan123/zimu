package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 03 — MCP 工具 schema 自动生成（agent-decision-layer 03）：注册表 Jackson schema →
 * LangChain4j {@link JsonObjectSchema} 的确定性转换；结构/描述逐字段等价，
 * 未知类型与结构缺失 fail-fast。
 */
class McpToolSchemaConverterTest {

    @Test
    void simpleReadSchemaRoundTrips() {
        ObjectNode schema = McpToolRegistry.schema(
                Map.of(
                        "page", McpToolRegistry.integerProperty("页码，从 0 开始"),
                        "size", McpToolRegistry.integerProperty("每页条数，1-200")),
                List.of());

        JsonObjectSchema converted = McpToolSchemaConverter.toObjectSchema(schema);

        McpToolSchemaTestSupport.assertSchemaEquals(schema, converted);
    }

    @Test
    void requiredAndStringPropertiesArePreserved() {
        ObjectNode schema = McpToolRegistry.schema(
                Map.of("message_id", McpToolRegistry.stringProperty("渠道消息记录 ID")),
                List.of("message_id"));

        JsonObjectSchema converted = McpToolSchemaConverter.toObjectSchema(schema);

        McpToolSchemaTestSupport.assertSchemaEquals(schema, converted);
        assertThat(converted.required()).containsExactly("message_id");
        assertThat(converted.properties().get("message_id")).isInstanceOf(
                dev.langchain4j.model.chat.request.json.JsonStringSchema.class);
    }

    @Test
    void nestedObjectAndArraySchemaRoundTrips() {
        ObjectNode item = McpToolRegistry.objectProperty("行级建议");
        ObjectNode itemProps = item.putObject("properties");
        itemProps.set("line_no", McpToolRegistry.stringProperty("正整数行号"));
        itemProps.set("quantity", McpToolRegistry.stringProperty("正数数量字符串"));
        ObjectNode receiver = McpToolRegistry.objectProperty("收货与结账资料");
        ObjectNode receiverProps = receiver.putObject("properties");
        receiverProps.set("name", McpToolRegistry.stringProperty("收货人姓名"));
        receiverProps.set("province", McpToolRegistry.stringProperty("省份"));
        ObjectNode schema = McpToolRegistry.schema(
                Map.of(
                        "draft_id", McpToolRegistry.stringProperty("订单草稿 ID"),
                        "receiver", receiver,
                        "items", McpToolRegistry.arrayProperty("行级建议，每项 {line_no, quantity?}", item)),
                List.of("draft_id"));

        JsonObjectSchema converted = McpToolSchemaConverter.toObjectSchema(schema);

        McpToolSchemaTestSupport.assertSchemaEquals(schema, converted);
    }

    @Test
    void boundedIntegerConstraintRoundTripsWithoutConstraintLoss() {
        ObjectNode count = McpToolRegistry.integerProperty("int32 正整数数量（JSON integer）");
        count.put("minimum", 1);
        count.put("maximum", Integer.MAX_VALUE);
        ObjectNode schema = McpToolRegistry.schema(Map.of("quantity", count), List.of("quantity"));

        JsonObjectSchema converted = McpToolSchemaConverter.toObjectSchema(schema);

        McpToolSchemaTestSupport.assertSchemaEquals(schema, converted);
        assertThat(converted.properties().get("quantity")).isInstanceOf(JsonRawSchema.class);
    }

    @Test
    void topLevelNonObjectSchemaIsRejected() {
        ObjectNode arraySchema = McpToolRegistry.arrayProperty(
                "非对象", McpToolRegistry.stringProperty("项"));

        assertThatThrownBy(() -> McpToolSchemaConverter.toObjectSchema(arraySchema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须是 object");
    }

    @Test
    void unsupportedTypeFailsFast() {
        ObjectNode schema = McpToolRegistry.schema(
                Map.of("weird", McpToolRegistry.stringProperty("描述").put("type", "regex")),
                List.of());

        assertThatThrownBy(() -> McpToolSchemaConverter.toObjectSchema(schema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("regex");
    }

    @Test
    void arrayWithoutItemsFailsFast() {
        ObjectNode arraySchema = McpToolRegistry.arrayProperty("无 items 定义", null);

        assertThatThrownBy(() -> McpToolSchemaConverter.toObjectSchema(arraySchema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("items");
    }

    @Test
    void missingTypeFieldFailsFast() {
        ObjectNode schema = McpToolRegistry.schema(Map.of(), List.of());
        schema.remove("type");

        assertThatThrownBy(() -> McpToolSchemaConverter.toObjectSchema(schema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type");
    }
}
