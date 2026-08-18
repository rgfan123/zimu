package cn.zimu.fulfillment.message;

import java.util.List;

/** 解释器输入：仅包含已入库的证据与受控媒体引用，不携带任何内部主数据。 */
public record InterpretationInput(
        long submissionId,
        String content,
        String quoteType,
        String quoteContent,
        List<String> mediaContentRefs) {}
