package cn.zimu.fulfillment.connector.feixiang;

/**
 * 飞象发货写门闩（{@code app.feixiang.shipment.write-mode}，默认 {@link #OFF}）。
 *
 * <p><b>为什么需要一个独立于能力位的四态开关</b>：{@code ConnectorCapabilities.onlinePush}
 * 在本仓里同时是<b>路由开关</b>而不只是「能不能推」——
 * {@code SourceReturnWecomScanner} 见到该位为真就<b>停止</b>把回填文件投递到企微
 * （即今天飞象唯一在生产验证过的回传通道），{@code SourceSyncAutoWorker} 见到该位为真就把
 * 该渠道纳入自动执行循环。因此「默认关闭」不能靠改能力位常量，必须由本枚举驱动能力位。
 *
 * <ul>
 *   <li>{@link #OFF}（默认）—— {@code onlinePush=false}。企微人工上传路径原样保留，
 *       自动回传 Worker 对飞象落 {@code NOT_APPLICABLE} 终态（安静，不产生 10 分钟死循环）。</li>
 *   <li>{@link #DRY_RUN} —— 同样 {@code onlinePush=false}（<b>刻意的</b>：演练不得停掉在跑的
 *       企微投递）。此模式下即使有任何代码路径走到 {@code submit}，网关也只构造报文并落审计，
 *       <b>绝不打开 socket</b>。报文核对走只读预览 seam。</li>
 *   <li>{@link #ARMED} —— {@code onlinePush=true}，且只允许<b>一次</b>真实写入：
 *       {@code app.idempotency_registry} 里 scope=feixiang.shipment 一旦出现效果已开始的行，
 *       后续写入一律被拒，直到人工核验平台结果后显式升到 {@link #ON}。</li>
 *   <li>{@link #ON} —— {@code onlinePush=true}，常态放行。</li>
 * </ul>
 *
 * <p>取值解析故意宽进严出：不认识的值一律回落 {@link #OFF}，绝不因为配置写错而意外开火。</p>
 */
public enum FeixiangShipmentWriteMode {

    /** 完全关闭：能力位为假，企微文件通道保持唯一回传路径。 */
    OFF,

    /** 演练：能力位仍为假；网关构造完整报文并落审计，绝不发出。 */
    DRY_RUN,

    /** 首发布防：能力位为真，但全局只允许一次真实写入。 */
    ARMED,

    /** 常态放行：能力位为真，不限次数。 */
    ON;

    /** 是否向 {@code ConnectorCapabilities.onlinePush} 置位。 */
    public boolean pushCapable() {
        return this == ARMED || this == ON;
    }

    /** 是否允许真正发出 HTTP 写请求。 */
    public boolean emitsExternalWrite() {
        return this == ARMED || this == ON;
    }

    /** 是否只允许一次真实写入（首发人工确认门闩）。 */
    public boolean firstWriteOnly() {
        return this == ARMED;
    }

    /** 宽进严出：null/空白/不认识的值一律回落 OFF。 */
    public static FeixiangShipmentWriteMode parse(String raw) {
        if (raw == null) {
            return OFF;
        }
        String value = raw.trim();
        for (FeixiangShipmentWriteMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return OFF;
    }
}
