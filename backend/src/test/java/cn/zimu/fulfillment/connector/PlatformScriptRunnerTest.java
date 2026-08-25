package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 第二轮评审 F4 修复验证：run 启动进程后并发读取 stdout——子进程输出超过管道缓冲（~64KB）
 * 时不再因先 waitFor 后读而假超时强杀；超时强杀与退出码语义保持不变。
 */
class PlatformScriptRunnerTest {

    private final PlatformScriptRunner runner = new PlatformScriptRunner();

    @Test
    void readsLargeStdoutWithoutFalseTimeout() {
        // 输出 ~100KB > 管道缓冲 64KB：旧的「先 waitFor 后读」实现会因子进程写端阻塞而假超时
        PlatformScriptRunner.ScriptExecution exec = runner.run(
                List.of("python3", "-c", "import sys; sys.stdout.write('x' * 102400)"),
                Map.of(),
                Duration.ofSeconds(10));

        assertThat(exec.timedOut()).isFalse();
        assertThat(exec.exitCode()).isZero();
        assertThat(exec.output()).hasSize(102400);
    }

    @Test
    void timesOutAndKillsProcess() {
        PlatformScriptRunner.ScriptExecution exec = runner.run(
                List.of("python3", "-c", "import time; time.sleep(5)"),
                Map.of(),
                Duration.ofSeconds(1));

        assertThat(exec.timedOut()).isTrue();
    }
}
