package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.file.SourceImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 三平台订单数据刷新编排（人工触发，Phase 0 脚本通道）。
 *
 * <p>对每个渠道：读取本地凭据文件 → 进程内执行对应拉取脚本（超时熔断）→
 * 产物文件（彩食鲜/飞象 xlsx）自动上传为 NEW 导入批次（内容哈希幂等，
 * 重复刷新不产生重复批次）→ 聚福宝 JSON 缺收货人字段，只报告拉取数量。
 * 全程审计；单渠道失败不阻断其他渠道。
 */
@Service
public class PlatformOrderRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PlatformOrderRefreshService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> DEFAULT_CHANNELS = List.of("CAISHIXIAN", "JUFUBAO", "FEIXIANG");

    /** 渠道 → 脚本文件名与凭据文件名。 */
    private static final Map<String, String[]> CHANNEL_SCRIPTS = Map.of(
            "CAISHIXIAN", new String[]{"caishixian_fetch_orders.py", "csx-credentials.txt"},
            "JUFUBAO", new String[]{"jufubao_fetch_orders.py", "jufubao-credentials.txt"},
            "FEIXIANG", new String[]{"feixiang_fetch_orders.py", "feixiang-credentials.txt"});

    private final SourceImportService sourceImportService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    private final Path scriptsDir;
    private final Path credentialsDir;
    private final Path workDir;
    private final Duration scriptTimeout;
    private final int defaultDays;

    PlatformOrderRefreshService(
            SourceImportService sourceImportService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${app.platform-pull.scripts-dir:${java.io.tmpdir}/zimu-platform-pull-scripts}") String scriptsDir,
            @Value("${app.platform-pull.credentials-dir:${java.io.tmpdir}/zimu-platform-pull-credentials}") String credentialsDir,
            @Value("${app.platform-pull.work-dir:${java.io.tmpdir}/zimu-platform-pull}") String workDir,
            @Value("${app.platform-pull.script-timeout:PT10M}") Duration scriptTimeout,
            @Value("${app.platform-pull.default-days:30}") int defaultDays) {
        this.sourceImportService = sourceImportService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
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
        String[] spec = CHANNEL_SCRIPTS.get(channel);
        if (spec == null) {
            result.put("status", "SKIPPED");
            result.put("message", "不支持的渠道: " + channel);
            return result;
        }
        Path script = scriptsDir.resolve(spec[0]);
        Path credentialFile = credentialsDir.resolve(spec[1]);
        if (!Files.isRegularFile(script)) {
            result.put("status", "FAILED");
            result.put("message", "拉取脚本不存在: " + script);
            return result;
        }
        if (!Files.isRegularFile(credentialFile)) {
            result.put("status", "FAILED");
            result.put("message", "凭据文件不存在: " + credentialFile + "（请先在 data-local/ 配置对应凭据）");
            return result;
        }

        Path outDir;
        try {
            outDir = Files.createTempDirectory(workDir, channel.toLowerCase() + "-");
        } catch (IOException ex) {
            result.put("status", "FAILED");
            result.put("message", "创建工作目录失败: " + ex.getMessage());
            return result;
        }

        List<String> command = buildCommand(channel, script, begin, end, outDir);
        try {
            runScript(command, credentialFile, result);
            Path artifact = newestFile(outDir);
            if (artifact == null) {
                result.put("status", "FAILED");
                result.put("message", "脚本执行完成但未产出文件");
                return result;
            }
            if ("JUFUBAO".equals(channel)) {
                handleJufubaoJson(artifact, result);
            } else {
                importXlsx(channel, artifact, result, context);
            }
            audit(context, channel, "refresh", Map.of(
                    "command", command,
                    "date_begin", begin.format(DAY),
                    "date_end", end.format(DAY)), result, "OK");
        } catch (ScriptFailedException ex) {
            result.put("status", "FAILED");
            result.put("message", ex.getMessage());
            audit(context, channel, "refresh", Map.of(
                    "command", command,
                    "date_begin", begin.format(DAY),
                    "date_end", end.format(DAY)), result, "SCRIPT_FAILED");
        } catch (BusinessException ex) {
            result.put("status", "FAILED");
            result.put("message", ex.getMessage());
            audit(context, channel, "refresh", Map.of(
                    "command", command,
                    "date_begin", begin.format(DAY),
                    "date_end", end.format(DAY)), result, ex.getBusinessCode());
        } catch (Exception ex) {
            log.error("平台订单刷新失败: channel={}", channel, ex);
            result.put("status", "FAILED");
            result.put("message", "刷新失败: " + ex.getMessage());
            audit(context, channel, "refresh", Map.of(
                    "command", command,
                    "date_begin", begin.format(DAY),
                    "date_end", end.format(DAY)), result, "INTERNAL_ERROR");
        } finally {
            result.put("latency_ms", (int) ((System.nanoTime() - started) / 1_000_000));
        }
        return result;
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

    private void runScript(List<String> command, Path credentialFile, Map<String, Object> result)
            throws ScriptFailedException, IOException {
        log.info("执行平台拉取: {}", command);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(readCredentials(credentialFile));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        boolean finished;
        try {
            finished = process.waitFor(scriptTimeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ScriptFailedException("拉取脚本被中断");
        }
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                output.append(buffer, 0, read);
            }
        }
        if (!finished) {
            process.destroyForcibly();
            throw new ScriptFailedException("拉取脚本执行超时（" + scriptTimeout + "）");
        }
        int exit = process.exitValue();
        if (exit != 0) {
            throw new ScriptFailedException("拉取脚本退出码 " + exit + ": " + tail(output.toString(), 1200));
        }
        result.put("script_output", tail(output.toString(), 400));
    }

    /** 解析本地凭据文件（KEY=VALUE 行，忽略 # 注释与空行）。 */
    private Map<String, String> readCredentials(Path file) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                env.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return env;
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
            Map<String, Object> payload = objectMapper.readValue(
                    Files.readString(artifact, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, Object>>() {});
            Object orders = payload.get("orders");
            int count = orders instanceof List<?> list ? list.size() : 0;
            result.put("status", "OK");
            result.put("order_count", count);
            result.put("message", "已拉取聚福宝待发货订单 " + count + " 单（JSON 直连缺收货人字段，未自动导入，请人工导表上传）");
            result.put("file_name", artifact.getFileName().toString());
        } catch (IOException ex) {
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
            String businessCode) {
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("platform-pull." + channel)
                .operation(operation)
                .requestPayload(request)
                .responsePayload(response)
                .httpStatus(200)
                .businessCode(businessCode)
                .latencyMs(0));
    }

    private static LocalDate parseDay(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return LocalDate.parse(value, DAY);
    }

    private static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    private static final class ScriptFailedException extends RuntimeException {
        ScriptFailedException(String message) {
            super(message);
        }
    }
}
