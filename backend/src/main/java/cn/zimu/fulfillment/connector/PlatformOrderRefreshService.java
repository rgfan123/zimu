package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.file.SourceImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>对每个渠道（第二轮评审 F1 接线）：<b>优先走 Java Connector</b>
 * （{@link PlatformConnector#pullOrders}，内部已调
 * {@link SourceImportService#upload}/{@code importStructured} 建 NEW 批次，内容哈希幂等）→
 * Connector 缺失或能力未接入（{@code CONNECTOR_CAPABILITY_UNAVAILABLE}）时回退脚本通道
 * （进程内执行 scripts/*_fetch_orders.py，产物自动上传，与文件导入同管线）。
 * 聚福宝 JSON 直连缺收货人字段，只报告拉取数量。全程审计；单渠道失败不阻断其他渠道。
 *
 * <p>幂等取舍（A1）：refresh 会真实调用外部平台拉取（不可重放），因此幂等键仅做格式校验
 * （{@code WriteCommands.requireIdempotencyKey}，≥8 字符）防重复点击；真正的重复防护由
 * 导入批次内容哈希幂等承担（重复拉取命中既有批次/订单，不产生重复批次）。
 *
 * <p>自动确认（F5）：{@code app.platform-pull.auto-confirm} 配置项本轮恒不打开（默认 false）——
 * 人工闸门（ImportBatch confirm）保留，配置项仅作后续「拉取后自动确认」预留，本服务不实现
 * 自动确认逻辑。
 */
@Service
public class PlatformOrderRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PlatformOrderRefreshService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> DEFAULT_CHANNELS = List.of("CAISHIXIAN", "JUFUBAO", "FEIXIANG");
    /** Connector 能力缺失业务码：命中时回退脚本通道（F1）。 */
    private static final String CAPABILITY_UNAVAILABLE = "CONNECTOR_CAPABILITY_UNAVAILABLE";

    /** 渠道 → 脚本规格（脚本文件名 + 凭据文件名 + 凭据环境变量名）。A10：以 record 取代 String[] 结伴。 */
    private static final Map<String, ChannelScriptSpec> CHANNEL_SCRIPTS = Map.of(
            "CAISHIXIAN", new ChannelScriptSpec(
                    "caishixian_fetch_orders.py", "csx-credentials.txt",
                    List.of("CSX_USERNAME", "CSX_PASSWORD", "CSX_SUPPLIER_CODE")),
            "JUFUBAO", new ChannelScriptSpec(
                    "jufubao_fetch_orders.py", "jufubao-credentials.txt",
                    List.of("JFUBAO_USERNAME", "JFUBAO_PASSWORD")),
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
    /** F5：自动确认配置项（本轮恒不自动确认，仅预留；见类注释）。 */
    private final boolean autoConfirm;

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
            @Value("${app.platform-pull.default-days:30}") int defaultDays,
            @Value("${app.platform-pull.auto-confirm:false}") boolean autoConfirm) {
        this.sourceImportService = sourceImportService;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
        this.singleFlight = singleFlight;
        this.scriptRunner = scriptRunner;
        Map<SourceChannel, PlatformConnector> collected = new EnumMap<>(SourceChannel.class);
        platformConnectors.forEach(connector -> collected.put(connector.channel(), connector));
        this.connectors = collected;
        this.autoConfirm = autoConfirm;
        this.scriptsDir = Path.of(scriptsDir);
        this.credentialsDir = Path.of(credentialsDir);
        this.workDir = Path.of(workDir);
        this.scriptTimeout = scriptTimeout;
        this.defaultDays = defaultDays;
        if (autoConfirm) {
            // F5：本轮未实现自动确认逻辑；配置项仅作预留，打开时告警避免误以为已生效。
            log.warn("app.platform-pull.auto-confirm=true 但本轮未实现自动确认逻辑（人工闸门保留），配置项仅作预留");
        }
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
        try (PlatformPullSingleFlight.Lease pullLease = singleFlight.tryAcquire(channel)) {
            if (!pullLease.acquired()) {
                result.put("status", "SKIPPED");
                result.put("message", "该渠道已有拉取任务进行中，本次未重复发起");
                return finish(
                        channel, begin, end, context, result, "PLATFORM_PULL_IN_PROGRESS", command, started);
            }
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
                    return finish(channel, begin, end, context, result, null, command, started);
                }
                if ("JUFUBAO".equals(channel)) {
                    handleJufubaoJson(artifact, result);
                } else {
                    importXlsx(channel, artifact, result, context);
                }
                return finish(channel, begin, end, context, result, null, command, started);
            } finally {
                // A6：临时目录含拉取产物（聚福宝 payload.json 含收货人电话/地址），执行后必须清理。
                scriptRunner.deleteRecursively(outDir);
            }
        } catch (PlatformScriptRunner.PlatformScriptException ex) {
            result.put("status", "FAILED");
            result.put("message", ex.getMessage());
            return finish(channel, begin, end, context, result, "SCRIPT_FAILED", command, started);
        } catch (BusinessException ex) {
            result.put("status", "FAILED");
            result.put("message", ex.getMessage());
            return finish(channel, begin, end, context, result, ex.getBusinessCode(), command, started);
        } catch (Exception ex) {
            log.error("平台订单刷新失败: channel={}", channel, ex);
            result.put("status", "FAILED");
            result.put("message", "刷新失败: " + ex.getMessage());
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
        if (command != null) {
            request.put("command", command);
        }
        audit(context, channel, "refresh", request, result, businessCode, latencyMs);
        return result;
    }

    /** 成功更新 last_pull_at 并清空 last_error；失败写入 last_error；SKIPPED 不动。 */
    private void updatePullState(String channel, Map<String, Object> result) {
        String status = String.valueOf(result.get("status"));
        if ("OK".equals(status)) {
            jdbc.update(
                    """
                    UPDATE app.connector_configs
                    SET last_pull_at=CURRENT_TIMESTAMP, last_error=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE source_channel=?
                    """,
                    channel);
        } else if ("FAILED".equals(status)) {
            Map<String, Object> error = Map.of(
                    "status", status,
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

    /** 组装渠道拉取命令；彩食鲜用 export 模式（xlsx），飞象直下 xlsx，聚福宝 JSON。 */
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
            case "JUFUBAO", "FEIXIANG" -> {
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

    /** 聚福宝 JSON 直连缺收货人字段（票 15 blocker）：只报告数量，不自动导入。 */
    private void handleJufubaoJson(Path artifact, Map<String, Object> result) {
        try {
            Map<String, Object> payload = PlatformScriptRunner.parseJson(
                    Files.readString(artifact, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
            Object orders = payload.get("orders");
            int count = orders instanceof List<?> list ? list.size() : 0;
            result.put("status", "OK");
            result.put("order_count", count);
            result.put("message", "已拉取聚福宝待发货订单 " + count + " 单（JSON 直连缺收货人字段，未自动导入，请人工导表上传）");
            result.put("file_name", artifact.getFileName().toString());
        } catch (IOException | IllegalStateException ex) {
            result.put("status", "FAILED");
            result.put("message", "解析聚福宝拉取结果失败: " + ex.getMessage());
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
}
