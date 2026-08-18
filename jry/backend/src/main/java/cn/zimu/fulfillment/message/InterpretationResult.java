package cn.zimu.fulfillment.message;

import java.util.Map;

/**
 * 一次解释的版本化派生结果。
 *
 * <p>供应商、模型名与提示词版本随结果保存；error 非空表示解释本身失败（例如模型不可用），
 * 此时 intent 必须为 NEED_REVIEW。structuredOutput 只包含模型原始输出，不包含任何已确认的内部 ID。
 */
public record InterpretationResult(
        MessageIntent intent,
        Map<String, Object> structuredOutput,
        String provider,
        String model,
        String promptVersion,
        String error) {}
