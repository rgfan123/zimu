package cn.zimu.fulfillment.agent;

import java.nio.file.Path;

/**
 * QUALITY 评测执行器接缝（meta-agent-platform-impl 09）：跑 {@code promptfoo eval} 并返回
 * 结果文件内容。业务代码只依赖本接口——测试注入假实现（执行器可 mock），生产默认
 * {@link NpxPromptfooRunner}（ProcessBuilder 跑 npx）。
 */
public interface QualityEvalRunner {

    /** 执行一次 promptfoo eval。{@code outputFile} 为 {@code --output} 结果 JSON 路径。 */
    RunResult run(Path configFile, Path outputFile);

    /** 执行结果：退出码 + 结果 JSON 文本 + 错误输出（stderr/stdout 摘要）。 */
    record RunResult(int exitCode, String outputJson, String errorText) {

        public boolean succeeded() {
            return exitCode == 0;
        }
    }
}
