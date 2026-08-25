package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * 三平台脚本通道共享执行器（A8：抽取自 PlatformOrderRefreshService 与 SourceReturnPushService 的重复代码）。
 *
 * <p>统一承载三类职责：本地凭据文件解析（环境变量优先，文件仅作本地开发兜底，见 {@link #readCredentials}）、
 * 脚本进程执行（超时熔断 + 输出捕获，超时/退出码由调用方决定，见 {@link #run}）、临时目录创建与清理。
 * 两个服务行为保持与拆分前一致；本类不持有业务状态，无事务边界（外部脚本绝不应持有数据库事务/连接）。
 *
 * <p>放在 connector 包的理由：本类是「平台脚本通道」的传输层原语，拉取（connector）与回传（file）两条
 * 通道都复用；connector 包已被 PlatformOrderRefreshService 使用，避免 file 包反向依赖其私有实现。
 */
@Component
public class PlatformScriptRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 脚本执行结果：是否超时、退出码、完整输出（由调用方自行 tail 截断）。 */
    public record ScriptExecution(boolean timedOut, int exitCode, String output) {}

    /** 脚本通道异常：超时/中断/进程级失败。平台明确拒绝（有 code/message）不属于本异常。 */
    public static class PlatformScriptException extends RuntimeException {
        public PlatformScriptException(String message) {
            super(message);
        }

        public PlatformScriptException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 审计命令公共前缀（A8：两个 service 重复的 audit 装配）：dataScope/requestId/traceId/operator/actorType
     * 各通道一致，service/operation/payload/httpStatus/businessCode/latencyMs 由调用方继续装配。
     */
    public static AuditLogService.AuditCommand baseAuditCommand(CommandContext context) {
        return new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN);
    }

    /**
     * 解析脚本凭据，返回注入脚本进程的环境变量。
     *
     * <p>spec 红线「凭据只走环境变量」：优先从 {@link System#getenv()} 读取；仅当该渠道要求的
     * 环境变量未全部配置时才回退读取本地明文凭据文件（data-local 挂载路径，仅供本地开发）。
     * 半配置（部分环境变量存在）时以环境变量优先、文件兜底合并，保证本地开发可用、生产不落明文。
     *
     * @param file    本地凭据文件（KEY=VALUE 行，忽略 # 注释与空行）；envKeys 全部配置时可不存在
     * @param envKeys 该渠道要求的凭据环境变量名（如 CSX_USERNAME/CSX_PASSWORD）
     */
    public Map<String, String> readCredentials(Path file, List<String> envKeys) throws IOException {
        List<String> configuredKeys = envKeys.stream()
                .filter(key -> isPresent(System.getenv(key)))
                .toList();
        if (configuredKeys.size() == envKeys.size() && !envKeys.isEmpty()) {
            // 全部由环境变量提供：不读明文文件（生产唯一合规路径）
            Map<String, String> env = new LinkedHashMap<>();
            configuredKeys.forEach(key -> env.put(key, System.getenv(key)));
            return env;
        }
        // 本地开发兜底：读明文凭据文件，已配置的环境变量优先覆盖同名键
        Map<String, String> env = parseCredentialFile(file);
        configuredKeys.forEach(key -> env.put(key, System.getenv(key)));
        return env;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, String> parseCredentialFile(Path file) throws IOException {
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

    /** 在 workDir 下创建一次性临时目录（脚本产物落盘区）。 */
    public Path createTempDirectory(Path workDir, String prefix) throws IOException {
        return Files.createTempDirectory(workDir, prefix);
    }

    /** 递归删除临时目录（PII 落盘清理，A6）：产物含收货人电话/地址，执行后必须清理。 */
    public void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不掩盖业务结果；残留仅限 tmp 目录
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 执行脚本进程：注入凭据环境变量、合并 stderr 到 stdout、等待超时并捕获完整输出。
     *
     * <p>超时不抛异常而是返回 timedOut=true（进程已被强杀），非零退出码也不抛——是否算失败由调用方
     * 决定（回传通道需在退出码非零时仍尝试解析结果文件，区分「平台拒绝」与「结果未知」，见 A4）。
     * 仅在线程中断时抛出 {@link PlatformScriptException}。
     *
     * <p>第二轮评审 F4：启动进程后<b>并发读取 stdout</b>（CompletableFuture 独立线程 drain 管道），
     * 再 waitFor 超时——否则子进程输出超过管道缓冲（~64KB）时会阻塞写端，先 waitFor 后读的实现
     * 会假超时强杀；读取线程在超时分支 destroyForcibly 后 join 取完整输出。</p>
     */
    public ScriptExecution run(List<String> command, Map<String, String> environment, Duration timeout) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().putAll(environment);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            // F4：并发读取 stdout，避免输出超管道缓冲时子进程写端阻塞导致假超时。
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder output = new StringBuilder();
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        output.append(buffer, 0, read);
                    }
                } catch (IOException ex) {
                    // 进程被强杀后管道关闭属预期；已读到的输出仍返回
                }
                return output.toString();
            });
            boolean finished;
            try {
                finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new PlatformScriptException("脚本进程被中断: " + command.getFirst(), ex);
            }
            if (!finished) {
                process.destroyForcibly();
            }
            // join 读取线程取完整输出（超时分支强杀后管道关闭 → EOF；10s 兜底防读取线程卡死）
            String output;
            try {
                output = outputFuture.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                output = "";
            } catch (Exception ex) {
                output = "";
            }
            if (!finished) {
                return new ScriptExecution(true, -1, output);
            }
            return new ScriptExecution(false, process.exitValue(), output);
        } catch (IOException ex) {
            throw new PlatformScriptException("启动脚本进程失败: " + command.getFirst() + ": " + ex.getMessage(), ex);
        }
    }

    // ---------------------------------------------------------------- 共享 JSON/文本工具

    /** JSON 序列化（F4 上提自 PlatformOrderRefreshService/SourceReturnPushService 的重复实现）。 */
    public static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    /** JSON 反序列化（F4 上提；解析失败抛 IllegalStateException，由调用方决定失败语义）。 */
    public static <T> T parseJson(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 解析失败", ex);
        }
    }

    /** 取文本末尾最多 max 字符（脚本输出截断展示用）。 */
    public static String tail(String text, int max) {
        return text.length() <= max ? text : text.substring(text.length() - max);
    }
}
