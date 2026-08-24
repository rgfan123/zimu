package cn.zimu.fulfillment.fulfillment;

import java.util.Set;

/**
 * 京东销售出库单状态码的共享语义（access-guide 367/54597）。
 *
 * <p>销售出库单状态枚举：100130 预分拣-获取运单；10014~10019 拣货/打包/交接全程有运单号；
 * 10020 包裹出库、10032/10033/10034 分拣/站点/妥投、10054 分拣中心发货（真实探测 2026-08-18：
 * 10015/10016 即返回 carrierInfo.waybillNo）。10028/10031/10035 为终止异常。
 *
 * <p>内部 {@code shipments.shipment_status}（CREATED/SHIPPED/FAILED/DELIVERED）与京东状态码是
 * 两套词汇表；本类只做语义归类，供对账展示与运单回填共用，避免两侧各自维护一套枚举漂移。
 */
public final class JdOutboundStatus {

    private JdOutboundStatus() {}

    /** 已出库/已发货的中后段状态码。 */
    public static final Set<String> SHIPPED_STATUS = Set.of(
            "100130", "10014", "10015", "10016", "10017", "10018", "10019",
            "10020", "10032", "10033", "10034", "10054");

    /** 终止异常状态码。 */
    public static final Set<String> TERMINAL_EXCEPTION_STATUS = Set.of("10028", "10031", "10035");

    /** 京东状态码的语义归类：SHIPPED / EXCEPTION / PENDING；无法识别时返回 PENDING。 */
    public static Semantic semantic(String code) {
        if (code == null) {
            return Semantic.PENDING;
        }
        if (SHIPPED_STATUS.contains(code)) {
            return Semantic.SHIPPED;
        }
        if (TERMINAL_EXCEPTION_STATUS.contains(code)) {
            return Semantic.EXCEPTION;
        }
        return Semantic.PENDING;
    }

    /** 语义归类，用于与内部发货状态的粗粒度对照。 */
    public enum Semantic {
        PENDING,
        SHIPPED,
        EXCEPTION
    }
}
