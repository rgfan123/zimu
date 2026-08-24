package cn.zimu.fulfillment.connector.jufubao;

import cn.zimu.fulfillment.connector.SourceSyncResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 聚福宝单订单发货外部写的持久幂等 store（GitHub Issue rgfan123/zimu#99）。
 *
 * <p>复用共享注册表 {@code app.idempotency_registry}（scope 固定为合法字符串 {@value #SCOPE}），
 * 不新增 migration 或表。幂等键固定为 {@code JUFUBAO + sub_order_id + tracking_no}
 * （见 {@link #idempotencyKey}）。注册表行是唯一跨重启事实来源：新 store 实例（无进程内状态）
 * 会重放 SUCCEEDED / RECONCILIATION_REQUIRED，绝不对未知结果给出 PROCEED，禁止盲目重提。
 *
 * <p>调用方必须遵守的流水线顺序：
 * <ol>
 *   <li>{@link #claim}：PROCEED 才可继续；REPLAY 直接返回已登记结果；CONFLICT / IN_PROGRESS /
 *       RECONCILIATION_REQUIRED 均不得提交外部写。</li>
 *   <li>写前检查（订单状态、发货详情、承运商映射等）；写前失败可调用 {@link #release}，允许安全重试。</li>
 *   <li>{@link #markEffectStarted}：必须在外部写之前调用并以独立事务提交，使租约失效后
 *       系统能判定「外部效果是否已开始」。</li>
 *   <li>外部提交后：已验证成功用 {@link #completeSuccess}、结果未知用 {@link #completeUnknown}
 *       持久化（均以 fencing {@code owner_token} 守卫）；平台明确拒绝用 {@link #release} 释放。</li>
 * </ol>
 *
 * <p>所有写操作都在 REQUIRES_NEW 独立事务中提交，先于外部调用落库。{@code owner_token} 是
 * fencing token：租约过期或被接管后，旧 owner 的任何写入都被拒绝（抛
 * {@code JUFUBAO_IDEMPOTENCY_CLAIM_LOST}）。租约过期且 {@code effect_started_at} 为空可安全接管；
 * {@code effect_started_at} 非空必须单调转 RECONCILIATION_REQUIRED，绝不回退到 PROCEED。
 *
 * <p>store 不接触、不持久化 Cookie / Token / 完整收件信息等敏感字段：快照只承载
 * {@link SourceSyncResult} 的契约字段（success / businessCode / message / platformRef / syncedAt）。
 */
public interface JufubaoShipmentAttemptStore {

    /** 共享注册表 scope：固定合法字符串，满足 {@code ^[a-z][a-z0-9_.-]{0,63}$} 约束。 */
    String SCOPE = "jufubao.shipment";

    /**
     * 抢占（或重放）一次聚福宝发货外部写。
     *
     * @param payload 本次发货意图；同 key（sub_order_id + tracking_no）必须携带稳定 payload，
     *                hash 与字段声明顺序无关（见 {@link #payloadHash}）
     * @return 决策结果；PROCEED 携带新 owner_token，REPLAY / RECONCILIATION_REQUIRED 携带应返回的结果
     */
    ClaimResult claim(ShipmentAttemptPayload payload);

    /**
     * 在外部写之前提交「外部效果已开始」标记（REQUIRES_NEW，先于外部调用持久化）。
     * 租约过期本身不使 owner 失效；只有已被新 owner 接管或状态已改变时才抛
     * {@code JUFUBAO_IDEMPOTENCY_CLAIM_LOST}。
     */
    void markEffectStarted(String subOrderId, String trackingNo, String ownerToken);

    /**
     * 持久化已验证成功结果：状态转 SUCCEEDED 并写入响应快照，以 fencing owner_token 守卫。
     */
    void completeSuccess(String subOrderId, String trackingNo, String ownerToken, SourceSyncResult result);

    /**
     * 持久化未知结果：状态单调转 RECONCILIATION_REQUIRED 并写入响应快照；此后任何实例的 claim
     * 都只会重放该结果，绝不给出 PROCEED。
     */
    void completeUnknown(String subOrderId, String trackingNo, String ownerToken, SourceSyncResult result);

    /**
     * 释放租约：平台明确拒绝或写前失败（未产生外部效果）时调用，行标记为 FAILED，
     * 允许后续同 key 同 payload 安全重试。
     */
    void release(String subOrderId, String trackingNo, String ownerToken, String businessCode, String message);

    /** 幂等键 = {@code JUFUBAO + sub_order_id + tracking_no}（两端去除首尾空白）。 */
    static String idempotencyKey(String subOrderId, String trackingNo) {
        String orderId = Objects.requireNonNull(subOrderId, "subOrderId").trim();
        String no = Objects.requireNonNull(trackingNo, "trackingNo").trim();
        if (orderId.isEmpty() || no.isEmpty()) {
            throw new IllegalArgumentException("subOrderId 与 trackingNo 不能为空");
        }
        return "JUFUBAO:" + orderId + ":" + no;
    }

    /**
     * 稳定 payload hash：字段名排序后的 canonical JSON 的 SHA-256。
     * 同一内容无论以 record 还是 map 构造、字段声明顺序如何、数量是否为带尾零的
     * BigDecimal（1 与 1.0 视为相同），hash 都保持一致。
     */
    static String payloadHash(ObjectMapper objectMapper, Object payload) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(canonical(objectMapper, objectMapper.valueToTree(payload)));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } catch (Exception ex) {
            throw new IllegalStateException("聚福宝幂等 payload 序列化失败", ex);
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
     * 一次发货尝试的 payload：覆盖影响外部写的全部字段。subOrderId 与 trackingNo 同时构成
     * 幂等键（重复出现在 hash 中无害且保持一致）。
     */
    record ShipmentAttemptPayload(
            String sourceRef,
            String subOrderId,
            BigDecimal actualShippedQuantity,
            String carrierOutputValue,
            String trackingNo) {

        public ShipmentAttemptPayload {
            sourceRef = sourceRef == null ? "" : sourceRef;
            subOrderId = requireNonBlank(subOrderId, "subOrderId");
            trackingNo = requireNonBlank(trackingNo, "trackingNo");
            actualShippedQuantity = Objects.requireNonNull(actualShippedQuantity, "actualShippedQuantity");
            carrierOutputValue = requireNonBlank(carrierOutputValue, "carrierOutputValue");
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

        static ClaimResult proceed(String ownerToken) {
            return new ClaimResult(Decision.PROCEED, null, ownerToken);
        }

        static ClaimResult replay(SourceSyncResult replay) {
            return new ClaimResult(Decision.REPLAY, replay, null);
        }

        static ClaimResult conflict() {
            return new ClaimResult(Decision.CONFLICT, null, null);
        }

        static ClaimResult inProgress() {
            return new ClaimResult(Decision.IN_PROGRESS, null, null);
        }

        static ClaimResult reconciliationRequired(SourceSyncResult replay) {
            return new ClaimResult(Decision.RECONCILIATION_REQUIRED, replay, null);
        }

    }

    /** claim 决策枚举。 */
    enum Decision {
        /** 首次占用、FAILED 重跑或租约过期且效果未开始的安全接管：可以执行外部写流水线。 */
        PROCEED,
        /** 同 key 同 payload 的已登记终态（SUCCEEDED / RECONCILIATION_REQUIRED）：重放结果，禁止再次提交。 */
        REPLAY,
        /** 同 key 不同 payload：请求冲突，不得提交。 */
        CONFLICT,
        /** 同 key 同 payload 且租约仍有效：其他执行者正在处理，退避等待。 */
        IN_PROGRESS,
        /** 租约过期且效果已开始：无法安全重试，已单调转 RECONCILIATION_REQUIRED，需人工对账。 */
        RECONCILIATION_REQUIRED
    }
}
