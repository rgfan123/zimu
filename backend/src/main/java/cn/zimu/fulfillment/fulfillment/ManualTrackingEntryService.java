package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工录入运单：人直接给一张发货批次填快递公司与运单号，不必先把数字填回 Excel 再上传。
 *
 * <h2>为什么要有它</h2>
 *
 * <p>在此之前系统<b>唯一</b>的运单入口是上传回填后的导出文件
 * （{@code POST /api/v1/fulfillment-exports/{id}/tracking-imports}）。第三方履约方经常只在
 * 群里发一个运单号，运营手上有事实、系统不收。
 *
 * <p>2026-08-30 生产实证：{@code app.provider_tracking_drafts} 0 行——第三方回传这条链
 * 从来没有真实数据走通过；发货批次 18（谭华勇）自 08-27 停在 CREATED 等了 48 小时；
 * 用户把顺丰单号发进企微两次，两次都无人消费。
 *
 * <h2>刻意<b>不</b>另起一套写入逻辑</h2>
 *
 * <p>本服务只负责把「人给的两个字段」补成一条完整命令，真正的写入全部交给
 * {@link ShipmentTrackingService#acceptShipment}——那里有锁 Shipment、同承运商同单号的
 * 幂等重放、已存在不同运单号的冲突收敛、以及承运商+运单号的 advisory lock
 * （防同一外部运单被两张 Shipment 同时占用）。
 *
 * <p><b>运单号一落就会推发货卡、并可能触发来源回传真写客户平台</b>，这条链不可逆。
 * 给它开一个绕过既有门禁的手输口，等于把最危险的一段做成最不设防的一段。
 */
@Service
public class ManualTrackingEntryService {

    private final JdbcTemplate jdbc;
    private final ShipmentTrackingService trackingService;
    private final CarrierPrefixMatcher carriers;

    public ManualTrackingEntryService(
            JdbcTemplate jdbc,
            ShipmentTrackingService trackingService,
            CarrierPrefixMatcher carriers) {
        this.jdbc = jdbc;
        this.trackingService = trackingService;
        this.carriers = carriers;
    }

    /**
     * 给一张发货批次录入运单。
     *
     * @param carrierInput 快递公司；可以是名称（如「顺丰速运」）也可以是承运商代码。
     *                     留空时按运单号前缀推断，<b>推不出就报错而不是猜</b>
     */
    @Transactional
    public ManualTrackingOutcome enter(
            long shipmentId, String carrierInput, String trackingNumber, CommandContext context) {
        String tracking = trackingNumber == null ? "" : trackingNumber.trim();
        if (tracking.isEmpty()) {
            throw BusinessException.unprocessable("MANUAL_TRACKING_NUMBER_REQUIRED", "运单号不能为空");
        }

        Header header = header(shipmentId);
        CarrierPrefixMatcher.Carrier carrier = resolveCarrier(carrierInput, tracking);
        List<ShipmentTrackingBatchCommand.Item> items = items(shipmentId);
        if (items.isEmpty()) {
            throw BusinessException.unprocessable(
                    "MANUAL_TRACKING_ITEMS_REQUIRED", "这张发货批次没有明细，无法录入运单");
        }

        ShipmentTrackingBatchCommand command = new ShipmentTrackingBatchCommand(
                null,
                shipmentId,
                header.orderId(),
                items,
                carrier.code(),
                carrier.name(),
                tracking,
                Instant.now(),
                // 原始载荷如实记「这是人工录的」，事后能和文件导入的行区分开。
                Map.of("source", "MANUAL_ENTRY", "operator", context.operator()),
                "人工录入运单");

        ShipmentTrackingAcceptance acceptance = trackingService.acceptShipment(command, context);
        if (acceptance.conflicted()) {
            // 冲突不抛异常：这张批次已有别的运单号是<b>业务事实</b>不是调用错误，
            // 要让人看见「已有的是哪个」才好核对，抛 5xx 只会把这条信息埋掉。
            return ManualTrackingOutcome.conflict(tracking, existingTracking(shipmentId));
        }
        return acceptance.replayed()
                ? ManualTrackingOutcome.replayed(tracking)
                : ManualTrackingOutcome.accepted(tracking);
    }

    /**
     * 承运商解析：人填了就认人填的，没填才按前缀推。
     *
     * <p><b>推不出来就报错，不默认一个。</b> 承运商代码会进回填文件、进来源平台回传，
     * 猜错的后果是客户拿着错的快递公司去查一个查不到的单号。
     */
    private CarrierPrefixMatcher.Carrier resolveCarrier(String stated, String trackingNumber) {
        String input = stated == null ? "" : stated.trim();
        if (!input.isEmpty()) {
            return carriers.resolveStated(input)
                    .or(() -> carriers.carrier(input))
                    .orElseThrow(() -> BusinessException.unprocessable(
                            "MANUAL_TRACKING_CARRIER_UNKNOWN",
                            "认不出快递公司「" + input + "」——请从承运商字典里选一个"));
        }
        return carriers.resolvePrefix(trackingNumber)
                .orElseThrow(() -> BusinessException.unprocessable(
                        "MANUAL_TRACKING_CARRIER_REQUIRED",
                        "运单号 " + trackingNumber + " 推断不出快递公司，请明确指定"));
    }

    private record Header(long orderId, String shipmentStatus) {}

    private Header header(long shipmentId) {
        Header header = jdbc.query(
                """
                SELECT s.order_id, s.shipment_status
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                WHERE s.id=?
                """,
                rs -> rs.next()
                        ? new Header(rs.getLong("order_id"), rs.getString("shipment_status"))
                        : null,
                shipmentId);
        if (header == null) {
            throw BusinessException.notFound("发货批次不存在: " + shipmentId);
        }
        return header;
    }

    /**
     * 整批明细，数量取「指示发货量」。
     *
     * <p>人工录入只支持<b>整批全发</b>：人手上只有一个运单号，说明这一批是一个包裹发出去的。
     * 部分发货要拆量、要判断剩余量怎么处理，那是文件链路和续发流程的事，
     * 在这里做等于把最复杂的一段塞进最简单的入口。
     */
    private List<ShipmentTrackingBatchCommand.Item> items(long shipmentId) {
        return jdbc.query(
                """
                SELECT si.fulfillment_id, f.order_line_id, si.instructed_quantity
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                WHERE si.shipment_id=?
                ORDER BY si.id
                """,
                (rs, row) -> new ShipmentTrackingBatchCommand.Item(
                        rs.getLong("fulfillment_id"),
                        rs.getLong("order_line_id"),
                        rs.getInt("instructed_quantity")),
                shipmentId);
    }

    private String existingTracking(long shipmentId) {
        String existing = jdbc.query(
                "SELECT tracking_number FROM app.trackings WHERE shipment_id=?",
                rs -> rs.next() ? rs.getString("tracking_number") : null,
                shipmentId);
        return existing == null ? "（未知）" : existing;
    }

    /** 录入界面的快递公司下拉；只给启用的，避免人选到已停用的承运商。 */
    public List<Map<String, String>> availableCarriers() {
        return carriers.enabledCarriers().stream()
                .map(carrier -> Map.of("code", carrier.code(), "name", carrier.name()))
                .toList();
    }
}
