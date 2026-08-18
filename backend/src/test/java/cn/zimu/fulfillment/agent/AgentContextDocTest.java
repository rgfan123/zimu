package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 02 — CONTEXT.md 边界行修订断言（agent-decision-layer 02）：仓库根 CONTEXT.md 已把
 * “Agent 自动决策调度”从“本系统不负责”清单移除，改为“本系统提供 Agent 决策/调度层，
 * 一期 Agent 仅做只读分析与建议，写操作必须人工确认”的表述。
 *
 * <p>Surefire 工作目录为 backend/，仓库根 CONTEXT.md 位于上级目录；若按任何候选路径
 * 都找不到文件（非标准运行方式）则跳过断言而非失败。
 */
class AgentContextDocTest {

    @Test
    void contextBoundaryLineMovedAgentDecisionLayerIntoSystemScope() throws IOException {
        Path context = resolveContextFile();
        if (context == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "未找到仓库根 CONTEXT.md，跳过");
        }
        String content = Files.readString(context, StandardCharsets.UTF_8);

        String boundaryLine = content.lines()
                .filter(line -> line.contains("本系统不负责"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CONTEXT.md 缺少边界行（本系统不负责）"));

        assertThat(boundaryLine)
                .as("旧表述不得继续出现")
                .doesNotContain("Agent 自动决策调度");
        assertThat(boundaryLine)
                .as("新表述：提供 Agent 决策/调度层")
                .contains("本系统提供 Agent 决策/调度层");
        assertThat(boundaryLine)
                .as("一期 Agent 仅只读建议")
                .contains("一期 Agent 仅做只读分析与建议");
        assertThat(boundaryLine)
                .as("写操作必须人工确认")
                .contains("任何业务写操作仍必须经授权人工确认");
        assertThat(boundaryLine)
                .as("Agent 职责经代码定义 + MCP 只读工具执行")
                .contains("采购、意图识别、数据查询等 Agent 职责由代码定义并通过 MCP 只读工具执行");
    }

    private static Path resolveContextFile() {
        String userDir = System.getProperty("user.dir", ".");
        for (String candidate : new String[] {
                "CONTEXT.md", "../CONTEXT.md", "../../CONTEXT.md"}) {
            Path path = Path.of(userDir, candidate).normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }
}
