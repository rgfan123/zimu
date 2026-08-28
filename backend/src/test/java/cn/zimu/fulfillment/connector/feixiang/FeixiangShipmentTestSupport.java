package cn.zimu.fulfillment.connector.feixiang;

import static org.mockito.Mockito.mock;

import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.file.SourceImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/** 飞象回传单元测试的共用替身：无 Spring、无数据库、<b>绝不</b>发出真实 HTTP。 */
final class FeixiangShipmentTestSupport {

    private FeixiangShipmentTestSupport() {}

    static final String SUB_ORDER_SN = "S2026826346818550490";
    static final String ORDER_SN = "D2026826346818550490";
    static final String ORDER_SON_ID = "24126510";
    static final String ORDER_PRODUCT_ID = "43231540";
    static final String TRACKING_NO = "JDVA46783539436";
    static final String CARRIER_DISPLAY = "京东物流";
    static final String EXPRESS_CODE = "jingdong";

    /** 只关心拉取的既有用例：回传依赖全部给不会被触发的替身，写门闩保持默认 OFF。 */
    static FeixiangConnector pullOnlyConnector(
            SourceImportService imports, FeixiangPullClient pullClient, FeixiangOrderTransform transform) {
        FeixiangShipmentAttemptStore store = new InMemoryFeixiangShipmentAttemptStore();
        return new FeixiangConnector(
                imports,
                pullClient,
                transform,
                mock(FeixiangShipmentPlanner.class),
                mock(FeixiangShipmentGateway.class),
                store,
                new FeixiangShipmentWriteGate("OFF", store));
    }

    static SourceShipmentResult shipmentResult(String expectedEffectHash) {
        SourceShipmentResult base = new SourceShipmentResult(
                SourceChannel.FEIXIANG,
                ORDER_SN,
                SUB_ORDER_SN,
                new BigDecimal("4"),
                new BigDecimal("1"),
                "SHIPPED",
                CARRIER_DISPLAY,
                TRACKING_NO,
                null,
                "张三",
                "13800000000",
                "上海市浦东新区某路 1 号",
                77L,
                SourceShipmentArtifact.empty());
        return expectedEffectHash == null ? base : base.withExpectedPlatformEffectHash(expectedEffectHash);
    }

    /** 平台详情替身：一行商品，pronum=1，未发货。 */
    static FeixiangOrderDetail detail(String sn, String expressCode) {
        return detail(sn, expressCode, "1", ORDER_PRODUCT_ID);
    }

    static FeixiangOrderDetail detail(String sn, String expressCode, String pronum, String orderProductId) {
        FeixiangOrderDetail.ReceiveInfo info = new FeixiangOrderDetail.ReceiveInfo(
                "9001", ORDER_SON_ID, ORDER_SN, SUB_ORDER_SN, "2", "1", "0",
                "2026-08-26 10:00:00", "2026-08-26 10:01:00", "",
                "张三", "13800000000", "上海市浦东新区", "上海市浦东新区某路 1 号");
        FeixiangOrderDetail.ProductLine line = new FeixiangOrderDetail.ProductLine(
                "9001", ORDER_SON_ID, orderProductId, "P1", "子牧羊小腿", "500g*4",
                pronum, "88.00", expressCode == null ? "" : expressCode, sn == null ? "" : sn,
                "", "待发货", "待发货", "", "供应商");
        return new FeixiangOrderDetail(info, List.of(line));
    }

    /** 可编排的网关替身；记录每一次 submit 的报文，且从不触网。 */
    static final class StubGateway implements FeixiangShipmentGateway {

        private final FeixiangShipmentWriteGate gate;
        FeixiangOrderDetail beforeDetail = detail("", "");
        FeixiangOrderDetail afterDetail = detail(TRACKING_NO, EXPRESS_CODE);
        SubmitResult submitResult = SubmitResult.accepted();
        RuntimeException detailFailure;
        final List<String> submittedBodies = new ArrayList<>();
        int detailCalls;
        int prepareCalls;

        StubGateway(FeixiangShipmentWriteGate gate) {
            this.gate = gate;
        }

        @Override
        public FeixiangShipmentWriteMode writeMode() {
            return gate.mode();
        }

        @Override
        public void prepareWrite() {
            prepareCalls++;
        }

        @Override
        public FeixiangOrderDetail orderDetail(String orderSonId) {
            detailCalls++;
            if (detailFailure != null) {
                throw detailFailure;
            }
            return submittedBodies.isEmpty() ? beforeDetail : afterDetail;
        }

        @Override
        public SubmitResult submit(String orderSonId, FeixiangShipmentRequest request) {
            FeixiangShipmentWriteGate.Decision decision = gate.inspectExternalWrite();
            if (!decision.allowed()) {
                return SubmitResult.notSent(decision.businessCode(), decision.message());
            }
            submittedBodies.add(request.formBody());
            return submitResult;
        }
    }

    /** 无 Spring/数据库的幂等 store 替身，保留真实实现的状态机语义。 */
    static final class InMemoryFeixiangShipmentAttemptStore implements FeixiangShipmentAttemptStore {

        private final ObjectMapper mapper = new ObjectMapper();
        private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public ClaimResult claim(ShipmentAttemptPayload payload) {
            String key = FeixiangShipmentAttemptStore.idempotencyKey(
                    payload.subOrderRef(), payload.trackingNo());
            String hash = FeixiangShipmentAttemptStore.payloadHash(mapper, payload);
            String owner = UUID.randomUUID().toString();
            AtomicReference<ClaimResult> decision = new AtomicReference<>();
            entries.compute(key, (ignored, existing) -> {
                if (existing == null) {
                    decision.set(ClaimResult.proceed(owner));
                    return new Entry(hash, owner);
                }
                if (!existing.payloadHash.equals(hash)) {
                    decision.set(ClaimResult.conflict());
                } else if (existing.status == Status.FAILED) {
                    decision.set(ClaimResult.proceed(owner));
                    Entry renewed = new Entry(hash, owner);
                    renewed.effectStarted = existing.effectStarted;
                    return renewed;
                } else if (existing.status == Status.SUCCEEDED) {
                    decision.set(ClaimResult.replay(existing.result));
                } else if (existing.status == Status.RECONCILIATION_REQUIRED) {
                    decision.set(ClaimResult.reconciliationRequired(existing.result));
                } else {
                    decision.set(ClaimResult.inProgress());
                }
                return existing;
            });
            return decision.get();
        }

        @Override
        public void markEffectStarted(String subOrderRef, String trackingNo, String ownerToken) {
            entry(subOrderRef, trackingNo, ownerToken).effectStarted = true;
        }

        @Override
        public void verifyWritePermit(String subOrderRef, String trackingNo, String ownerToken) {
            entry(subOrderRef, trackingNo, ownerToken);
        }

        @Override
        public void completeSuccess(
                String subOrderRef, String trackingNo, String ownerToken, SourceSyncResult result) {
            complete(subOrderRef, trackingNo, ownerToken, result, Status.SUCCEEDED);
        }

        @Override
        public void completeUnknown(
                String subOrderRef, String trackingNo, String ownerToken, SourceSyncResult result) {
            complete(subOrderRef, trackingNo, ownerToken, result, Status.RECONCILIATION_REQUIRED);
        }

        @Override
        public void release(
                String subOrderRef, String trackingNo, String ownerToken, String businessCode, String message) {
            entry(subOrderRef, trackingNo, ownerToken).status = Status.FAILED;
        }

        @Override
        public boolean releaseReconciledNotAccepted(String intentKey) {
            Entry entry = entries.get(intentKey);
            if (entry == null) {
                return false;
            }
            entry.status = Status.FAILED;
            entry.effectStarted = false;
            return true;
        }

        @Override
        public long externalEffectCount() {
            return entries.values().stream()
                    .filter(entry -> entry.effectStarted && entry.status != Status.IN_PROGRESS)
                    .count();
        }

        private void complete(
                String subOrderRef,
                String trackingNo,
                String ownerToken,
                SourceSyncResult result,
                Status status) {
            Entry entry = entry(subOrderRef, trackingNo, ownerToken);
            entry.status = status;
            entry.result = result;
        }

        private Entry entry(String subOrderRef, String trackingNo, String ownerToken) {
            Entry entry = entries.get(
                    FeixiangShipmentAttemptStore.idempotencyKey(subOrderRef, trackingNo));
            if (entry == null || !entry.ownerToken.equals(ownerToken)) {
                throw new IllegalStateException("飞象测试执行租约已失效");
            }
            return entry;
        }

        private enum Status {
            IN_PROGRESS,
            SUCCEEDED,
            FAILED,
            RECONCILIATION_REQUIRED
        }

        private static final class Entry {
            private final String payloadHash;
            private final String ownerToken;
            private Status status = Status.IN_PROGRESS;
            private SourceSyncResult result;
            private boolean effectStarted;

            private Entry(String payloadHash, String ownerToken) {
                this.payloadHash = payloadHash;
                this.ownerToken = ownerToken;
            }
        }
    }
}
