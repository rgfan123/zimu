package cn.zimu.fulfillment.rawmaterial;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * yuanliaokc 入库单/报废单/流水响应的严格白名单解析。
 *
 * <p>读客户端与写客户端共用同一套解析（入库单在 list 与 create/approve 响应里是同一
 * _inbound_out 投影），差别只在漂移的异常类型——各自传入 drift 工厂
 * （读→{@link RawMaterialReadException} CONTRACT_DRIFT，写→{@link RawMaterialWriteException}
 * WRITE_CONTRACT_DRIFT），保证「宁停不猜」的判定逻辑只有一份，不随两个客户端各自漂移。
 *
 * <p>drift 消息只带字段名，不回显整行业务数据（与 {@link YuanliaokcReadClient} 既有纪律一致）。
 */
final class YuanliaokcPayloadParser {

    private YuanliaokcPayloadParser() {}

    static YuanliaokcInboundOrder inboundOrder(
            JsonNode node, Function<String, RuntimeException> drift) {
        if (node == null || !node.isObject()) {
            throw drift.apply("入库单响应不是对象，契约已漂移");
        }
        JsonNode linesNode = node.path("lines");
        if (!linesNode.isArray()) {
            throw drift.apply("入库单缺少 lines 数组，契约已漂移");
        }
        List<YuanliaokcInboundOrder.Line> lines = new ArrayList<>(linesNode.size());
        for (JsonNode line : linesNode) {
            lines.add(inboundLine(line, drift));
        }
        return new YuanliaokcInboundOrder(
                requiredLong(node, "id", drift),
                requiredText(node, "order_no", drift),
                optionalText(node, "supplier_name", drift),
                requiredLong(node, "warehouse_id", drift),
                requiredText(node, "warehouse_name", drift),
                requiredText(node, "status", drift),
                optionalText(node, "notes", drift),
                requiredText(node, "created_at", drift),
                List.copyOf(lines));
    }

    private static YuanliaokcInboundOrder.Line inboundLine(
            JsonNode node, Function<String, RuntimeException> drift) {
        if (!node.isObject()) {
            throw drift.apply("入库单行不是对象，契约已漂移");
        }
        return new YuanliaokcInboundOrder.Line(
                requiredLong(node, "id", drift),
                requiredLong(node, "material_id", drift),
                requiredText(node, "material_name", drift),
                optionalText(node, "batch_no", drift),
                optionalText(node, "supplier_batch_no", drift),
                optionalLong(node, "piece_count", drift),
                // 上游 InboundLineIn 声明 quantity_kg 必须 > 0（Field(gt=0)），非正即口径变了
                requiredPositiveDecimal(node, "quantity_kg", drift),
                optionalText(node, "production_date", drift),
                optionalText(node, "expiry_date", drift),
                optionalLong(node, "created_batch_id", drift));
    }

    static YuanliaokcScrapOrder scrapOrder(JsonNode node, Function<String, RuntimeException> drift) {
        if (node == null || !node.isObject()) {
            throw drift.apply("报废单响应不是对象，契约已漂移");
        }
        return new YuanliaokcScrapOrder(
                requiredLong(node, "id", drift),
                requiredText(node, "order_no", drift),
                requiredLong(node, "batch_id", drift),
                requiredText(node, "batch_no", drift),
                requiredText(node, "material_name", drift),
                optionalLong(node, "piece_count", drift),
                // 上游 ScrapCreate 声明 quantity_kg 必须 > 0（Field(gt=0)）
                requiredPositiveDecimal(node, "quantity_kg", drift),
                requiredText(node, "reason", drift),
                requiredText(node, "status", drift),
                requiredText(node, "created_at", drift));
    }

    static YuanliaokcStockTransaction transaction(
            JsonNode node, Function<String, RuntimeException> drift) {
        if (node == null || !node.isObject()) {
            throw drift.apply("流水行不是对象，契约已漂移");
        }
        return new YuanliaokcStockTransaction(
                requiredLong(node, "id", drift),
                requiredLong(node, "material_id", drift),
                optionalText(node, "material_name", drift),
                optionalLong(node, "batch_id", drift),
                optionalText(node, "batch_no", drift),
                requiredText(node, "transaction_type", drift),
                // 变动量可负（出库/报废/冲销），结存量不做符号断言（见 record 注释），只要求是数值
                requiredDecimal(node, "quantity_change_kg", drift),
                requiredDecimal(node, "quantity_after_kg", drift),
                optionalText(node, "source_document_type", drift),
                optionalLong(node, "source_document_id", drift),
                optionalText(node, "notes", drift),
                optionalLong(node, "operator_id", drift),
                requiredText(node, "created_at", drift));
    }

    // ------------------------------------------------------------------
    // 字段级判定：与 YuanliaokcReadClient 的结存解析同一口径
    // ------------------------------------------------------------------

    private static String requiredText(
            JsonNode node, String field, Function<String, RuntimeException> drift) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw drift.apply("缺少必填字段或类型不符: " + field);
        }
        return value.asText();
    }

    private static String optionalText(
            JsonNode node, String field, Function<String, RuntimeException> drift) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw drift.apply("字段类型不符: " + field);
        }
        return value.asText();
    }

    private static long requiredLong(
            JsonNode node, String field, Function<String, RuntimeException> drift) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw drift.apply("缺少必填字段或类型不符: " + field);
        }
        return value.asLong();
    }

    private static Long optionalLong(
            JsonNode node, String field, Function<String, RuntimeException> drift) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw drift.apply("字段类型不符: " + field);
        }
        return value.asLong();
    }

    private static BigDecimal requiredDecimal(
            JsonNode node, String field, Function<String, RuntimeException> drift) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw drift.apply("缺少必填字段或类型不符: " + field);
        }
        return value.decimalValue();
    }

    private static BigDecimal requiredPositiveDecimal(
            JsonNode node, String field, Function<String, RuntimeException> drift) {
        BigDecimal decimal = requiredDecimal(node, field, drift);
        if (decimal.signum() <= 0) {
            throw drift.apply("字段必须为正数: " + field);
        }
        return decimal;
    }
}
