package cn.zimu.fulfillment.agent.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 履约单据 Agent 的结构化输入：{@code {"import_batch_id": "..."}}。
 *
 * <p>非法输入在**进入模型之前**拒绝（INVALID_PARAMETERS）。让模型去处理
 * 「批次号写错了」这种事，既费 token 又得不到确定的答案。
 */
public record FulfillmentFileInput(long importBatchId) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static FulfillmentFileInput parse(String json) {
        if (json == null || json.isBlank()) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "履约单据 Agent 输入必须是结构化 JSON，且不能为空");
        }
        final JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception ex) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "履约单据 Agent 输入不是合法 JSON");
        }
        if (!node.isObject()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "履约单据 Agent 输入必须是 JSON 对象");
        }
        JsonNode raw = node.path("import_batch_id");
        if (raw.isMissingNode() || raw.isNull()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "缺少 import_batch_id");
        }
        String text = raw.isTextual() ? raw.asText().trim() : raw.asText();
        if (!text.matches("^[1-9][0-9]{0,18}$")) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "import_batch_id 必须是正整数: " + text);
        }
        return new FulfillmentFileInput(Long.parseLong(text));
    }

    /** 传给模型的用户输入：仍是结构化 JSON，保持与定义 input_format 的约定一致。 */
    public String toUserInput() {
        return "{\"import_batch_id\":\"" + importBatchId + "\"}";
    }
}
