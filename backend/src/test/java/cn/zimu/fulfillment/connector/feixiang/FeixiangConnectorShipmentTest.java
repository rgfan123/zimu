package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 飞象在线回传的 Connector 契约：五道安全要求的行为断言。
 *
 * <p>网关全部打替身，<b>绝不</b>触真实平台。</p>
 */
class FeixiangConnectorShipmentTest {

    private FeixiangShipmentLineage lineage;
    private FeixiangCarrierCodeResolver carriers;
    private FeixiangShipmentTestSupport.InMemoryFeixiangShipmentAttemptStore store;

    @BeforeEach
    void setUp() {
        lineage = mock(FeixiangShipmentLineage.class);
        carriers = mock(FeixiangCarrierCodeResolver.class);
        store = new FeixiangShipmentTestSupport.InMemoryFeixiangShipmentAttemptStore();
        when(lineage.resolve(77L)).thenReturn(
                new FeixiangShipmentLineage.Resolution(
                        FeixiangShipmentTestSupport.ORDER_SON_ID, "OK", "ok"));
        when(carriers.resolve(FeixiangShipmentTestSupport.CARRIER_DISPLAY)).thenReturn(
                new FeixiangCarrierCodeResolver.Resolution(
                        FeixiangShipmentTestSupport.EXPRESS_CODE, "OK", "ok"));
    }

    // ---------------------------------------------------------------- 要求 1：默认关闭

    @Test
    void writeModeOffKeepsOnlinePushCapabilityFalseSoTheWecomFilePathStaysAlive() {
        assertThat(fixture("OFF").connector.capabilities().onlinePush()).isFalse();
        assertThat(fixture("DRY_RUN").connector.capabilities().onlinePush()).isFalse();
        assertThat(fixture("ARMED").connector.capabilities().onlinePush()).isTrue();
        assertThat(fixture("ON").connector.capabilities().onlinePush()).isTrue();
    }

    @Test
    void writeModeOffRefusesBeforeClaimingAnyIdempotencyRowOrTouchingThePlatform() {
        Fixture fixture = fixture("OFF");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("FEIXIANG_WRITE_MODE_DISABLED");
        assertThat(fixture.gateway.submittedBodies).isEmpty();
        assertThat(fixture.gateway.detailCalls).isZero();
        assertThat(store.externalEffectCount()).isZero();
    }

    @Test
    void unguardedSingleArgumentPushIsBlockedOutright() {
        SourceSyncResult result = fixture("ON").connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null));

        assertThat(result.businessCode()).isEqualTo("SOURCE_SYNC_EXECUTION_CONTEXT_REQUIRED");
    }

    @Test
    void missingWritePermitIsRefused() {
        SourceSyncResult result = fixture("ON").connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), null);

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_WRITE_PERMIT_REQUIRED");
    }

    // ---------------------------------------------------------------- 要求 2：dry-run 预览

    @Test
    void previewBuildsTheExactPayloadWithoutEmittingAnyWrite() {
        Fixture fixture = fixture("DRY_RUN");

        FeixiangShipmentPlanner.WritePlan plan =
                fixture.connector.previewShipmentWrite(FeixiangShipmentTestSupport.shipmentResult(null));

        assertThat(plan.available()).isTrue();
        assertThat(plan.request().formBody()).isEqualTo(
                "order_product_ids%5B%5D=" + FeixiangShipmentTestSupport.ORDER_PRODUCT_ID
                        + "&sn=" + FeixiangShipmentTestSupport.TRACKING_NO
                        + "&express_code=" + FeixiangShipmentTestSupport.EXPRESS_CODE
                        + "&delivery_remark=");
        assertThat(fixture.gateway.submittedBodies).isEmpty();
    }

    // ---------------------------------------------------------------- 要求 4：写后回查

    @Test
    void acceptedPlusConfirmedTrackingIsTheOnlyPathToSuccess() {
        Fixture fixture = fixture("ON");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.success()).isTrue();
        assertThat(result.platformRef())
                .isEqualTo("order_son_id:" + FeixiangShipmentTestSupport.ORDER_SON_ID);
        assertThat(fixture.gateway.submittedBodies).hasSize(1);
    }

    @Test
    void equalSourceQuantitiesOutsideLongCacheDoNotLookLikeDrift() {
        Fixture fixture = fixture("ON");
        fixture.gateway.beforeDetail = FeixiangShipmentTestSupport.detail(
                "", "", "200", FeixiangShipmentTestSupport.ORDER_PRODUCT_ID);
        fixture.gateway.afterDetail = FeixiangShipmentTestSupport.detail(
                FeixiangShipmentTestSupport.TRACKING_NO,
                FeixiangShipmentTestSupport.EXPRESS_CODE,
                "200",
                FeixiangShipmentTestSupport.ORDER_PRODUCT_ID);

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                withSourceQuantity(FeixiangShipmentTestSupport.shipmentResult(null), 200L),
                () -> {});

        assertThat(result.success()).isTrue();
    }

    @Test
    void acceptedButUnverifiedTrackingGoesToReconciliationNotSuccess() {
        Fixture fixture = fixture("ON");
        // 平台受理了，但写后读回来目标行仍没有我们的运单号。
        fixture.gateway.afterDetail = FeixiangShipmentTestSupport.detail("", "");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void someoneElsesTrackingOnThePlatformIsNotOurSuccess() {
        Fixture fixture = fixture("ON");
        fixture.gateway.afterDetail = FeixiangShipmentTestSupport.detail("SF0001", "shunfeng");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void unknownWriteResponseGoesToReconciliationAndIsNeverRetryable() {
        Fixture fixture = fixture("ON");
        fixture.gateway.submitResult = FeixiangShipmentGateway.SubmitResult.unknown("未知 status");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");

        // 同一 payload 再来一次只会重放对账结果，绝不重新提交。
        SourceSyncResult replay = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});
        assertThat(replay.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(fixture.gateway.submittedBodies).hasSize(1);
    }

    @Test
    void explicitPlatformRejectionIsASafeFailureThatMayBeRetried() {
        Fixture fixture = fixture("ON");
        fixture.gateway.submitResult =
                FeixiangShipmentGateway.SubmitResult.rejected("FEIXIANG_SHIPMENT_REJECTED", "运单被拒绝");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_SHIPMENT_REJECTED");
        assertThat(result.businessCode()).isNotEqualTo("RECONCILIATION_REQUIRED");
    }

    // ---------------------------------------------------------------- 要求 5：幂等

    @Test
    void identicalRetryReplaysTheStoredSuccessWithoutASecondPlatformWrite() {
        Fixture fixture = fixture("ON");
        SourceShipmentResult result = FeixiangShipmentTestSupport.shipmentResult(null);

        assertThat(fixture.connector.pushShipmentResult(result, () -> {}).success()).isTrue();
        assertThat(fixture.connector.pushShipmentResult(result, () -> {}).success()).isTrue();

        assertThat(fixture.gateway.submittedBodies).hasSize(1);
    }

    @Test
    void changingCarrierOnTheSameSubOrderAndTrackingIsAConflictNotANewKey() {
        Fixture fixture = fixture("ON");
        fixture.connector.pushShipmentResult(FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        SourceShipmentResult switched = withCarrier(
                FeixiangShipmentTestSupport.shipmentResult(null), "顺丰速运");
        SourceSyncResult result = fixture.connector.pushShipmentResult(switched, () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_IDEMPOTENCY_CONFLICT");
        assertThat(fixture.gateway.submittedBodies).hasSize(1);
    }

    @Test
    void armedGateAllowsExactlyOneRealWriteEvenIfSomethingKeepsCalling() {
        Fixture fixture = fixture("ARMED");
        assertThat(fixture.connector
                        .pushShipmentResult(FeixiangShipmentTestSupport.shipmentResult(null), () -> {})
                        .success())
                .isTrue();

        // 换一个未发货的目标：写前门禁全过，唯一挡住第二次写的必须是布防本身。
        fixture.gateway.afterDetail = FeixiangShipmentTestSupport.detail("", "");
        SourceShipmentResult another = withTracking(
                FeixiangShipmentTestSupport.shipmentResult(null), "JDVA99999999999");
        SourceSyncResult second = fixture.connector.pushShipmentResult(another, () -> {});

        assertThat(second.businessCode()).isEqualTo("FEIXIANG_FIRST_WRITE_ARMING_CONSUMED");
        assertThat(fixture.gateway.submittedBodies).hasSize(1);
    }

    // ---------------------------------------------------------------- 写前只读门禁

    @Test
    void alreadyShippedLineIsNeverOverwritten() {
        Fixture fixture = fixture("ON");
        fixture.gateway.beforeDetail = FeixiangShipmentTestSupport.detail("SF0001", "shunfeng");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_ORDER_NOT_SHIPPABLE");
        assertThat(fixture.gateway.submittedBodies).isEmpty();
    }

    @Test
    void unmappedCarrierBlocksBeforeAnyWrite() {
        Fixture fixture = fixture("ON");
        when(carriers.resolve(FeixiangShipmentTestSupport.CARRIER_DISPLAY)).thenReturn(
                new FeixiangCarrierCodeResolver.Resolution(
                        null, "FEIXIANG_CARRIER_API_CODE_MISSING", "未配置"));

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_CARRIER_API_CODE_MISSING");
        assertThat(fixture.gateway.submittedBodies).isEmpty();
    }

    @Test
    void quantityDriftBetweenInternalAndPlatformBlocksBeforeAnyWrite() {
        Fixture fixture = fixture("ON");
        fixture.gateway.beforeDetail = FeixiangShipmentTestSupport.detail(
                "", "", "3", FeixiangShipmentTestSupport.ORDER_PRODUCT_ID);

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_SHIPMENT_QUANTITY_MISMATCH");
        assertThat(fixture.gateway.submittedBodies).isEmpty();
    }

    @Test
    void writePlanDriftAfterConfirmationBlocksBeforeAnyWrite() {
        Fixture fixture = fixture("ON");

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult("0".repeat(64)), () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_WRITE_PLAN_CHANGED");
        assertThat(fixture.gateway.submittedBodies).isEmpty();
    }

    @Test
    void unresolvableLineageBlocksBeforeAnyPlatformCall() {
        Fixture fixture = fixture("ON");
        when(lineage.resolve(77L)).thenReturn(
                new FeixiangShipmentLineage.Resolution(
                        null, "FEIXIANG_ORDER_SON_ID_REQUIRED", "Excel 批次不支持在线回传"));

        SourceSyncResult result = fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        assertThat(result.businessCode()).isEqualTo("FEIXIANG_ORDER_SON_ID_REQUIRED");
        assertThat(fixture.gateway.detailCalls).isZero();
    }

    @Test
    void permitIsConsumedExactlyOncePerExternalWrite() {
        Fixture fixture = fixture("ON");
        AtomicInteger permits = new AtomicInteger();

        fixture.connector.pushShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null), permits::incrementAndGet);

        assertThat(permits).hasValue(1);
    }

    // ---------------------------------------------------------------- 平台检查

    @Test
    void checkExposesPlatformStateQuantityAndCarrierMapping() {
        Fixture fixture = fixture("ON");

        SourcePlatformCheckResult check = fixture.connector.checkShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null));

        assertThat(check.available()).isTrue();
        assertThat(check.platformState()).isEqualTo("SHIPPABLE");
        assertThat(check.carrierMapped()).isTrue();
        assertThat(check.sendableQuantity()).isEqualTo(1L);
        assertThat(check.addressStatus()).isEqualTo(SourcePlatformCheckResult.AddressStatus.CLEAR);
        assertThat(check.effectHash()).isNotBlank();
    }

    @Test
    void checkReportsAlreadyShippedInsteadOfGuessingExpressStateCodes() {
        Fixture fixture = fixture("ON");
        fixture.gateway.beforeDetail = FeixiangShipmentTestSupport.detail("SF0001", "shunfeng");

        SourcePlatformCheckResult check = fixture.connector.checkShipmentResult(
                FeixiangShipmentTestSupport.shipmentResult(null));

        assertThat(check.platformState()).isEqualTo("ALREADY_SHIPPED");
    }

    @Test
    void reconciledNotAcceptedReleasesTheInnerIntentKey() {
        Fixture fixture = fixture("ON");
        fixture.gateway.submitResult = FeixiangShipmentGateway.SubmitResult.unknown("未知");
        fixture.connector.pushShipmentResult(FeixiangShipmentTestSupport.shipmentResult(null), () -> {});

        String intentKey = FeixiangShipmentAttemptStore.idempotencyKey(
                FeixiangShipmentTestSupport.SUB_ORDER_SN, FeixiangShipmentTestSupport.TRACKING_NO);

        assertThat(fixture.connector.releaseShipmentIntent(intentKey)).isTrue();
    }

    // ---------------------------------------------------------------- 脚手架

    private record Fixture(FeixiangConnector connector, FeixiangShipmentTestSupport.StubGateway gateway) {}

    private Fixture fixture(String mode) {
        FeixiangShipmentWriteGate gate = new FeixiangShipmentWriteGate(mode, store);
        FeixiangShipmentTestSupport.StubGateway gateway =
                new FeixiangShipmentTestSupport.StubGateway(gate);
        FeixiangShipmentPlanner planner = new FeixiangShipmentPlanner(lineage, carriers, gateway);
        FeixiangConnector connector = new FeixiangConnector(
                mock(SourceImportService.class),
                mock(FeixiangPullClient.class),
                new FeixiangOrderTransform(),
                planner,
                gateway,
                store,
                gate);
        return new Fixture(connector, gateway);
    }

    private static SourceShipmentResult withCarrier(SourceShipmentResult base, String carrier) {
        return new SourceShipmentResult(
                base.channel(), base.sourceRef(), base.sourceLineRef(), base.actualShippedQuantity(),
                base.sourceUnitQuantity(), base.outcome(), carrier, base.firstTrackingNo(),
                base.exceptionReason(), base.receiverName(), base.receiverPhone(),
                base.receiverAddress(), base.shipmentId(), base.artifact());
    }

    private static SourceShipmentResult withTracking(SourceShipmentResult base, String trackingNo) {
        return new SourceShipmentResult(
                base.channel(), base.sourceRef(), base.sourceLineRef(), base.actualShippedQuantity(),
                base.sourceUnitQuantity(), base.outcome(), base.carrierOutputValue(), trackingNo,
                base.exceptionReason(), base.receiverName(), base.receiverPhone(),
                base.receiverAddress(), base.shipmentId(), base.artifact());
    }

    private static SourceShipmentResult withSourceQuantity(SourceShipmentResult base, long sourceQuantity) {
        return new SourceShipmentResult(
                base.channel(), base.sourceRef(), base.sourceLineRef(), base.actualShippedQuantity(),
                sourceQuantity, base.outcome(), base.carrierOutputValue(), base.firstTrackingNo(),
                base.exceptionReason(), base.receiverName(), base.receiverPhone(),
                base.receiverAddress(), base.shipmentId(), base.artifact());
    }
}
