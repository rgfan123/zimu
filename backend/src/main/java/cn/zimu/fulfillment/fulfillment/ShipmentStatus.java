package cn.zimu.fulfillment.fulfillment;

import java.util.Optional;

/**
 * 发货批次生命周期词汇表（{@code app.shipments.shipment_status}）与它回答的业务问题。
 *
 * <p>取值集合由 {@code V1__baseline.sql} 的 CHECK 约束确定并从未修改：
 * CREATED / SHIPPED / FAILED / DELIVERED；合法迁移由同一迁移的触发器强制：
 * CREATED → SHIPPED|FAILED，SHIPPED → DELIVERED（本类只做判定，不复制迁移规则）。
 *
 * <p>本类是这套词汇的唯一裁决点：调用方问业务问题（是否已形成发货事实、能否回填运单、
 * 能否建京东出库单），不再各自书写状态字面量。注意三个问题**不是**同一条规则的三种写法
 * ——「已发货」是 SHIPPED|DELIVERED，「可回填运单」是 CREATED|SHIPPED，「可建单」只有
 * CREATED；历史上它们分散在 4 个包里，看起来像互相矛盾，其实是三个不同的问题（票 02）。
 *
 * <p>与 {@link JdOutboundStatus}（京东销售出库单状态码）是两套词汇表，互不换算；
 * 运单导入文件「结果」列的 SHIPPED/PARTIAL/FAILED 也是第三套词汇，与本类无关。
 *
 * <p>尚未收编的点位：SQL 内联判定仍在各查询中书写
 * {@code shipment_status IN ('SHIPPED','DELIVERED')} 等字面量（8 处 Java 内嵌 SQL +
 * V2/V3/V6 迁移视图）。新增或改名状态时必须一并同步，定位用
 * {@code git grep -n "shipment_status" backend/src}。
 */
public enum ShipmentStatus {

    /** 已创建，尚未发货；可建京东出库单、可回填运单。 */
    CREATED,

    /** 已发货，已形成发货事实。 */
    SHIPPED,

    /** 发货失败（失败原因必填，见 V1 CHECK）。 */
    FAILED,

    /** 已妥投，同样属于已形成发货事实。 */
    DELIVERED;

    /**
     * 数据库取值 → 枚举；null、空串与任何非法取值返回 empty，调用方据此落到否定分支，
     * 与迁移前各点位的 {@code equals} 语义逐位一致（不做大小写宽松解析）。
     */
    static Optional<ShipmentStatus> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        for (ShipmentStatus status : values()) {
            if (status.name().equals(raw)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /** 是否已形成发货事实（SHIPPED 或 DELIVERED）：来源回传前置、对账语义、看板口径共用。 */
    public static boolean isShipped(String raw) {
        return parse(raw).filter(status -> status == SHIPPED || status == DELIVERED).isPresent();
    }

    /** 是否发货失败（仅 FAILED）。 */
    public static boolean isFailed(String raw) {
        return parse(raw).filter(status -> status == FAILED).isPresent();
    }

    /** 是否允许回填运单（CREATED 或 SHIPPED）：已妥投与已失败都不再接受回填。 */
    public static boolean acceptsTrackingBackfill(String raw) {
        return parse(raw).filter(status -> status == CREATED || status == SHIPPED).isPresent();
    }

    /** 是否允许提交京东出库建单（仅 CREATED）。 */
    public static boolean acceptsOutboundSubmit(String raw) {
        return parse(raw).filter(status -> status == CREATED).isPresent();
    }
}
