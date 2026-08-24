package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceReceiverNormalizer;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 确定性差异裁决。仅保守格式归一；绝不以模糊相似度放行姓名或地址。 */
@Component
public final class SourceSyncPolicy {

    private final SourceSyncHash hashes;

    public SourceSyncPolicy(SourceSyncHash hashes) {
        this.hashes = hashes;
    }

    public SourceSyncCheck evaluate(
            SourceSyncFactsReader.Loaded loaded,
            SourcePlatformCheckResult platform,
            SourceShipmentArtifact artifact) {
        SourceSyncFacts internal = loaded.facts();
        List<SourceSyncBlocker> blockers = new ArrayList<>(loaded.blockers());
        if (!platform.available()) {
            if (blockers.isEmpty()) {
                block(blockers, platform.businessCode(), "platform", "平台当前事实不可用，禁止执行回传");
            }
        } else {
            if (!writableState(internal.sourceChannel(), platform.platformState())) {
                block(blockers, "SOURCE_PLATFORM_STATE_NOT_WRITABLE", "platform_state",
                        "来源订单当前状态不允许执行 P0 在线回传");
            }
            if (platform.addressStatus() == SourcePlatformCheckResult.AddressStatus.CONFIRMATION_REQUIRED) {
                block(blockers, "SOURCE_PLATFORM_ADDRESS_CONFIRMATION_REQUIRED", "receiver.address",
                        "平台提示收货地址已变化，必须先在平台确认");
            } else if (platform.addressStatus() == SourcePlatformCheckResult.AddressStatus.UNKNOWN) {
                block(blockers, "SOURCE_PLATFORM_ADDRESS_STATUS_UNKNOWN", "receiver.address",
                        "平台地址确认状态未知，禁止执行回传");
            }
            if (!platform.carrierMapped()) {
                block(blockers, "SOURCE_PLATFORM_CARRIER_UNMAPPED", "carrier", "正式物流公司未命中平台实时字典");
            }
            compareReceiver(blockers, internal, platform);
            compareQuantity(blockers, internal.shippedSourceQuantity(), platform.sendableQuantity());
        }
        String artifactHash = artifactHash(internal, artifact);
        StablePlatform stablePlatform = new StablePlatform(
                platform.available(), platform.businessCode(), platform.platformState(),
                platform.acceptanceRequired(), platform.addressStatus(), platform.receiverName(),
                platform.receiverPhone(), platform.receiverAddress(), platform.sendableQuantity(),
                platform.carrierMapped(), platform.effectHash());
        String checkHash = hashes.hash(new CheckHashInput(
                artifactHash, stablePlatform, blockers, loaded.projection().status(),
                loaded.projection().attemptCount(), loaded.projection().lockVersion()));
        return new SourceSyncCheck(
                internal.shipmentId(), blockers.isEmpty(), checkHash, artifactHash, internal,
                platform, blockers, loaded.projection(), loaded.reconciliationIntent());
    }

    public String artifactHash(SourceSyncFacts facts, SourceShipmentArtifact artifact) {
        StableArtifact stableArtifact = new StableArtifact(
                artifact != null && artifact.present(), artifact == null ? null : artifact.sha256());
        return hashes.hash(new ArtifactHashInput(facts, stableArtifact));
    }

    private static void compareReceiver(
            List<SourceSyncBlocker> blockers,
            SourceSyncFacts internal,
            SourcePlatformCheckResult platform) {
        if (!SourceReceiverNormalizer.sameName(internal.receiverName(), platform.receiverName())) {
            block(blockers, "SOURCE_RECEIVER_NAME_MISMATCH", "receiver.name", "内部与平台收货人姓名不一致");
        }
        if (!SourceReceiverNormalizer.samePhone(internal.receiverPhone(), platform.receiverPhone())) {
            block(blockers, "SOURCE_RECEIVER_PHONE_MISMATCH", "receiver.phone", "内部与平台收货电话不一致");
        }
        if (!SourceReceiverNormalizer.sameAddress(internal.receiverAddress(), platform.receiverAddress())) {
            block(blockers, "SOURCE_RECEIVER_ADDRESS_MISMATCH", "receiver.address", "内部与平台收货地址不一致");
        }
    }

    private static void compareQuantity(
            List<SourceSyncBlocker> blockers, BigDecimal internal, BigDecimal platform) {
        if (internal == null || platform == null || internal.compareTo(platform) != 0) {
            block(blockers, "SOURCE_PLATFORM_SENDABLE_QUANTITY_MISMATCH", "sendable_source_quantity",
                    "内部拟回传来源份数与平台当前可发数量不一致");
        }
    }

    private static boolean writableState(
            cn.zimu.fulfillment.common.domain.SourceChannel channel,
            String state) {
        if (channel == cn.zimu.fulfillment.common.domain.SourceChannel.JUFUBAO) {
            return "NO_RECEIPT".equals(state) || "NO_DELIVERY".equals(state);
        }
        if (channel == cn.zimu.fulfillment.common.domain.SourceChannel.CAISHIXIAN) {
            return "3".equals(state);
        }
        return false;
    }

    private static void block(List<SourceSyncBlocker> blockers, String code, String field, String message) {
        String stable = code == null || code.isBlank() ? "SOURCE_PLATFORM_CHECK_FAILED" : code;
        if (blockers.stream().noneMatch(item -> stable.equals(item.code()))) {
            blockers.add(new SourceSyncBlocker(stable, field, message));
        }
    }

    private record StableArtifact(boolean present, String sha256) {}
    private record ArtifactHashInput(SourceSyncFacts facts, StableArtifact artifact) {}
    private record StablePlatform(boolean available, String businessCode, String platformState,
            boolean acceptanceRequired, SourcePlatformCheckResult.AddressStatus addressStatus,
            String receiverName, String receiverPhone, String receiverAddress,
            BigDecimal sendableQuantity, boolean carrierMapped, String effectHash) {}
    private record CheckHashInput(String artifactHash, StablePlatform platform, List<SourceSyncBlocker> blockers,
            SourceSyncStatus status, int attemptCount, long version) {}
}
