package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 采购比价 Agent 的结构化输入（agent-decision-layer 05）。
 *
 * <p>输入契约：{@code procurement_ticket_id} 或 {@code sku_id} 至少其一（正整数 ID），
 * {@code quantity} 可选（正整数 JSON token，与订单行数量语义一致）。
 * 解析失败/校验失败抛 {@link BusinessException}（INVALID_PARAMETERS），服务调用方按
 * 4xx 错误处理，不进入模型调用。{@link #toUserInput()} 把归一化后的输入序列化为
 * JSON 传给模型。
 */
public record ProcurementPriceInput(String procurementTicketId, String skuId, Integer quantity) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID_PATTERN = "^[1-9][0-9]*$";

    /** 解析并校验结构化输入 JSON；任一字段缺失但未提供目标时拒绝。 */
    public static ProcurementPriceInput parse(String json) {
        if (json == null || json.isBlank()) {
            throw badRequest("输入不能为空，必须提供 procurement_ticket_id 或 sku_id 之一");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw badRequest("输入必须是 JSON 对象");
        }
        if (node == null || !node.isObject()) {
            throw badRequest("输入必须是 JSON 对象");
        }
        String ticketId = text(node, "procurement_ticket_id");
        String skuId = text(node, "sku_id");
        Integer quantity = count(node, "quantity");
        if (isBlank(ticketId) && isBlank(skuId)) {
            throw badRequest("必须提供 procurement_ticket_id 或 sku_id 之一");
        }
        if (!isBlank(ticketId) && !ticketId.matches(ID_PATTERN)) {
            throw badRequest("procurement_ticket_id 必须是正整数 ID");
        }
        if (!isBlank(skuId) && !skuId.matches(ID_PATTERN)) {
            throw badRequest("sku_id 必须是正整数 ID");
        }
        return new ProcurementPriceInput(
                blankToNull(ticketId), blankToNull(skuId), quantity);
    }

    /** 归一化输入序列化为 JSON（作为模型 userInput）。 */
    public String toUserInput() {
        var output = MAPPER.createObjectNode()
                .put("procurement_ticket_id", blankToNull(procurementTicketId))
                .put("sku_id", blankToNull(skuId));
        if (quantity == null) output.putNull("quantity");
        else output.put("quantity", quantity);
        return output.toString();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : String.valueOf(value);
    }

    private static Integer count(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw badRequest("quantity 必须为正整数 JSON 值");
        }
        try {
            return CountQuantity.fromPositiveJsonInteger(value.bigIntegerValue());
        } catch (InvalidCountQuantityException exception) {
            throw badRequest("quantity 必须为 int32 正整数");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private static BusinessException badRequest(String message) {
        return BusinessException.badRequest("INVALID_PARAMETERS", message);
    }
}
