package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * 环境变量样例文件必须分别写明 Agent 与 MCP 协议模块开放清单。
 *
 * <p>空值语义反转成 fail-safe 之后，「照 .env.example 拉起的环境」拿到的是零工具而不是全部工具；
 * 这本身是安全的，但样例文件必须同时说清「留空 = 不开放任何 MCP 工具」并给出与生产一致的示例值，
 * 否则新环境只会得到一个查不出原因的哑 MCP。这条约束靠人肉 review 记不住，用例钉住它。
 *
 * <p>Surefire 工作目录为 backend/，样例文件在仓库根；按候选路径都找不到时跳过而不是失败
 * （与 {@code AgentContextDocTest} 同一处理方式）。
 */
class McpModulesEnvExampleTest {

    /** 生产当前的显式配置（只读三模块），样例值必须与之一致。 */
    private static final String PRODUCTION_PROTOCOL_MODULES = "masterdata,inventory,orders-read";
    private static final String DEFAULT_AGENT_MODULES =
            "messages,orders,masterdata,inventory,procurement,orders-read,bundles-read,followup,control,write";

    @Test
    void envExampleDeclaresModulesWithProductionValueAndFailSafeNote() throws IOException {
        Path envExample = resolveEnvExample();
        Assumptions.assumeTrue(envExample != null, "未找到仓库根 .env.example，跳过");

        List<String> lines = Files.readAllLines(envExample, StandardCharsets.UTF_8);
        int protocolIndex = indexOfSetting(lines, "MCP_MODULES");
        assertThat(protocolIndex)
                .as(".env.example 必须包含 MCP_MODULES 配置项")
                .isNotNegative();

        assertThat(lines.get(protocolIndex).strip())
                .as("样例值与生产显式配置一致")
                .isEqualTo("MCP_MODULES=" + PRODUCTION_PROTOCOL_MODULES);

        String comment = commentBlockAbove(lines, protocolIndex);
        assertThat(comment)
                .as("必须注明留空的语义，否则运维只会看到一个不解释自己的哑 MCP")
                .contains("留空 = 不开放任何 MCP 工具");

        int agentIndex = indexOfSetting(lines, "AGENT_TOOL_MODULES");
        assertThat(agentIndex)
                .as(".env.example 必须单独声明 Agent 工具面，不能继续从公共 MCP 清单推导")
                .isNotNegative();
        assertThat(lines.get(agentIndex).strip())
                .isEqualTo("AGENT_TOOL_MODULES=" + DEFAULT_AGENT_MODULES);
    }

    @Test
    void protocolModulesPreferNewEnvironmentVariableAndFallBackToLegacyVariable() throws IOException {
        assertThat(resolveApplicationProperty(
                        "app.mcp.protocol-modules",
                        Map.of("MCP_PROTOCOL_MODULES", "masterdata", "MCP_MODULES", "inventory")))
                .isEqualTo("masterdata");
        assertThat(resolveApplicationProperty(
                        "app.mcp.protocol-modules", Map.of("MCP_MODULES", PRODUCTION_PROTOCOL_MODULES)))
                .isEqualTo(PRODUCTION_PROTOCOL_MODULES);
        assertThat(resolveApplicationProperty("app.mcp.protocol-modules", Map.of())).isEmpty();
    }

    @Test
    void explicitEmptyAgentEnvironmentVariableProducesEmptySurfaceInsteadOfDefault() throws IOException {
        assertThat(resolveApplicationProperty("app.agent.tool-modules", Map.of("AGENT_TOOL_MODULES", "")))
                .isEmpty();
        assertThat(resolveApplicationProperty("app.agent.tool-modules", Map.of()))
                .isEqualTo(DEFAULT_AGENT_MODULES);
    }

    private static int indexOfSetting(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith(name + "=")) {
                return i;
            }
        }
        return -1;
    }

    /** 紧邻该配置项上方的连续注释行。 */
    private static String commentBlockAbove(List<String> lines, int index) {
        StringBuilder comment = new StringBuilder();
        for (int i = index - 1; i >= 0 && lines.get(i).stripLeading().startsWith("#"); i--) {
            comment.insert(0, lines.get(i) + "\n");
        }
        return comment.toString();
    }

    private static Path resolveEnvExample() {
        String userDir = System.getProperty("user.dir", ".");
        for (String candidate : new String[] {".env.example", "../.env.example", "../../.env.example"}) {
            Path path = Path.of(userDir, candidate).normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static String resolveApplicationProperty(String name, Map<String, Object> environment)
            throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("test-environment", environment));
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yamlSources.forEach(sources::addLast);
        return new PropertySourcesPropertyResolver(sources).getProperty(name, String.class);
    }
}
