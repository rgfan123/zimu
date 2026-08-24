package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 09 — promptfoo 真实冒烟（meta-agent-platform-impl 09）：生成器产出的 config YAML +
 * chat 消息 JSON 被 {@code npx promptfoo eval} 端到端消费（provider 换成 promptfoo 内置
 * {@code echo}，无密钥/无网络依赖——密钥路径零依赖；deepseek provider 的真实调用由
 * CI/本地设置 {@code DEEPSEEK_API_KEY} 后同一配置直接跑）；结果 JSON 可被
 * {@link PromptfooEvalResult} 解析。默认关闭（{@code PROMPTFOO_SMOKE=1} 时启用）。
 */
@EnabledIfEnvironmentVariable(named = "PROMPTFOO_SMOKE", matches = "1")
class PromptfooEvalSmokeTest {

    @Test
    void generatedYamlIsConsumedByPromptfooEvalEndToEnd() throws Exception {
        AgentDefinition definition = AgentSeedFixtures.procurementDefinition();
        List<QualityEvalCase> cases = List.of(
                new QualityEvalCase(1, "采购工单 9005 还差多少数量", List.of("9005", "件")),
                new QualityEvalCase(2, "SKU-EVAL-000001 的进货价是多少", List.of("进货价")));

        Path dir = Files.createTempDirectory("promptfoo-smoke-");
        Path chat = dir.resolve("chat.json");
        Path config = dir.resolve("promptfoo.yaml");
        Path result = dir.resolve("results.json");
        Files.writeString(chat, PromptfooYamlGenerator.chatMessagesJson(definition), StandardCharsets.UTF_8);
        String deepseekConfig = PromptfooYamlGenerator.generateConfig(definition, cases);
        // 冒烟专用：provider 换成内置 echo（同一 prompts/tests/asserts 结构，免密钥免网络）
        String echoConfig = deepseekConfig.replace(
                """
                providers:
                  - "id": "deepseek:deepseek-chat"
                    "config":
                      "apiKey": "{{env.DEEPSEEK_API_KEY}}"
                """,
                "providers:\n  - echo\n");
        assertThat(echoConfig).as("echo provider 替换必须生效").contains("echo");
        Files.writeString(config, echoConfig, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(
                        "npx", "--yes", "promptfoo", "eval",
                        "--config", config.toString(),
                        "--output", result.toString(),
                        "--no-cache")
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        java.util.concurrent.Future<byte[]> drained =
                java.util.concurrent.Executors.newSingleThreadExecutor()
                        .submit(() -> process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("promptfoo eval 冒烟超时");
        }
        String console = new String(drained.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8);

        // echo 输出是提示词原文，不满足领域断言（1 passed/1 failed 属预期）——
        // 冒烟只证明「配置被端到端消费 + 结果 JSON 产生」，退出码非 0 不代表配置失败
        assertThat(process.exitValue())
                .as("promptfoo eval 必须运行到完成（生成结果 JSON）；控制台输出: %s", console)
                .isNotEqualTo(-1);

        // 结果 JSON 可被解析且两条用例均执行（无 error）
        PromptfooEvalResult parsed = PromptfooEvalResult.parse(Files.readString(result));
        assertThat(parsed.caseCount()).isEqualTo(2);
        assertThat(parsed.scores()).hasSize(2);
    }
}
