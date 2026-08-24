package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.AbstractHttpPullConnector;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.ExternalWritePermit;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.file.SourceImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 彩食鲜 Connector：在线拉单（ticket 07，文件化链路）。
 *
 * <p>pullOrders 链路：登录（login-token 续期）→ 发起导出任务 → 轮询完成 → 下载 xlsx 字节
 * → 复用现有文件解析管线 {@link SourceImportService#upload}（raw 行血缘、confirm、履约导出
 * 全链路与文件导入一致）。产物是真实文件字节，无需自定义 transform。</p>
 *
 * <p>合规：真实网络只在 {@link #pullOrders} / {@link #testConnection} 的登录探测时发生；
 * 凭据只走环境变量，凭据缺失返回 {@code CREDENTIALS_REQUIRED} 失败而非抛异常。
 * testConnection/日期计算/结果装配等公共实现见 {@link AbstractHttpPullConnector}。</p>
 */
@Component
public class CaishixianConnector extends AbstractHttpPullConnector {

    private static final Logger log = LoggerFactory.getLogger(CaishixianConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SourceImportService sourceImportService;
    private final CaishixianPullClient pullClient;
    private final CaishixianShipmentGateway shipmentGateway;

    public CaishixianConnector(
            SourceImportService sourceImportService,
            CaishixianPullClient pullClient,
            CaishixianShipmentGateway shipmentGateway) {
        this.sourceImportService = sourceImportService;
        this.pullClient = pullClient;
        this.shipmentGateway = shipmentGateway;
    }

    @Override
    public SourceChannel channel() {
        return SourceChannel.CAISHIXIAN;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(true, true, true, true, false);
    }

    @Override
    protected LoginProbe loginProbe() {
        CaishixianPullClient.LoginResult login = pullClient.login();
        return new LoginProbe(login.ok(), login.businessCode(), login.message());
    }

    @Override
    public PullResult pullOrders(PullCursor cursor) {
        SourceChannel channel = channel();
        try {
            CaishixianPullClient.LoginResult login = pullClient.login();
            if (!login.ok()) {
                return failed(channel, login.businessCode(), login.message());
            }
            LocalDate begin = beginDate(cursor);
            LocalDate end = endDate(cursor);
            byte[] xlsx = pullClient.pullDeliverExport(login.token(), begin.format(DAY), end.format(DAY));
            String filename = "caishixian-deliver-" + end.format(DAY) + ".xlsx";
            Map<String, Object> batch = sourceImportService.upload(
                    xlsx,
                    filename,
                    "NEW",
                    null,
                    "platform-pull-" + channel.name().toLowerCase() + "-" + System.nanoTime(),
                    commandContext());
            int accepted = acceptedCount(batch);
            String message = "已拉取彩食鲜待发货订单，导入批次 " + batch.get("batch_no")
                    + "（accepted=" + accepted + "）";
            log.info("彩食鲜拉取完成: {}", message);
            return success(channel, accepted, message, batch);
        } catch (BusinessException exception) {
            if (DUPLICATE_CODES.contains(exception.getBusinessCode())) {
                log.info("彩食鲜拉取命中重复订单（{}），按无新数据处理", exception.getBusinessCode());
                return success(channel, 0, "已拉取但订单已存在（重复拉取防护），无新数据导入");
            }
            return failed(channel, exception.getBusinessCode(), "导入失败: " + exception.getMessage());
        } catch (CaishixianPullClient.PullTransportException exception) {
            log.warn("彩食鲜拉取失败: {}", exception.getMessage());
            return failed(channel, "PLATFORM_PULL_ERROR", exception.getMessage());
        }
    }

    @Override
    public SourcePlatformCheckResult checkShipmentResult(SourceShipmentResult result) {
        SourceSyncResult invalid = validateShipment(result);
        if (invalid != null) {
            return SourcePlatformCheckResult.unavailable(channel(), invalid.businessCode(), invalid.message());
        }
        try {
            CaishixianShipmentGateway.PlatformOrderSnapshot snapshot = shipmentGateway.inspect(
                    result.sourceRef().trim(), result.sourceLineRef().trim());
            boolean receiverComplete = snapshot != null
                    && nonBlank(snapshot.receiverName())
                    && nonBlank(snapshot.receiverPhone())
                    && nonBlank(snapshot.receiverAddress());
            return new SourcePlatformCheckResult(
                    true,
                    "OK",
                    "彩食鲜 Shipment 当前事实已读取",
                    snapshot == null ? null : Integer.toString(snapshot.orderStatus()),
                    false,
                    receiverComplete
                            ? SourcePlatformCheckResult.AddressStatus.CLEAR
                            : SourcePlatformCheckResult.AddressStatus.UNKNOWN,
                    snapshot == null ? null : snapshot.receiverName(),
                    snapshot == null ? null : snapshot.receiverPhone(),
                    snapshot == null ? null : snapshot.receiverAddress(),
                    snapshot == null ? null : snapshot.sendableQuantity(),
                    carrier(result.carrierOutputValue()) != null);
        } catch (RuntimeException exception) {
            return SourcePlatformCheckResult.unavailable(
                    channel(), "CAISHIXIAN_PLATFORM_CHECK_UNAVAILABLE", "彩食鲜 Shipment 当前事实读取失败");
        }
    }

    /** 禁止绕过 source-sync 的人工确认、Shipment CAS 与外部写租约。 */
    @Override
    public SourceSyncResult pushShipmentResult(SourceShipmentResult result) {
        return SourceSyncResult.failed(
                "SOURCE_SYNC_EXECUTION_CONTEXT_REQUIRED",
                "彩食鲜在线发货必须通过 Shipment source-sync execute 入口");
    }

    @Override
    public SourceSyncResult pushShipmentResult(
            SourceShipmentResult result,
            ExternalWritePermit permit) {
        SourceSyncResult invalid = validateShipment(result);
        if (invalid != null) {
            return invalid;
        }
        AtomicBoolean externalWriteStarted = new AtomicBoolean();
        try {
            CaishixianShipmentGateway.PlatformOrderSnapshot before = shipmentGateway.inspect(
                    result.sourceRef().trim(), result.sourceLineRef().trim());
            if (before == null || !before.present() || before.orderStatus() != 3) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_ORDER_NOT_SHIPPABLE",
                        "彩食鲜订单当前不是待发货状态，未上传回填文件");
            }
            if (!sameReceiver(result, before)) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_RECEIVER_MISMATCH",
                        "Shipment 与彩食鲜当前收货信息不一致，未上传回填文件");
            }
            if (before.sendableQuantity() == null
                    || before.sendableQuantity().compareTo(result.sourceUnitQuantity()) != 0) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_SHIPMENT_QUANTITY_MISMATCH",
                        "Shipment 来源份数与彩食鲜当前可发数量不一致，未上传回填文件");
            }
            CaishixianShipmentGateway.CarrierOption carrier = carrier(result.carrierOutputValue());
            if (carrier == null) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_CARRIER_UNMAPPED",
                        "正式运单的物流公司未命中彩食鲜实时字典，未上传回填文件");
            }
            if (result.artifact() == null || !result.artifact().present()) {
                return SourceSyncResult.failed(
                        "CAISHIXIAN_SHIPMENT_ARTIFACT_REQUIRED",
                        "缺少单 Shipment 彩食鲜回填工作簿，未上传");
            }
            CaishixianShipmentGateway.UploadAck uploaded = shipmentGateway.upload(result.artifact(), () -> {
                permit.beforeExternalWrite();
                externalWriteStarted.set(true);
            });
            if (uploaded.outcome() == CaishixianShipmentGateway.UploadAck.Outcome.REJECTED) {
                String code = safeCode(uploaded.platformCode());
                return SourceSyncResult.failed(
                        code,
                        "彩食鲜明确拒绝发货回填（业务码：" + code + "）");
            }
            CaishixianShipmentGateway.Verification verification = shipmentGateway.awaitVerified(
                    before.platformOrderId(), carrier.code(), result.firstTrackingNo().trim());
            if (verification.verified()) {
                return SourceSyncResult.ok(verification.platformOrderId());
            }
            return reconciliationRequired(
                    verification.safeMessage(),
                    before.platformOrderId());
        } catch (RuntimeException exception) {
            if (externalWriteStarted.get()) {
                return reconciliationRequired("彩食鲜上传或写后查询结果未知", null);
            }
            return SourceSyncResult.failed(
                    "CAISHIXIAN_PLATFORM_UNAVAILABLE",
                    "彩食鲜发货前查询失败，尚未上传回填文件");
        }
    }

    private SourceSyncResult validateShipment(SourceShipmentResult result) {
        if (result == null || result.channel() != SourceChannel.CAISHIXIAN) {
            return SourceSyncResult.failed("CAISHIXIAN_CHANNEL_MISMATCH", "发货结果不属于彩食鲜渠道");
        }
        if (result.shipmentId() == null || result.shipmentId() <= 0
                || !nonBlank(result.sourceRef()) || !nonBlank(result.sourceLineRef())) {
            return SourceSyncResult.failed("CAISHIXIAN_SHIPMENT_LINEAGE_REQUIRED", "彩食鲜 Shipment 来源血缘不完整");
        }
        BigDecimal quantity = result.sourceUnitQuantity();
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 0) {
            return SourceSyncResult.failed("CAISHIXIAN_QUANTITY_INVALID", "彩食鲜回填数量必须是正整数来源份数");
        }
        if (!"SHIPPED".equals(result.outcome())) {
            return SourceSyncResult.failed("CAISHIXIAN_OUTCOME_NOT_SHIPPABLE", "P0 只允许完整已发货结果回传彩食鲜");
        }
        if (!nonBlank(result.carrierOutputValue()) || !nonBlank(result.firstTrackingNo())) {
            return SourceSyncResult.failed("CAISHIXIAN_TRACKING_REQUIRED", "彩食鲜回填缺少物流公司或正式运单号");
        }
        if (!nonBlank(result.receiverName()) || !nonBlank(result.receiverPhone()) || !nonBlank(result.receiverAddress())) {
            return SourceSyncResult.failed("CAISHIXIAN_RECEIVER_REQUIRED", "彩食鲜回填缺少 Shipment 收货信息");
        }
        return null;
    }

    private CaishixianShipmentGateway.CarrierOption carrier(String code) {
        if (!nonBlank(code)) {
            return null;
        }
        return shipmentGateway.carrierOptions().stream()
                .filter(option -> code.trim().equals(option.code()))
                .findFirst()
                .orElse(null);
    }

    private boolean sameReceiver(
            SourceShipmentResult result,
            CaishixianShipmentGateway.PlatformOrderSnapshot platform) {
        return normalizeText(result.receiverName()).equals(normalizeText(platform.receiverName()))
                && normalizePhone(result.receiverPhone()).equals(normalizePhone(platform.receiverPhone()))
                && normalizeText(result.receiverAddress()).equals(normalizeText(platform.receiverAddress()));
    }

    private SourceSyncResult reconciliationRequired(String message, String platformRef) {
        return SourceSyncResult.failed(
                "RECONCILIATION_REQUIRED",
                (nonBlank(message) ? message : "彩食鲜发货结果未知") + "；禁止盲目重提，请到平台核对",
                platformRef);
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Za-z0-9._-]{1,64}")
                ? code
                : "CAISHIXIAN_PLATFORM_REJECTED";
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String normalizePhone(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("[\\s()-]", "");
        return normalized.startsWith("+86") ? normalized.substring(3) : normalized;
    }
}
