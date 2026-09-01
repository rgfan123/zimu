package cn.zimu.fulfillment.connector.sync;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceSyncPolicyTest {

    private final SourceSyncPolicy policy =
            new SourceSyncPolicy(new SourceSyncHash(new ObjectMapper().findAndRegisterModules()));

    @Test
    void ordinaryPhoneFormattingDoesNotHideAnExactReceiverAndQuantityMatch() {
        SourceSyncCheck check = policy.evaluate(
                loaded(facts("138 0000-0000", "河南省 郑州市 1号")),
                platform("13800000000", "河南省 郑州市 1号", 1L),
                SourceShipmentArtifact.empty());

        assertThat(check.ready()).isTrue();
        assertThat(check.blockers()).isEmpty();
        assertThat(check.checkHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void receiverOrSourceQuantityDifferenceIsDeterministicallyBlocking() {
        SourceSyncCheck check = policy.evaluate(
                loaded(facts("13800000000", "河南省郑州市1号")),
                platform("13900000000", "河南省郑州市2号", 2L),
                SourceShipmentArtifact.empty());

        assertThat(check.ready()).isFalse();
        assertThat(check.blockers()).extracting(SourceSyncBlocker::code)
                .contains(
                        "SOURCE_RECEIVER_PHONE_MISMATCH",
                        "SOURCE_RECEIVER_ADDRESS_MISMATCH",
                        "SOURCE_PLATFORM_SENDABLE_QUANTITY_MISMATCH");
    }

    @Test
    void changedUploadArtifactInvalidatesBothArtifactAndCheckHashes() {
        SourceSyncCheck first = policy.evaluate(
                loaded(facts("13800000000", "河南省郑州市1号")),
                platform("13800000000", "河南省郑州市1号", 1L),
                new SourceShipmentArtifact("a.xlsx", "application/xlsx", new byte[] {1}, "a".repeat(64)));
        SourceSyncCheck changed = policy.evaluate(
                loaded(facts("13800000000", "河南省郑州市1号")),
                platform("13800000000", "河南省郑州市1号", 1L),
                new SourceShipmentArtifact("a.xlsx", "application/xlsx", new byte[] {2}, "b".repeat(64)));

        assertThat(changed.artifactHash()).isNotEqualTo(first.artifactHash());
        assertThat(changed.checkHash()).isNotEqualTo(first.checkHash());
    }

    @Test
    void nonWritablePlatformStateFailsClosedEvenWhenReceiverAndQuantityMatch() {
        SourcePlatformCheckResult delivered = new SourcePlatformCheckResult(
                true, "OK", "state", "DELIVERED", false,
                SourcePlatformCheckResult.AddressStatus.CLEAR,
                "张三", "13800000000", "河南省郑州市1号", 1L, true);

        SourceSyncCheck check = policy.evaluate(
                loaded(facts("13800000000", "河南省郑州市1号")),
                delivered,
                SourceShipmentArtifact.empty());

        assertThat(check.ready()).isFalse();
        assertThat(check.blockers()).extracting(SourceSyncBlocker::code)
                .contains("SOURCE_PLATFORM_STATE_NOT_WRITABLE");
    }

    private SourceSyncFacts facts(String phone, String address) {
        return new SourceSyncFacts(
                7L, 8L, SourceChannel.JUFUBAO, "main-1", "sub-1", "张三", phone, address,
                1L, 1L, 1L, "FULLY_FULFILLED",
                "JD", "京东物流", "京东物流", "JDVA123");
    }

    private SourceSyncFactsReader.Loaded loaded(SourceSyncFacts facts) {
        return new SourceSyncFactsReader.Loaded(
                facts, List.of(), new SourceSyncProjection(SourceSyncStatus.PENDING, 0, 0, null, null, null));
    }

    private SourcePlatformCheckResult platform(String phone, String address, Long quantity) {
        return new SourcePlatformCheckResult(
                true, "OK", "ready", "NO_DELIVERY", false,
                SourcePlatformCheckResult.AddressStatus.CLEAR,
                "张三", phone, address, quantity, true);
    }
}
