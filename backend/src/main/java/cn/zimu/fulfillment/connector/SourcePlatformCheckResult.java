package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;

/** 平台最新来源子单事实；确定性差异裁决仍由 source-sync Policy 完成。 */
public record SourcePlatformCheckResult(
        boolean available,
        String businessCode,
        String message,
        String platformState,
        boolean acceptanceRequired,
        AddressStatus addressStatus,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        Long sendableQuantity,
        boolean carrierMapped,
        String effectHash) {

    public SourcePlatformCheckResult {
        addressStatus = addressStatus == null ? AddressStatus.UNKNOWN : addressStatus;
    }

    public SourcePlatformCheckResult(
            boolean available,
            String businessCode,
            String message,
            String platformState,
            boolean acceptanceRequired,
            AddressStatus addressStatus,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            Long sendableQuantity,
            boolean carrierMapped) {
        this(available, businessCode, message, platformState, acceptanceRequired, addressStatus,
                receiverName, receiverPhone, receiverAddress, sendableQuantity, carrierMapped, null);
    }

    public static SourcePlatformCheckResult unavailable(SourceChannel channel) {
        return unavailable(channel, "CONNECTOR_CAPABILITY_UNAVAILABLE", "该渠道在线检查尚未接入: " + channel);
    }

    public static SourcePlatformCheckResult unavailable(
            SourceChannel channel,
            String businessCode,
            String message) {
        return new SourcePlatformCheckResult(
                false,
                businessCode == null || businessCode.isBlank() ? "CONNECTOR_CAPABILITY_UNAVAILABLE" : businessCode,
                message == null || message.isBlank() ? "该渠道在线检查不可用: " + channel : message,
                null,
                false,
                AddressStatus.UNKNOWN,
                null,
                null,
                null,
                null,
                false,
                null);
    }

    public enum AddressStatus {
        CLEAR,
        CONFIRMATION_REQUIRED,
        UNKNOWN
    }
}
