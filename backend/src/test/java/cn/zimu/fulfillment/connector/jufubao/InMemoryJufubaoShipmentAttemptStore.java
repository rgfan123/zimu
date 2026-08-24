package cn.zimu.fulfillment.connector.jufubao;

import cn.zimu.fulfillment.connector.SourceSyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/** 无 Spring/数据库的 Connector 单元测试替身。 */
final class InMemoryJufubaoShipmentAttemptStore implements JufubaoShipmentAttemptStore {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile int failPermitAt = Integer.MAX_VALUE;
    private volatile int permitChecks;

    InMemoryJufubaoShipmentAttemptStore failPermitAt(int checkNumber) {
        this.failPermitAt = checkNumber;
        return this;
    }

    int permitChecks() {
        return permitChecks;
    }

    @Override
    public ClaimResult claim(ShipmentAttemptPayload payload) {
        String key = JufubaoShipmentAttemptStore.idempotencyKey(payload.subOrderId(), payload.trackingNo());
        String hash = JufubaoShipmentAttemptStore.payloadHash(mapper, payload);
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
                return new Entry(hash, owner);
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
    public void markEffectStarted(String subOrderId, String trackingNo, String ownerToken) {
        entry(subOrderId, trackingNo, ownerToken);
    }

    @Override
    public void verifyWritePermit(String subOrderId, String trackingNo, String ownerToken) {
        entry(subOrderId, trackingNo, ownerToken);
        if (++permitChecks == failPermitAt) {
            throw new IllegalStateException("聚福宝测试写许可已失效");
        }
    }

    @Override
    public void completeSuccess(String subOrderId, String trackingNo, String ownerToken, SourceSyncResult result) {
        complete(subOrderId, trackingNo, ownerToken, result, Status.SUCCEEDED);
    }

    @Override
    public void completeUnknown(String subOrderId, String trackingNo, String ownerToken, SourceSyncResult result) {
        complete(subOrderId, trackingNo, ownerToken, result, Status.RECONCILIATION_REQUIRED);
    }

    @Override
    public void release(String subOrderId, String trackingNo, String ownerToken, String businessCode, String message) {
        entry(subOrderId, trackingNo, ownerToken).status = Status.FAILED;
    }

    @Override
    public boolean releaseReconciledNotAccepted(String intentKey) {
        Entry entry = entries.get(intentKey);
        if (entry == null || entry.status != Status.RECONCILIATION_REQUIRED) {
            return false;
        }
        entry.status = Status.FAILED;
        return true;
    }

    private void complete(
            String subOrderId,
            String trackingNo,
            String ownerToken,
            SourceSyncResult result,
            Status status) {
        Entry entry = entry(subOrderId, trackingNo, ownerToken);
        entry.result = result;
        entry.status = status;
    }

    private Entry entry(String subOrderId, String trackingNo, String ownerToken) {
        Entry entry = entries.get(JufubaoShipmentAttemptStore.idempotencyKey(subOrderId, trackingNo));
        if (entry == null || entry.status != Status.IN_PROGRESS || !entry.ownerToken.equals(ownerToken)) {
            throw new IllegalStateException("聚福宝测试幂等租约已失效");
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
        private volatile Status status = Status.IN_PROGRESS;
        private volatile SourceSyncResult result;

        private Entry(String payloadHash, String ownerToken) {
            this.payloadHash = payloadHash;
            this.ownerToken = ownerToken;
        }
    }
}
