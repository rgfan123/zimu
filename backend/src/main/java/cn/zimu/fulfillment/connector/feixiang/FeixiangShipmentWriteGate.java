package cn.zimu.fulfillment.connector.feixiang;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 飞象发货写门闩：把 {@code app.feixiang.shipment.write-mode} 变成两个可执行判断。
 *
 * <ol>
 *   <li>{@link #pushCapable()} —— 供 {@code FeixiangConnector.capabilities()} 置位
 *       {@code onlinePush}。这一位不只是「能不能推」，它同时决定企微文件投递是否让路、
 *       自动回传 Worker 是否纳入本渠道，所以必须由门闩驱动而不是写死常量。</li>
 *   <li>{@link #inspectExternalWrite()} —— 在打开 socket 之前的最后一次判断，
 *       由 {@link FeixiangHttpShipmentGateway} 在 {@code submit} 里调用。</li>
 * </ol>
 *
 * <p><b>ARMED 一次性布防</b>是「首次真实写入必须人工确认」的兜底：即使
 * {@code app.source-sync.auto.enabled} 被打开，自动 Worker 也只能消耗掉这一次布防，
 * 第二次写入一律被拒，直到人工核对完平台结果并显式把模式升到 ON。判据取
 * {@code effect_started_at} 非空的行数——一次结果未知的写入同样算用掉了布防。</p>
 */
@Component
public class FeixiangShipmentWriteGate {

    private final FeixiangShipmentWriteMode mode;
    private final FeixiangShipmentAttemptStore attempts;

    public FeixiangShipmentWriteGate(
            @Value("${app.feixiang.shipment.write-mode:OFF}") String rawMode,
            FeixiangShipmentAttemptStore attempts) {
        this.mode = FeixiangShipmentWriteMode.parse(rawMode);
        this.attempts = attempts;
    }

    public FeixiangShipmentWriteMode mode() {
        return mode;
    }

    /** 是否向 {@code ConnectorCapabilities.onlinePush} 置位。 */
    public boolean pushCapable() {
        return mode.pushCapable();
    }

    /**
     * 真实外部写之前的最后一次判断。
     *
     * <p>ARMED 下计数不可用时<b>拒绝</b>（fail-closed）：宁可少发一次，也不在
     * 「不知道是不是首发」的情况下往客户平台写。</p>
     */
    public Decision inspectExternalWrite() {
        return switch (mode) {
            case OFF -> Decision.block(
                    "FEIXIANG_WRITE_MODE_DISABLED",
                    "飞象在线回传写门闩未开启（app.feixiang.shipment.write-mode=OFF），未发出任何请求");
            case DRY_RUN -> Decision.block(
                    "FEIXIANG_WRITE_DRY_RUN",
                    "飞象在线回传处于演练模式，已构造并审计完整报文但未发出请求");
            case ARMED -> {
                long consumed;
                try {
                    consumed = attempts.externalEffectCount();
                } catch (RuntimeException exception) {
                    yield Decision.block(
                            "FEIXIANG_FIRST_WRITE_ARMING_UNAVAILABLE",
                            "无法确认首发布防是否已被使用，未发出请求");
                }
                yield consumed == 0
                        ? Decision.pass()
                        : Decision.block(
                                "FEIXIANG_FIRST_WRITE_ARMING_CONSUMED",
                                "飞象首发布防已被使用；请人工核对平台结果后再显式升级写门闩，未发出请求");
            }
            case ON -> Decision.pass();
        };
    }

    /** 门闩判定；{@code allowed=false} 时 {@code businessCode} 一定非空。 */
    public record Decision(boolean allowed, String businessCode, String message) {

        static Decision pass() {
            return new Decision(true, "OK", "写门闩已放行");
        }

        static Decision block(String businessCode, String message) {
            return new Decision(false, businessCode, message);
        }
    }
}
