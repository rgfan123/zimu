package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;

/**
 * 平台 Connector 边界（api-contract §6.2 落地，ticket 01）。
 *
 * <p>兼容演进：在线方法均为 default 实现，默认返回 CAPABILITY_UNAVAILABLE，
 * 与 ExcelPlatformConnector 现有「文件模式」语义一致；各平台子类在实现
 * onlinePull 时覆盖 capabilities() 置位并重写对应方法。</p>
 */
public interface PlatformConnector {

    SourceChannel channel();

    ConnectorCapabilities capabilities();

    ConnectionTestResult testConnection(ConnectorRuntime runtime);

    // ---------------------------------------------------------------- 在线拉取（Phase 1）

    /** 拉取新订单。未实现时返回 CAPABILITY_UNAVAILABLE。 */
    default PullResult pullOrders(PullCursor cursor) {
        return PullResult.unavailable(channel());
    }

    /** 拉取订单变更。按合并裁决由「消失检测」（ticket 14）替代，平台实现可不提供。 */
    default PullResult pullOrderChanges(PullCursor cursor) {
        return PullResult.unavailable(channel());
    }

    /** 拉取订单取消。按合并裁决由「消失检测」（ticket 14）替代，平台实现可不提供。 */
    default PullResult pullCancellations(PullCursor cursor) {
        return PullResult.unavailable(channel());
    }

    /**
     * 平台原始订单信封 → 标准订单输入。
     * 文件模式（EXCEL）下 transform 由文件解析管线承担，此处默认不支持；
     * 在线实现（ticket 07/08/09）覆盖此方法。
     */
    default CanonicalOrderInput transform(SourceOrderEnvelope envelope) {
        throw new UnsupportedOperationException(
                "transform 未实现: channel=" + channel() + "（EXCEL 模式由文件解析管线承担）");
    }

    // ---------------------------------------------------------------- 在线回传（Phase 2）

    /** 读取平台最新订单与可发货事实；不得产生远端写效果。 */
    default SourcePlatformCheckResult checkShipmentResult(SourceShipmentResult result) {
        return SourcePlatformCheckResult.unavailable(channel());
    }

    /** 回传发货结果。未实现时返回失败（CAPABILITY_UNAVAILABLE 语义）。 */
    default SourceSyncResult pushShipmentResult(SourceShipmentResult result) {
        return SourceSyncResult.unavailable(channel());
    }

    /**
     * 受围栏保护的 Shipment 级回传。Adapter 若包含多个不可逆写，必须在每一次前
     * 重新调用 permit；默认实现只为旧的单效果 Connector 提供兼容。
     */
    default SourceSyncResult pushShipmentResult(SourceShipmentResult result, ExternalWritePermit permit) {
        permit.beforeExternalWrite();
        return pushShipmentResult(result);
    }

    /** 只读重查未知写结果；默认复用平台检查。 */
    default SourcePlatformCheckResult reconcileShipmentResult(SourceShipmentResult result) {
        return checkShipmentResult(result);
    }

    /**
     * 人工对账确认平台未受理后，释放 Adapter 内部的原始平台意图键。
     * 实现必须幂等；不得从当前已漂移事实重算键。
     */
    default boolean releaseShipmentIntent(String platformIntentKey) {
        return false;
    }
}
