package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.connector.SourceSyncResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 飞象发货外部写的持久幂等 store。
 *
 * <p><b>零新表、零迁移</b>：复用共享注册表 {@code app.idempotency_registry}
 * （scope 固定为 {@value #SCOPE}，满足既有 scope 命名约束）。
 *
 * <p><b>幂等键的形状是本类最要紧的设计决定。</b>键 = 渠道前缀 +
 * {@code order_son_sn + 运单号} 的 SHA-256，<b>不含承运商代码</b>：
 * 承运商留在 payload 里，于是「同一子单同一运单号换个承运商再发一次」会撞成
 * {@link Decision#CONFLICT} 拦停等人，而不是算出一个新键把上一次的写覆盖掉——
 * 平台自己没有幂等，重复提交会把商品行上的运单号改写成最新值。
 *
 * <p>同一个键还被 {@code SourceSyncStore.platformIntentKey} 登记进
 * {@code shipment_syncs.platform_intent_key}，由数据库唯一索引
 * {@code uq_shipment_syncs_platform_intent} 二次强制；人工对账判定「平台未受理」时
 * 由 {@link #releaseReconciledNotAccepted} 按<b>当初登记的</b>键解锁，不从当前事实重算。
 *
 * <p>调用顺序与聚福宝同构：claim 到写前只读检查，再 markEffectStarted、verifyWritePermit、
 * 外部写，最后 completeSuccess / completeUnknown / release。
 */
public interface FeixiangShipmentAttemptStore {

    /** 共享注册表 scope。 */
    String SCOPE = "feixiang.shipment";

    /** 幂等键前缀，与聚福宝同表共存但互不干扰。 */
    String KEY_PREFIX = "FEIXIANG:sha256:";

    /** 抢占（或重放）一次飞象发货外部写。 */
    ClaimResult claim(ShipmentAttemptPayload payload);

    /** 外部写之前提交「效果已开始」标记（REQUIRES_NEW，先于外部调用落库）。 */
    void markEffectStarted(String subOrderRef, String trackingNo, String ownerToken);

    /** 每一次不可逆外部写之前单独获取 fencing 许可。 */
    void verifyWritePermit(String subOrderRef, String trackingNo, String ownerToken);

    /** 已由写后回查确认成功。 */
    void completeSuccess(String subOrderRef, String trackingNo, String ownerToken, SourceSyncResult result);

    /** 结果未知：单调转 RECONCILIATION_REQUIRED，此后任何实例都只重放，绝不 PROCEED。 */
    void completeUnknown(String subOrderRef, String trackingNo, String ownerToken, SourceSyncResult result);

    /** 平台明确拒绝或写前失败（未产生外部效果）时释放租约，允许同 payload 安全重试。 */
    void release(String subOrderRef, String trackingNo, String ownerToken, String businessCode, String message);

    /** 人工对账确认平台未受理后，按最初登记的完整 intent key 解锁。 */
    boolean releaseReconciledNotAccepted(String intentKey);

    /**
     * 本 scope 下<b>已结束</b>且外部效果已开始过的行数。
     *
     * <p>{@link FeixiangShipmentWriteGate} 用它实现 ARMED 一次性布防：只要有过一次真正
     * 打到平台的写入，后续一律拒绝，直到人工核验平台结果后显式升到 ON。
     *
     * <p>两个判据都要紧：
     * <ul>
     *   <li>取 {@code effect_started_at} 而不是 SUCCEEDED——一次<b>结果未知</b>的写入
     *       同样消耗掉这次布防，那正是最需要人去平台核对的情形；</li>
     *   <li>排除 {@code IN_PROGRESS}——否则正在执行的这一次会把自己算进去，
     *       在标记效果之后、发出请求之前把自己挡下，首发永远发不出去。</li>
     * </ul>
     */
    long externalEffectCount();

    /** 幂等键 = 渠道前缀 + {@code order_son_sn + 运单号} 的稳定摘要（<b>不含承运商</b>）。 */
    static String idempotencyKey(String subOrderRef, String trackingNo) {
        String ref = Objects.requireNonNull(subOrderRef, "subOrderRef").trim();
        String no = Objects.requireNonNull(trackingNo, "trackingNo").trim();
        if (ref.isEmpty() || no.isEmpty()) {
            throw new IllegalArgumentException("subOrderRef 与 trackingNo 不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((ref + "|" + no).getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 稳定 payload hash：字段名排序后的 canonical JSON 的 SHA-256（数组保持业务顺序）。 */
    static String payloadHash(ObjectMapper objectMapper, Object payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(
                    canonical(objectMapper, objectMapper.valueToTree(payload)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } catch (Exception ex) {
            throw new IllegalStateException("飞象幂等 payload 序列化失败", ex);
        }
    }

    private static JsonNode canonical(ObjectMapper objectMapper, JsonNode value) {
        if (value.isObject()) {
            List<String> fieldNames = new ArrayList<>();
            value.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(String::compareTo);
            ObjectNode sorted = objectMapper.createObjectNode();
            for (String fieldName : fieldNames) {
                sorted.set(fieldName, canonical(objectMapper, value.get(fieldName)));
            }
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            value.forEach(element -> ordered.add(canonical(objectMapper, element)));
            return ordered;
        }
        if (value.isBigDecimal()) {
            BigDecimal normalized = value.decimalValue().stripTrailingZeros();
            return objectMapper.getNodeFactory()
                    .textNode(normalized.signum() == 0 ? "0" : normalized.toPlainString());
        }
        return value;
    }

    /**
     * 一次发货尝试的 payload：覆盖全部影响外部写的字段。
     *
     * <p>承运商在 payload 里而不在键里——换承运商必须是 CONFLICT，不是新键。这里存的是
     * <b>内部配置值</b>（{@code carrier_mappings} 的显示名）而不是平台代码：它才是「操作员
     * 改了承运商」这一意图变化的真源，且不依赖一次平台读取即可算出，claim 得以先于任何外呼。</p>
     */
    record ShipmentAttemptPayload(
            String sourceRef,
            String subOrderRef,
            BigDecimal sourceUnitQuantity,
            String carrierOutputValue,
            String trackingNo,
            String expectedPlatformEffectHash) {

        public ShipmentAttemptPayload {
            sourceRef = sourceRef == null ? "" : sourceRef;
            subOrderRef = requireNonBlank(subOrderRef, "subOrderRef");
            trackingNo = requireNonBlank(trackingNo, "trackingNo");
            sourceUnitQuantity = Objects.requireNonNull(sourceUnitQuantity, "sourceUnitQuantity");
            carrierOutputValue = requireNonBlank(carrierOutputValue, "carrierOutputValue");
            expectedPlatformEffectHash =
                    expectedPlatformEffectHash == null ? "" : expectedPlatformEffectHash;
        }

        private static String requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " 不能为空");
            }
            return value.trim();
        }
    }

    /** claim 决策结果。 */
    record ClaimResult(Decision decision, SourceSyncResult replay, String ownerToken) {

        public static ClaimResult proceed(String ownerToken) {
            return new ClaimResult(Decision.PROCEED, null, ownerToken);
        }

        public static ClaimResult replay(SourceSyncResult replay) {
            return new ClaimResult(Decision.REPLAY, replay, null);
        }

        public static ClaimResult conflict() {
            return new ClaimResult(Decision.CONFLICT, null, null);
        }

        public static ClaimResult inProgress() {
            return new ClaimResult(Decision.IN_PROGRESS, null, null);
        }

        public static ClaimResult reconciliationRequired(SourceSyncResult replay) {
            return new ClaimResult(Decision.RECONCILIATION_REQUIRED, replay, null);
        }
    }

    /** claim 决策枚举。 */
    enum Decision {
        /** 首次占用、FAILED 重跑或租约过期且效果未开始的安全接管。 */
        PROCEED,
        /** 同键同 payload 的已登记终态：重放，禁止再次提交。 */
        REPLAY,
        /** 同键不同 payload（例如换了承运商）：请求冲突，不得提交。 */
        CONFLICT,
        /** 同键同 payload 且租约仍有效：他人正在处理。 */
        IN_PROGRESS,
        /** 租约过期且效果已开始：单调转对账，绝不回 PROCEED。 */
        RECONCILIATION_REQUIRED
    }
}
