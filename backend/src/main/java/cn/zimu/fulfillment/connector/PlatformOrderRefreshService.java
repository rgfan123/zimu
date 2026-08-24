package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.file.SourceImportService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 三平台订单数据刷新编排（人工触发，Java Connector 优先、脚本通道兜底）。
 *
 * <p>对每个渠道：先基于 {@code connector_configs} 原子领取本次在途拉取（要求 enabled、
 * client mode=REAL、transport mode=API）→
 * <b>优先走 Java Connector</b>（{@link PlatformConnector#pullOrders}，内部已调
 * {@link SourceImportService#upload}/{@code importStructured} 建 NEW 批次，内容哈希幂等）→
 * Connector 缺失或能力未接入（{@code CONNECTOR_CAPABILITY_UNAVAILABLE}）时回退脚本通道
 * （进程内执行 scripts/*_fetch_orders.py，产物自动上传，与文件导入同管线）。
 * 聚福宝缺少已验证收货人契约时 fail-closed 为 NEED_REVIEW 批次，不生成可履约订单。
 * 全程审计；单渠道失败不阻断其他渠道。
 *
 * <p>并发门禁：同一渠道同时最多一个真实拉取。PostgreSQL 会话锁由专用连接持有；成功、失败
 * 都释放，连接或 JVM 异常终止时数据库自动释放，因此串行重试不受频率限制且没有陈旧 claim。
 * 成功清空 last_error，失败写入 last_error；配置/在途门禁不触发任何 Connector 或脚本拉取。
 *
 * <p>幂等取舍（A1）：refresh 会真实调用外部平台拉取（不可重放），因此幂等键仅做格式校验
 * （{@code WriteCommands.requireIdempotencyKey}，≥8 字符）防重复点击；真正的重复防护由
 * 导入批次内容哈希幂等承担（重复拉取命中既有批次/订单，不产生重复批次）。
 *
 */
@Service
public class PlatformOrderRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PlatformOrderRefreshService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> DEFAULT_CHANNELS = List.of("CAISHIXIAN", "JUFUBAO", "FEIXIANG");
    /** Connector 能力缺失业务码：命中时回退脚本通道（F1）。 */
    private static final String CAPABILITY_UNAVAILABLE = "CONNECTOR_CAPABILITY_UNAVAILABLE";
    private static final String CLEANUP_FAILED = "PLATFORM_PULL_CLEANUP_FAILED";

    static final String LOAD_CONNECTOR_GATE_SQL = """
            SELECT enabled, mode, transport_mode
            FROM app.connector_configs
            WHERE source_channel=?
            """;

    /** 渠道 → 脚本规格（脚本文件名 + 凭据文件名 + 凭据环境变量名）。A10：以 record 取代 String[] 结伴。 */
    private static final Map<String, ChannelScriptSpec> CHANNEL_SCRIPTS = Map.of(
            "CAISHIXIAN", new ChannelScriptSpec(
                    "caishixian_fetch_orders.py", "csx-credentials.txt",
                    List.of("CSX_USERNAME", "CSX_PASSWORD", "CSX_SUPPLIER_CODE")),
            "FEIXIANG", new ChannelScriptSpec(
                    "feixiang_fetch_orders.py", "feixiang-credentials.txt",
                    List.of("FEIXIANG_USERNAME", "FEIXIANG_PASSWORD")));

    private final SourceImportService sourceImportService;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;
    private final PlatformPullSingleFlight singleFlight;
    private final PlatformScriptRunner scriptRunner;
    /** F1：渠道 → Java Connector（Spring 收集 List<PlatformConnector> 后按 channel() 建索引，同 ConnectorService）。 */
    private final Map<SourceChannel, PlatformConnector> connectors;

    private final Path scriptsDir;
    private final Path credentialsDir;
    private final Path workDir;
    private final Duration scriptTimeout;
    private final int defaultDays;

    PlatformOrderRefreshService(
            SourceImportService sourceImportService,
            AuditLogService auditLogService,
            JdbcTemplate jdbc,
            PlatformPullSingleFlight singleFlight,
            PlatformScriptRunner scriptRunner,
            List<PlatformConnector> platformConnectors,
            @Value("${app.platform-pull.scripts-dir:${java.io.tmpdir}/zimu-platform-pull-scripts}") String scriptsDir,
            @Value("${app.platform-pull.credentials-dir:${java.io.tmpdir}/zimu-platform-pull-credentials}") String credentialsDir,
            @Value("${app.platform-pull.work-dir:${java.io.tmpdir}/zimu-platform-pull}") String workDir,
            @Value("${app.platform-pull.script-timeout:PT10M}") Duration scriptTimeout,
            @Value("${app.platform-pull.default-days:30}") int defaultDays) {
        this.sourceImportService = sourceImportService;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
        this.singleFlight = singleFlight;
        this.scriptRunner = scriptRunner;
        Map<SourceChannel, PlatformConnector> collected = new EnumMap<>(SourceChannel.class);
        platformConnectors.forEach(connector -> collected.put(connector.channel(), connector));
        this.connectors = collected;
        this.scriptsDir = Path.of(scriptsDir);
        this.credentialsDir = Path.of(credentialsDir);
        this.workDir = Path.of(workDir);
        this.scriptTimeout = scriptTimeout;
        this.defaultDays = defaultDays;
    }

    public Map<String, Object> refresh(PlatformOrderRefreshController.RefreshRequest body, CommandContext context) {
        List<String> channels = body == null || body.channels() == null || body.channels().isEmpty()
                ? DEFAULT_CHANNELS
                : body.channels().stream().map(String::toUpperCase).toList();
        LocalDate end = parseDay(body == null ? null : body.date_end(), LocalDate.now(SHANGHAI));
        LocalDate begin = parseDay(body == null ? null : body.date_begin(), end.minusDays(defaultDays - 1));

        List<Map<String, Object>> results = new ArrayList<>();
        for (String channel : channels) {
            results.add(refreshChannel(channel, begin, end, context));
        }
        if (results.stream().noneMatch(result -> "OK".equals(result.get("status")))) {
            // A11：全渠道 SKIPPED/FAILED 且无任何 OK → 502（保持 {business_code, message, http_status} 错误契约）。
            throw new BusinessException(
                    502,
                    "PLATFORM_REFRESH_ALL_FAILED",
                    "所有渠道刷新均未成功（SKIPPED 或 FAILED），请查看各渠道 message 后重试",
                    List.of(),
                    Map.of("channels", results));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", results);
        out.put("date_begin", begin.format(DAY));
        out.put("date_end", end.format(DAY));
        return out;
    }

    private Map<String, Object> refreshChannel(String channel, LocalDate begin, LocalDate end, CommandContext context) {
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", channel);
        List<String> command = null;
        try {
            PullAttemptDecision capability = runtimeCapability(channel);
            if (!capability.allowed()) {
                result.put("status", "SKIPPED");
                result.put("message", capability.message());
                return finish(channel, begin, end, context, result, capability.businessCode(), command, started);
            }
            try (PlatformPullSingleFlight.Lease pullLease = singleFlight.tryAcquire(channel)) {
                if (!pullLease.acquired()) {
                    result.put("status", "SKIPPED");
                    result.put("message", "该渠道已有拉取任务进行中，本次未重复发起");
                    return finish(
                            channel, begin, end, context, result, "PLATFORM_PULL_IN_PROGRESS", command, started);
                }
                PullAttemptDecision gate = runtimeConfigurationGate(channel);
                if (!gate.allowed()) {
                    result.put("status", "SKIPPED");
                    result.put("message", gate.message());
                    return finish(channel, begin, end, context, result, gate.businessCode(), command, started);
                }
                try {
                    // F1：优先走 Java Connector（内部已调 SourceImportService 建批次，不再走脚本）。
                    PullResult pull = connectorPull(channel, begin, end);
                    if (pull != null) {
                        if (pull.ok()) {
                            result.put("status", "OK");
                            result.put("message", pull.message());
                            result.put("order_count", pull.pulledCount());
                            if (pull.importBatch() != null) {
                                result.put("batch_id", pull.importBatch().id());
                                result.put("batch_no", pull.importBatch().batchNo());
                                result.put("row_counts", pull.importBatch().rowCounts());
                            }
                            return finish(channel, begin, end, context, result, null, command, started);
                        }
                        if (!CAPABILITY_UNAVAILABLE.equals(pull.businessCode())) {
                            // 凭据缺失/平台错误/网络失败等真实失败：渠道 FAILED，不回退脚本（避免双重拉取）。
                            result.put("status", "FAILED");
                            result.put("message", pull.message());
                            return finish(channel, begin, end, context, result, pull.businessCode(), command, started);
                        }
                        log.info("渠道 {} 的 Connector 未接入在线拉取（{}），回退脚本通道", channel, pull.businessCode());
                    }
                    // ---- 脚本通道（兜底）：Connector 缺失或能力未接入 ----
                    ChannelScriptSpec spec = CHANNEL_SCRIPTS.get(channel);
                    if (spec == null) {
                        result.put("status", "SKIPPED");
                        result.put("message", "不支持的渠道: " + channel);
                        return finish(channel, begin, end, context, result, null, command, started);
                    }
                    Path script = scriptsDir.resolve(spec.scriptName());
                    Path credentialFile = credentialsDir.resolve(spec.credentialFile());
                    if (!Files.isRegularFile(script)) {
                        result.put("status", "FAILED");
                        result.put("message", "拉取脚本不存在: " + script);
                        return finish(channel, begin, end, context, result, null, command, started);
                    }
                    if (!Files.isRegularFile(credentialFile)
                            && spec.credentialEnvNames().stream().noneMatch(key -> System.getenv(key) != null)) {
                        result.put("status", "FAILED");
                        result.put("message", "凭据缺失: " + credentialFile
                                + "（请配置 " + spec.credentialEnvNames() + " 环境变量，或先在 data-local/ 配置对应凭据）");
                        return finish(channel, begin, end, context, result, null, command, started);
                    }
                    Path outDir = scriptRunner.createTempDirectory(workDir, channel.toLowerCase() + "-");
                    try {
                        command = buildCommand(channel, script, begin, end, outDir);
                        PlatformScriptRunner.ScriptExecution exec = scriptRunner.run(
                                command, scriptRunner.readCredentials(credentialFile, spec.credentialEnvNames()), scriptTimeout);
                        if (exec.timedOut()) {
                            throw new PlatformScriptRunner.PlatformScriptException("拉取脚本执行超时（" + scriptTimeout + "）");
                        }
                        if (exec.exitCode() != 0) {
                            throw new PlatformScriptRunner.PlatformScriptException(
                                    "拉取脚本退出码 " + exec.exitCode() + ": " + PlatformScriptRunner.tail(exec.output(), 1200));
                        }
                        result.put("script_output", PlatformScriptRunner.tail(exec.output(), 400));
                        Path artifact = newestFile(outDir);
                        if (artifact == null) {
                            result.put("status", "FAILED");
                            result.put("message", "脚本执行完成但未产出文件");
                        } else {
                            importXlsx(channel, artifact, result, context);
                        }
                    } finally {
                        // A6：临时目录含平台订单产物（可能含收货人电话/地址），执行后必须清理。
                        PlatformScriptRunner.CleanupResult cleanup = scriptRunner.deleteRecursively(outDir);
                        if (!cleanup.complete()) {
                            markCleanupFailure(result, cleanup);
                        }
                    }
                    return finish(channel, begin, end, context, result, resultBusinessCode(result), command, started);
                } catch (PlatformScriptRunner.PlatformScriptException ex) {
                    if (cleanupFailed(result)) {
                        return finish(channel, begin, end, context, result, CLEANUP_FAILED, null, started);
                    }
                    result.put("status", "FAILED");
                    result.put("message", ex.getMessage());
                    return finish(channel, begin, end, context, result, "SCRIPT_FAILED", command, started);
                } catch (BusinessException ex) {
                    if (cleanupFailed(result)) {
                        return finish(channel, begin, end, context, result, CLEANUP_FAILED, null, started);
                    }
                    result.put("status", "FAILED");
                    result.put("message", ex.getMessage());
                    return finish(channel, begin, end, context, result, ex.getBusinessCode(), command, started);
                } catch (Exception ex) {
                    if (cleanupFailed(result)) {
                        return finish(channel, begin, end, context, result, CLEANUP_FAILED, null, started);
                    }
                    log.error("平台订单刷新失败: channel={}", channel, ex);
                    result.put("status", "FAILED");
                    result.put("message", "刷新失败: " + ex.getMessage());
                    return finish(channel, begin, end, context, result, "INTERNAL_ERROR", command, started);
                }
            }
        } catch (RuntimeException ex) {
            if (result.containsKey("business_code")) {
                throw ex;
            }
            log.error("平台订单刷新失败: channel={}", channel, ex);
            result.put("status", "FAILED");
            result.put("message", "拉取并发门禁或运行配置读取失败: " + ex.getMessage());
            return finish(channel, begin, end, context, result, "INTERNAL_ERROR", command, started);
        }
    }

    /**
     * F1：按渠道查 Java Connector 并执行在线拉取（PullCursor.initial(since, until)，Asia/Shanghai 窗口）。
     * 渠道名非法或无对应 Connector bean 时返回 null，调用方走脚本兜底。
     */
    private PullResult connectorPull(String channel, LocalDate begin, LocalDate end) {
        SourceChannel sourceChannel;
        try {
            sourceChannel = SourceChannel.valueOf(channel);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        PlatformConnector connector = connectors.get(sourceChannel);
        if (connector == null) {
            return null;
        }
        OffsetDateTime since = begin.atStartOfDay(SHANGHAI).toOffsetDateTime();
        OffsetDateTime until = end.atStartOfDay(SHANGHAI).toOffsetDateTime();
        return connector.pullOrders(PullCursor.initial(since, until));
    }

    /**
     * capability gate 必须先于数据库 claim 与任何外呼。已注册但 onlinePull=false 的 Connector
     * 不得登录，也不得偷偷回退脚本；聚福宝 bean 缺失时也保持 fail-closed。
     */
    private PullAttemptDecision runtimeCapability(String channel) {
        if (!DEFAULT_CHANNELS.contains(channel)) {
            return PullAttemptDecision.blocked("PLATFORM_CHANNEL_UNSUPPORTED", "不支持的渠道: " + channel);
        }
        SourceChannel sourceChannel;
        try {
            sourceChannel = SourceChannel.valueOf(channel);
        } catch (IllegalArgumentException exception) {
            return PullAttemptDecision.blocked("PLATFORM_CHANNEL_UNSUPPORTED", "不支持的渠道: " + channel);
        }
        PlatformConnector connector = connectors.get(sourceChannel);
        if (connector != null) {
            ConnectorCapabilities capabilities = connector.capabilities();
            if (capabilities == null || !capabilities.onlinePull()) {
                return PullAttemptDecision.blocked(
                        CAPABILITY_UNAVAILABLE,
                        "该渠道 onlinePull 尚未安全接入，已阻止登录、脚本回退与导入");
            }
        } else if (sourceChannel == SourceChannel.JUFUBAO) {
            return PullAttemptDecision.blocked(
                    CAPABILITY_UNAVAILABLE,
                    "聚福宝在线 Connector 未注册，已阻止脚本回退");
        }
        return PullAttemptDecision.granted();
    }

    /** A9：审计在结果组装完成后调用，传入真实 latency_ms；响应 payload 为含 latency_ms 的最终结果。 */
    private Map<String, Object> finish(
            String channel,
            LocalDate begin,
            LocalDate end,
            CommandContext context,
            Map<String, Object> result,
            String businessCode,
            List<String> command,
            long started) {
        int latencyMs = (int) ((System.nanoTime() - started) / 1_000_000);
        result.put("latency_ms", latencyMs);
        if (businessCode == null) {
            businessCode = "OK".equals(result.get("status")) ? "OK"
                    : "SKIPPED".equals(result.get("status")) ? "SKIPPED" : "REFRESH_FAILED";
        }
        result.put("business_code", businessCode);
        updatePullState(channel, result);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("date_begin", begin.format(DAY));
        request.put("date_end", end.format(DAY));
        if (command != null && !CLEANUP_FAILED.equals(businessCode)) {
            request.put("command", command);
        }
        audit(context, channel, "refresh", request, result, businessCode, latencyMs);
        return result;
    }

    /** 读取运行配置；调用方已持有单飞锁，没有频率窗口。 */
    private PullAttemptDecision runtimeConfigurationGate(String channel) {
        ConnectorGateState state = jdbc.query(
                LOAD_CONNECTOR_GATE_SQL,
                rs -> rs.next()
                        ? new ConnectorGateState(
                                rs.getBoolean("enabled"),
                                rs.getString("mode"),
                                rs.getString("transport_mode"))
                        : null,
                channel);
        if (state == null) {
            return PullAttemptDecision.blocked(
                    "CONNECTOR_CONFIG_MISSING", "Connector 运行配置不存在，已阻止外部拉取: " + channel);
        }
        if (!state.enabled()) {
            return PullAttemptDecision.blocked("CONNECTOR_DISABLED", "Connector 已停用，已跳过外部拉取");
        }
        if (!"REAL".equals(state.clientMode())) {
            return PullAttemptDecision.blocked(
                    "CONNECTOR_CLIENT_MODE_NOT_REAL", "Connector client mode 不是 REAL，已阻止外部拉取");
        }
        if (!"API".equals(state.transportMode())) {
            return PullAttemptDecision.blocked(
                    "CONNECTOR_TRANSPORT_NOT_API", "Connector transport mode 不是 API，已阻止外部拉取");
        }
        return PullAttemptDecision.granted();
    }

    /** 成功清空 last_error；失败写入 last_error。SKIPPED 不改错误状态。 */
    private void updatePullState(String channel, Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        if ("OK".equals(status)) {
            jdbc.update(
                    """
                    UPDATE app.connector_configs
                    SET last_error=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE source_channel=?
                    """,
                    channel);
        } else if ("FAILED".equals(status)) {
            Map<String, Object> error = Map.of(
                    "status", status,
                    "business_code", String.valueOf(result.getOrDefault("business_code", "REFRESH_FAILED")),
                    "message", String.valueOf(result.getOrDefault("message", "")));
            jdbc.update(
                    """
                    UPDATE app.connector_configs
                    SET last_error=?::jsonb, updated_at=CURRENT_TIMESTAMP
                    WHERE source_channel=?
                    """,
                    PlatformScriptRunner.writeJson(error),
                    channel);
        }
    }

    private void markCleanupFailure(
            Map<String, Object> result, PlatformScriptRunner.CleanupResult cleanup) {
        result.put("status", "FAILED");
        result.put("business_code", CLEANUP_FAILED);
        result.put("message", "拉取处理已结束，但敏感临时文件清理不完整，已记录安全告警，请立即人工处理");
        result.put("cleanup_failure_count", cleanup.failureCount());
        result.put("cleanup_path_id", cleanup.pathIdentifier());
        result.remove("script_output");
        result.remove("file_name");
    }

    private boolean cleanupFailed(Map<String, Object> result) {
        return CLEANUP_FAILED.equals(result.get("business_code"));
    }

    private String resultBusinessCode(Map<String, Object> result) {
        Object code = result.get("business_code");
        return code == null ? null : String.valueOf(code);
    }

    /** 组装渠道拉取命令；彩食鲜用 export 模式（xlsx），飞象直下 xlsx。 */
    private List<String> buildCommand(String channel, Path script, LocalDate begin, LocalDate end, Path outDir) {
        List<String> command = new ArrayList<>(List.of("python3", script.toString(), "--force", "--out-dir", outDir.toString()));
        switch (channel) {
            case "CAISHIXIAN" -> {
                command.add("--mode");
                command.add("export");
                command.add("--pay-begin");
                command.add(begin.format(DAY));
                command.add("--pay-end");
                command.add(end.format(DAY));
            }
            case "FEIXIANG" -> {
                command.add("--begin");
                command.add(begin.format(DAY));
                command.add("--end");
                command.add(end.format(DAY));
            }
            default -> throw new IllegalArgumentException("unsupported channel " + channel);
        }
        return command;
    }

    /** 取目录内最新文件（脚本产物），忽略空目录。 */
    private Path newestFile(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .orElse(null);
        }
    }

    private void importXlsx(String channel, Path artifact, Map<String, Object> result, CommandContext context) {
        try {
            byte[] bytes = Files.readAllBytes(artifact);
            String filename = artifact.getFileName().toString();
            Map<String, Object> batch = sourceImportService.upload(
                    bytes, filename, "NEW", null,
                    "platform-pull-" + channel + "-" + System.nanoTime(), context);
            result.put("status", "OK");
            result.put("message", "已拉取并生成导入批次 " + batch.get("batch_no"));
            result.put("batch_no", batch.get("batch_no"));
            result.put("batch_id", batch.get("id"));
            result.put("row_counts", batch.get("row_counts"));
            result.put("file_name", filename);
        } catch (BusinessException ex) {
            // 内容哈希幂等命中既有批次或订单已存在（DUPLICATE_ORDER）都是重复拉取的幂等防护，
            // 按「无新数据」成功处理，不把幂等冲突当失败。
            String code = ex.getBusinessCode();
            if ("DUPLICATE_ORDER".equals(code) || "ORDER_ALREADY_EXISTS".equals(code)) {
                result.put("status", "OK");
                result.put("message", "已拉取，但订单已存在（重复拉取防护），无新数据导入");
            } else {
                result.put("status", "FAILED");
                result.put("message", "导入失败: " + ex.getMessage());
            }
        } catch (IOException ex) {
            result.put("status", "FAILED");
            result.put("message", "读取拉取产物失败: " + ex.getMessage());
        }
    }

    private void audit(
            CommandContext context,
            String channel,
            String operation,
            Map<String, Object> request,
            Map<String, Object> response,
            String businessCode,
            int latencyMs) {
        auditLogService.record(PlatformScriptRunner.baseAuditCommand(context)
                .service("platform-pull." + channel)
                .operation(operation)
                .requestPayload(request)
                .responsePayload(response)
                .httpStatus(200)
                .businessCode(businessCode)
                .latencyMs(latencyMs));
    }

    private static LocalDate parseDay(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(value, DAY);
    }

    /** A10：渠道脚本规格——脚本文件名 + 凭据文件名 + 凭据环境变量名（取代 String[] 结伴）。 */
    private record ChannelScriptSpec(String scriptName, String credentialFile, List<String> credentialEnvNames) {}

    record ConnectorGateState(boolean enabled, String clientMode, String transportMode) {}

    private record PullAttemptDecision(boolean allowed, String businessCode, String message) {
        static PullAttemptDecision granted() {
            return new PullAttemptDecision(true, "OK", "运行能力可用");
        }

        static PullAttemptDecision blocked(String businessCode, String message) {
            return new PullAttemptDecision(false, businessCode, message);
        }
    }
}
