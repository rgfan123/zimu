package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 第二轮评审 F4 修复验证：run 启动进程后并发读取 stdout——子进程输出超过管道缓冲（~64KB）
 * 时不再因先 waitFor 后读而假超时强杀；超时强杀与退出码语义保持不变。
 */
class PlatformScriptRunnerTest {

    private final PlatformScriptRunner runner = new PlatformScriptRunner();

    @Test
    void drainsLargeStdoutWithoutFalseTimeoutAndKeepsOnlyBoundedTail() {
        int outputSize = PlatformScriptRunner.MAX_CAPTURED_OUTPUT_CHARS + 102_400;
        PlatformScriptRunner.ScriptExecution exec = runner.run(
                List.of("python3", "-c",
                        "import sys; sys.stdout.write('x' * " + outputSize + " + 'CAPTURED_TAIL')"),
                Map.of(),
                Duration.ofSeconds(10));

        assertThat(exec.timedOut()).isFalse();
        assertThat(exec.exitCode()).isZero();
        assertThat(exec.output()).hasSize(PlatformScriptRunner.MAX_CAPTURED_OUTPUT_CHARS)
                .endsWith("CAPTURED_TAIL");
    }

    @Test
    void timesOutAndKillsProcess() {
        PlatformScriptRunner.ScriptExecution exec = runner.run(
                List.of("python3", "-c", "import time; time.sleep(5)"),
                Map.of(),
                Duration.ofSeconds(1));

        assertThat(exec.timedOut()).isTrue();
    }

    @Test
    void reportsCleanupFailureWithoutExposingTemporaryPath(@TempDir Path tempDir) throws Exception {
        Path sensitiveFile = Files.writeString(tempDir.resolve("orders.xlsx"), "receiver phone and address");
        PlatformScriptRunner failingRunner = new PlatformScriptRunner() {
            @Override
            void deletePath(Path path) throws IOException {
                throw new IOException("simulated cleanup failure");
            }
        };

        PlatformScriptRunner.CleanupResult result = failingRunner.deleteRecursively(tempDir);

        assertThat(result.complete()).isFalse();
        assertThat(result.failureCount()).isGreaterThan(0);
        assertThat(result.pathIdentifier()).doesNotContain(tempDir.toString())
                .doesNotContain(sensitiveFile.getFileName().toString())
                .doesNotContain("receiver phone and address");
    }
}
