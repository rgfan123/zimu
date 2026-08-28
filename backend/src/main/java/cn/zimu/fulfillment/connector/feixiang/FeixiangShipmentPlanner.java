package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.connector.SourceReceiverNormalizer;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 飞象发货的<b>只读</b>写计划构造器：血缘解析、承运商映射、平台事实拉取与报文构造。
 *
 * <p>它是 check、dry-run 预览与真写三条路径的<b>唯一</b>入口，因此三者看到的报文
 * 逐字节相同——「人核对过的」和「发出去的」不可能走形。本类<b>不产生任何远端写效果</b>。
 *
 * <p>取数分工是本类的核心决定：
 * <ul>
 *   <li><b>身份</b>（{@code order_son_id}）来自导入快照（平台主键，不可变）；</li>
 *   <li><b>事实</b>（商品行 ID、数量、收货人、是否已发货）来自<b>本次</b>只读拉取的
 *       平台详情，绝不用导入时的旧快照——那正是「写计划漂移」的来源。</li>
 * </ul>
 */
@Component
public class FeixiangShipmentPlanner {

    private static final ObjectMapper EFFECT_HASH_MAPPER = new ObjectMapper();

    private final FeixiangShipmentLineage lineage;
    private final FeixiangCarrierCodeResolver carriers;
    private final FeixiangShipmentGateway gateway;

    public FeixiangShipmentPlanner(
            FeixiangShipmentLineage lineage,
            FeixiangCarrierCodeResolver carriers,
            FeixiangShipmentGateway gateway) {
        this.lineage = lineage;
        this.carriers = carriers;
        this.gateway = gateway;
    }

    /**
     * 写计划。
     *
     * @param available     平台事实是否可读；为假时只有 businessCode / message 有意义
     * @param platformState 供 {@code SourceSyncPolicy.writableState} 判定的状态串。
     *                      刻意<b>不</b>使用平台的 {@code express_state} 数字码——它的语义
     *                      没有抓包确认过。这里只用两个无歧义的事实合成：详情读得到，
     *                      且没有任何目标商品行已带运单号/物流公司。
     * @param request       完整报文；承运商未映射或商品行不可用时为 null
     * @param effectHash    写计划指纹（商品行集合 + 平台承运商代码），用于确认后的漂移检测
     */
    public record WritePlan(
            boolean available,
            String businessCode,
            String message,
            String orderSonId,
            String platformState,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            BigDecimal sendableQuantity,
            boolean carrierMapped,
            String expressCode,
            FeixiangShipmentRequest request,
            String effectHash) {

        /** 平台当前允许提交发货。 */
        public static final String STATE_SHIPPABLE = "SHIPPABLE";
        /** 平台上已经有物流事实：绝不重复写，避免覆盖别人的运单号。 */
        public static final String STATE_ALREADY_SHIPPED = "ALREADY_SHIPPED";
        /** 读到了详情但没有可发商品行。 */
        public static final String STATE_NO_LINES = "NO_LINES";

        static WritePlan unavailable(String businessCode, String message) {
            return new WritePlan(false, businessCode, message, null, null, null, null, null,
                    null, false, null, null, null);
        }
    }

    /** 构造写计划；只读，任何失败都不产生远端效果。 */
    public WritePlan plan(SourceShipmentResult result) {
        if (result == null || result.shipmentId() == null) {
            return WritePlan.unavailable(
                    "FEIXIANG_SHIPMENT_LINEAGE_REQUIRED", "飞象 Shipment 血缘不完整，无法构造写计划");
        }
        FeixiangShipmentLineage.Resolution resolved = lineage.resolve(result.shipmentId());
        if (!resolved.resolved()) {
            return WritePlan.unavailable(resolved.businessCode(), resolved.message());
        }
        FeixiangOrderDetail detail;
        try {
            gateway.prepareWrite();
            detail = gateway.orderDetail(resolved.orderSonId());
        } catch (RuntimeException exception) {
            return WritePlan.unavailable(
                    "FEIXIANG_PLATFORM_CHECK_UNAVAILABLE", "飞象订单详情读取失败，未提交任何平台请求");
        }
        if (detail == null || detail.receiveInfo() == null) {
            return WritePlan.unavailable(
                    "FEIXIANG_PLATFORM_DETAIL_INVALID", "飞象订单详情缺少收货信息，无法核对");
        }
        return assemble(result, resolved.orderSonId(), detail);
    }

    private WritePlan assemble(
            SourceShipmentResult result, String orderSonId, FeixiangOrderDetail detail) {
        FeixiangOrderDetail.ReceiveInfo info = detail.receiveInfo();
        List<FeixiangOrderDetail.ProductLine> lines = detail.products();
        String platformState = platformState(lines);
        BigDecimal sendable = sendableQuantity(lines);

        FeixiangCarrierCodeResolver.Resolution carrier = carriers.resolve(result.carrierOutputValue());
        FeixiangShipmentRequest request = null;
        String effectHash = null;
        if (carrier.resolved() && WritePlan.STATE_SHIPPABLE.equals(platformState)) {
            try {
                request = new FeixiangShipmentRequest(
                        orderProductIds(lines), result.firstTrackingNo(), carrier.expressCode(), "");
                effectHash = effectHash(request);
            } catch (IllegalArgumentException exception) {
                // 标识符/运单号/承运商代码非法：在发出请求之前拒绝，绝不静默清洗。
                return new WritePlan(true, "FEIXIANG_WRITE_PAYLOAD_INVALID", exception.getMessage(),
                        orderSonId, platformState, info.name(), info.phone(), info.joinedAddress(),
                        sendable, carrier.resolved(), carrier.expressCode(), null, null);
            }
        }
        String code = carrier.resolved() ? "OK" : carrier.businessCode();
        String message = carrier.resolved() ? "飞象订单当前事实已读取" : carrier.message();
        return new WritePlan(true, code, message, orderSonId, platformState,
                info.name(), info.phone(), info.joinedAddress(), sendable,
                carrier.resolved(), carrier.expressCode(), request, effectHash);
    }

    /** 收货三要素是否与内部快照一致；Policy 也会独立比一次，这里是提交前的最后一道。 */
    public static boolean sameReceiver(SourceShipmentResult result, WritePlan plan) {
        return plan != null
                && SourceReceiverNormalizer.sameName(result.receiverName(), plan.receiverName())
                && SourceReceiverNormalizer.samePhone(result.receiverPhone(), plan.receiverPhone())
                && SourceReceiverNormalizer.sameAddress(result.receiverAddress(), plan.receiverAddress());
    }

    /**
     * 状态合成：只用有证据的两个事实。
     *
     * <p>任何一行已带运单号或物流公司即判 {@link WritePlan#STATE_ALREADY_SHIPPED}——
     * 平台没有幂等，对已发货行再提交会把运单号<b>改写</b>成最新值。</p>
     */
    private static String platformState(List<FeixiangOrderDetail.ProductLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return WritePlan.STATE_NO_LINES;
        }
        boolean shipped = lines.stream().anyMatch(FeixiangOrderDetail.ProductLine::alreadyShipped);
        return shipped ? WritePlan.STATE_ALREADY_SHIPPED : WritePlan.STATE_SHIPPABLE;
    }

    /** 平台可发份数 = 各商品行 {@code pronum} 之和；任一行非正整数则返回 null（判定为未知）。 */
    private static BigDecimal sendableQuantity(List<FeixiangOrderDetail.ProductLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (FeixiangOrderDetail.ProductLine line : lines) {
            String raw = line.pronum() == null ? "" : line.pronum().trim();
            if (raw.isEmpty() || !raw.chars().allMatch(Character::isDigit)) {
                return null;
            }
            BigDecimal quantity = new BigDecimal(raw);
            if (quantity.signum() <= 0) {
                return null;
            }
            total = total.add(quantity);
        }
        return total;
    }

    private static List<String> orderProductIds(List<FeixiangOrderDetail.ProductLine> lines) {
        List<String> ids = new ArrayList<>();
        for (FeixiangOrderDetail.ProductLine line : lines) {
            ids.add(line.orderProductId());
        }
        return List.copyOf(ids);
    }

    /** 写计划指纹：商品行集合（排序后）+ 平台承运商代码；运单号不进指纹（它是意图本身）。 */
    private static String effectHash(FeixiangShipmentRequest request) {
        Set<String> sorted = new LinkedHashSet<>(request.orderProductIds().stream().sorted().toList());
        return FeixiangShipmentAttemptStore.payloadHash(
                EFFECT_HASH_MAPPER,
                new WriteFingerprint(List.copyOf(sorted), request.expressCode()));
    }

    private record WriteFingerprint(List<String> orderProductIds, String expressCode) {}
}
