package cn.zimu.fulfillment.order.dto;

import java.util.List;
import java.util.Map;

/** 订单草稿行的白名单视图：模型原值、SKU 候选与确认后的 SKU 引用。 */
public record OrderDraftLineDto(
        String id,
        int lineNo,
        String skuId,
        String skuCode,
        List<Map<String, Object>> skuCandidates,
        String productNameRaw,
        String specRaw,
        String unitRaw,
        String quantity) {}
