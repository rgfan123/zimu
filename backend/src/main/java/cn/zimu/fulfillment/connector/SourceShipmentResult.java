package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;

/**
 * 回传发货结果（ticket 01，Phase 2 契约，签名见设计文档 §4.1）。
 *
 * <p>输入只用内部标准字段：来源引用、来源行引用、实际发货数量、履约结果、
 * 来源渠道承运商输出值、首批运单号与异常原因；不得把平台表格列名泄露到领域层。</p>
 *
 * <p>{@code sourceUnitQuantity} 仅在本地数量无法精确换算、结果只用于展示阻断检查时可为
 * {@code null}；任何 Connector 写入路径都必须先拒绝该空值，不能伪造 0 份。</p>
 */
public record SourceShipmentResult(
        SourceChannel channel,
        String sourceRef,
        String sourceLineRef,
        long actualShippedQuantity,
        Long sourceUnitQuantity,
        String outcome,
        String carrierOutputValue,
        String firstTrackingNo,
        String exceptionReason,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        Long shipmentId,
        SourceShipmentArtifact artifact,
        String expectedPlatformEffectHash) {

    public SourceShipmentResult(
            SourceChannel channel,
            String sourceRef,
            String sourceLineRef,
            long actualShippedQuantity,
            Long sourceUnitQuantity,
            String outcome,
            String carrierOutputValue,
            String firstTrackingNo,
            String exceptionReason,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            Long shipmentId,
            SourceShipmentArtifact artifact) {
        this(channel, sourceRef, sourceLineRef, actualShippedQuantity, sourceUnitQuantity, outcome,
                carrierOutputValue, firstTrackingNo, exceptionReason, receiverName, receiverPhone,
                receiverAddress, shipmentId, artifact, null);
    }

    /** 兼容已恢复 Adapter 的旧扩展构造；旧调用默认两种数量口径相同。 */
    public SourceShipmentResult(
            SourceChannel channel,
            String sourceRef,
            String sourceLineRef,
            long actualShippedQuantity,
            String outcome,
            String carrierOutputValue,
            String firstTrackingNo,
            String exceptionReason,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            Long shipmentId,
            SourceShipmentArtifact artifact) {
        this(channel, sourceRef, sourceLineRef, actualShippedQuantity, actualShippedQuantity, outcome,
                carrierOutputValue, firstTrackingNo, exceptionReason, receiverName, receiverPhone,
                receiverAddress, shipmentId, artifact, null);
    }

    /** 兼容既有 Connector 调用；旧路径默认内部数量与来源份数相同。 */
    public SourceShipmentResult(
            SourceChannel channel,
            String sourceRef,
            String sourceLineRef,
            long actualShippedQuantity,
            String outcome,
            String carrierOutputValue,
            String firstTrackingNo,
            String exceptionReason) {
        this(channel, sourceRef, sourceLineRef, actualShippedQuantity, actualShippedQuantity, outcome,
                carrierOutputValue, firstTrackingNo, exceptionReason, null, null, null, null,
                SourceShipmentArtifact.empty(), null);
    }

    public SourceShipmentResult withExpectedPlatformEffectHash(String effectHash) {
        return new SourceShipmentResult(
                channel, sourceRef, sourceLineRef, actualShippedQuantity, sourceUnitQuantity, outcome,
                carrierOutputValue, firstTrackingNo, exceptionReason, receiverName, receiverPhone,
                receiverAddress, shipmentId, artifact, effectHash);
    }
}
