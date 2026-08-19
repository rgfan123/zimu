package cn.zimu.fulfillment.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 生产 QUALITY 执行器（meta-agent-platform-impl 09）：{@code ProcessBuilder} 跑
 * {@code npx promptfoo eval}（本地/CI 形态）。密钥经环境变量（promptfoo deepseek provider
 * 读取 {@code DEEPSEEK_API_KEY}，配置里只有 {@code {{env.…}}} 引用，无字面密钥）；
 * 超时强杀；stdout 由独立线程排空防止管道阻塞死锁。
 */
public class NpxPromptfooRunner implements QualityEvalRunner {

    private final Path workDir;
    private final long timeoutMillis;
    private final ExecutorService stdoutDrain = Executors.newCachedThreadPool();

    public NpxPromptfooRunner(Path workDir, long timeoutMillis) {
        this.workDir = workDir;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public RunResult run(Path configFile, Path outputFile) {
        ProcessBuilder builder = new ProcessBuilder(
                "npx",
                "promptfoo",
                "eval",
                "--config",
                configFile.toString(),
                "--output",
                outputFile.toString(),
                "--no-cache");
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            Future<byte[]> drained = stdoutDrain.submit(() -> process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                // 超时强杀：连同 npx→node 后代进程一并销毁，防止 promptfoo 残留
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
                drained.cancel(true);
                return new RunResult(-1, "", "promptfoo eval 执行超时（" + timeoutMillis + "ms）");
            }
            try {
                String outputText = new String(drained.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
                String outputJson = Files.exists(outputFile) ? Files.readString(outputFile) : "";
                return new RunResult(process.exitValue(), outputJson, outputText);
            } finally {
                shutdownExecutor();
            }
        } catch (IOException ex) {
            shutdownExecutor();
            return new RunResult(-1, "", "promptfoo 启动失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            shutdownExecutor();
            return new RunResult(-1, "", "promptfoo eval 被中断");
        } catch (ExecutionException | TimeoutException ex) {
            shutdownExecutor();
            return new RunResult(-1, "", "promptfoo 输出排空失败: " + ex.getMessage());
        }
    }

    /** 单次调用后收口排空线程池（进程已退出，Future 已消费；防线程泄漏）。 */
    private void shutdownExecutor() {
        stdoutDrain.shutdownNow();
    }
}
