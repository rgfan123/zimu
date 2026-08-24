package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 彩食鲜/聚福宝/飞象在线拉取 Connector 的共享抽象（ticket 07/08/09）。
 *
 * <p>承载三个在线 Connector 近全同的公共实现：{@link #testConnection}（transportMode 感知）、
 * 日期窗口计算（Asia/Shanghai）、{@link #commandContext()}、幂等冲突码
 * {@link #DUPLICATE_CODES}、{@link #acceptedCount}、{@link #success}/{@link #failed}。
 * 子类只保留渠道特有逻辑：PullClient/transform 注入与 pullOrders 链路。
 *
 * <p>设计取舍：选择抽象类继承（而非共享 helper 类）——testConnection/success/failed 需要
 * 覆盖接口语义并复用 protected 常量，组合式 helper 会把近全同的方法签名散落到三个子类；
 * 抽象类把「渠道无关」部分一次收敛，子类职责收窄为「登录探测 + 拉取 + 导入」。代价是
 * 子类与抽象类单继承耦合，但三个 Connector 本就不需要其他父类。
 */
public abstract class AbstractHttpPullConnector implements PlatformConnector {

    protected static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 导入命中既有订单的幂等业务码（内容哈希/来源单号重复 → 按无新数据处理，不视为失败）。 */
    protected static final Set<String> DUPLICATE_CODES = Set.of("DUPLICATE_ORDER", "ORDER_ALREADY_EXISTS");

    /** 登录探测结果（子类把各自 PullClient.LoginResult 映射为公共形状；token 等渠道特有字段不进入）。 */
    public record LoginProbe(boolean ok, String businessCode, String message) {}

    /**
     * 登录探测：仅 transportMode=API 时由 {@link #testConnection} 调用（凭据缺失时
     * 返回 CREDENTIALS_REQUIRED）；EXCEL 模式在 testConnection 分支短路，不触网。
     */
    protected abstract LoginProbe loginProbe();

    @Override
    public ConnectionTestResult testConnection(ConnectorRuntime runtime) {
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (!runtime.enabled()) {
            return new ConnectionTestResult(false, checkedAt, 0, "CONNECTOR_DISABLED", "Connector 已停用");
        }
        // 第二轮评审 D 项：transportMode=EXCEL 时只报告文件 Adapter 就绪（同 ExcelPlatformConnector
        // 旧行为，不真登录）；仅 transportMode=API 才发起真实登录探测。
        if ("EXCEL".equals(runtime.transportMode())) {
            return new ConnectionTestResult(true, checkedAt, 0, "EXCEL_ADAPTER_READY", "文件 Adapter 可用");
        }
        long started = System.nanoTime();
        LoginProbe login = loginProbe();
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        if (login.ok()) {
            return new ConnectionTestResult(true, checkedAt, latencyMs, "OK", "登录成功，连接可用");
        }
        return new ConnectionTestResult(false, checkedAt, latencyMs, login.businessCode(), login.message());
    }

    /** 拉取窗口起点（Asia/Shanghai）：since 所在日，缺省 30 天前。 */
    protected LocalDate beginDate(PullCursor cursor) {
        if (cursor != null && cursor.since() != null) {
            return cursor.since().atZoneSameInstant(SHANGHAI).toLocalDate();
        }
        return LocalDate.now(SHANGHAI).minusDays(30);
    }

    /** 拉取窗口终点（Asia/Shanghai）：until 所在日（含），缺省今天。 */
    protected LocalDate endDate(PullCursor cursor) {
        if (cursor != null && cursor.until() != null) {
            return cursor.until().atZoneSameInstant(SHANGHAI).toLocalDate();
        }
        return LocalDate.now(SHANGHAI);
    }

    /** 系统拉取上下文：operator 固定 system:platform-pull，requestId/traceId 同源（审计可关联）。 */
    protected CommandContext commandContext() {
        String id = "platform-pull-" + System.nanoTime();
        return new CommandContext(id, id, "system:platform-pull");
    }

    /** 从导入批次结果取 accepted 行数（row_counts.accepted），缺失按 0 计。 */
    @SuppressWarnings("unchecked")
    protected int acceptedCount(Map<String, Object> batch) {
        Object counts = batch == null ? null : batch.get("row_counts");
        if (counts instanceof Map<?, ?> map && map.get("accepted") instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    /**
     * 成功结果装配（第二轮评审 F 项修复）：accepted>0 返回 OK+count；
     * accepted≤0（无新数据或重复拉取防护命中）返回 OK+count 0 并<b>保留 message</b>
     * ——旧实现丢 message 的缺陷在此修复。缺省文案兜底空 message。
     */
    protected PullResult success(SourceChannel channel, int accepted, String message) {
        String text = message != null && !message.isBlank() ? message : "无新数据或已存在（重复拉取防护）";
        return new PullResult(channel, List.of(), null, Math.max(accepted, 0), OffsetDateTime.now(),
                PullResult.PullStatus.OK, "OK", text);
    }

    /** 成功导入时同时带回未确认批次，让刷新界面可以安全进入人工整批确认。 */
    protected PullResult success(SourceChannel channel, int accepted, String message, Map<String, Object> batch) {
        String text = message != null && !message.isBlank() ? message : "已拉取并生成导入批次";
        Object id = batch == null ? null : batch.get("id");
        Object batchNo = batch == null ? null : batch.get("batch_no");
        PullResult.ImportBatchReference reference = id == null || batchNo == null
                ? null
                : new PullResult.ImportBatchReference(
                        String.valueOf(id), String.valueOf(batchNo), rowCounts(batch));
        return new PullResult(channel, List.of(), null, Math.max(accepted, 0), OffsetDateTime.now(),
                PullResult.PullStatus.OK, "OK", text, reference);
    }

    private Map<String, Object> rowCounts(Map<String, Object> batch) {
        Object counts = batch.get("row_counts");
        if (counts instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            return normalized;
        }
        return Map.of();
    }

    protected PullResult failed(SourceChannel channel, String businessCode, String message) {
        return new PullResult(channel, List.of(), null, 0, OffsetDateTime.now(),
                PullResult.PullStatus.FAILED, businessCode, message);
    }
}
