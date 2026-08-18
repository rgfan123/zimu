package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.math.BigDecimal;

/**
 * 回传发货结果（ticket 01，Phase 2 契约，签名见设计文档 §4.1）。
 *
 * <p>输入只用内部标准字段：来源引用、来源行引用、实际发货数量、履约结果、
 * 来源渠道承运商输出值、首批运单号与异常原因；不得把平台表格列名泄露到领域层。</p>
 */
public record SourceShipmentResult(
        SourceChannel channel,
        String sourceRef,
        String sourceLineRef,
        BigDecimal actualShippedQuantity,
        String outcome,
        String carrierOutputValue,
        String firstTrackingNo,
        String exceptionReason) {
}
