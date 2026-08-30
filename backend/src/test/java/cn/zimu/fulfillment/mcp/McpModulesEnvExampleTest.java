package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Test
    void composePreservesExplicitEmptyAgentSurfaceAndPassesNewProtocolOverride() throws IOException {
        Path compose = resolveRepositoryFile("docker-compose.yml");
        Assumptions.assumeTrue(compose != null, "未找到仓库根 docker-compose.yml，跳过");

        String yaml = Files.readString(compose, StandardCharsets.UTF_8);
        assertThat(yaml)
                .as("Compose 必须区分 AGENT_TOOL_MODULES 未设置与显式空值")
                .contains("AGENT_TOOL_MODULES: ${AGENT_TOOL_MODULES-" + DEFAULT_AGENT_MODULES + "}")
                .doesNotContain("AGENT_TOOL_MODULES: ${AGENT_TOOL_MODULES:-");
        assertThat(yaml)
                .as("新协议变量必须进入容器，Spring 的新变量优先级才会生效")
                .containsPattern("(?m)^\\s+MCP_PROTOCOL_MODULES:\\s*$")
                .doesNotContain("MCP_PROTOCOL_MODULES: ${");
    }

    @Test
    void dockerComposeConfigPreservesUnsetEmptyAndNewProtocolPrecedence() throws Exception {
        Path compose = resolveRepositoryFile("docker-compose.yml");
        Assumptions.assumeTrue(compose != null, "未找到仓库根 docker-compose.yml，跳过");
        Assumptions.assumeTrue(dockerComposeAvailable(), "docker compose 不可用，跳过真实 config 契约测试");

        JsonNode unset = backendEnvironment(compose, Map.of());
        assertThat(unset.path("AGENT_TOOL_MODULES").asText()).isEqualTo(DEFAULT_AGENT_MODULES);
        assertThat(unset.path("MCP_PROTOCOL_MODULES").isNull())
                .as("新变量未设置时 Compose 模型必须保留未解析 null，启动容器时才会省略")
                .isTrue();
        assertThat(unset.path("MCP_MODULES").asText()).isEmpty();

        JsonNode explicitEmpty = backendEnvironment(
                compose,
                Map.of("AGENT_TOOL_MODULES", "", "MCP_PROTOCOL_MODULES", "", "MCP_MODULES", "inventory"));
        assertThat(explicitEmpty.path("AGENT_TOOL_MODULES").asText()).isEmpty();
        assertThat(explicitEmpty.has("MCP_PROTOCOL_MODULES")).isTrue();
        assertThat(explicitEmpty.path("MCP_PROTOCOL_MODULES").asText()).isEmpty();
        assertThat(resolveApplicationProperty(
                        "app.mcp.protocol-modules", environmentMap(explicitEmpty)))
                .as("新变量显式空必须覆盖非空旧变量")
                .isEmpty();

        JsonNode legacyOnly = backendEnvironment(compose, Map.of("MCP_MODULES", "inventory"));
        assertThat(legacyOnly.path("MCP_PROTOCOL_MODULES").isNull()).isTrue();
        assertThat(resolveApplicationProperty(
                        "app.mcp.protocol-modules", environmentMap(legacyOnly)))
                .isEqualTo("inventory");

        JsonNode newWins = backendEnvironment(
                compose,
                Map.of(
                        "AGENT_TOOL_MODULES", "orders",
                        "MCP_PROTOCOL_MODULES", "masterdata",
                        "MCP_MODULES", "inventory"));
        assertThat(newWins.path("AGENT_TOOL_MODULES").asText()).isEqualTo("orders");
        assertThat(resolveApplicationProperty("app.mcp.protocol-modules", environmentMap(newWins)))
                .isEqualTo("masterdata");
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
        return resolveRepositoryFile(".env.example");
    }

    private static Path resolveRepositoryFile(String name) {
        String userDir = System.getProperty("user.dir", ".");
        for (String candidate : new String[] {name, "../" + name, "../../" + name}) {
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

    private static boolean dockerComposeAvailable() {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("docker", "compose", "version");
            controlledEnvironment(builder, Map.of());
            process = builder.redirectErrorStream(true).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static JsonNode backendEnvironment(Path compose, Map<String, String> variables) throws Exception {
        Path repository = compose.getParent();
        Path overlay = Files.createTempFile("zimu-compose-contract-", ".yml");
        Path envFile = Files.createTempFile("zimu-compose-contract-", ".env");
        Path outputFile = Files.createTempFile("zimu-compose-contract-", ".json");
        try {
            Files.writeString(
                    overlay,
                    "services:\n  backend:\n    env_file: !reset []\n",
                    StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(
                    "docker",
                    "compose",
                    "--project-directory",
                    repository.toString(),
                    "--env-file",
                    envFile.toString(),
                    "-f",
                    compose.toString(),
                    "-f",
                    overlay.toString(),
                    "config",
                    "--format",
                    "json");
            controlledEnvironment(builder, variables);
            Process process = builder.redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("docker compose config 超时");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertThat(process.exitValue())
                    .as("docker compose config 应成功，输出：%s", output)
                    .isZero();
            return MAPPER.readTree(output).path("services").path("backend").path("environment");
        } finally {
            Files.deleteIfExists(overlay);
            Files.deleteIfExists(envFile);
            Files.deleteIfExists(outputFile);
        }
    }

    private static Map<String, Object> environmentMap(JsonNode environment) {
        Map<String, Object> values = new HashMap<>();
        environment.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return values;
    }

    private static void controlledEnvironment(ProcessBuilder builder, Map<String, String> variables) {
        Map<String, String> inherited = System.getenv();
        Map<String, String> environment = builder.environment();
        environment.clear();
        for (String name : List.of("PATH", "HOME", "DOCKER_CONFIG", "TMPDIR", "LANG")) {
            if (inherited.get(name) != null) {
                environment.put(name, inherited.get(name));
            }
        }
        environment.put("POSTGRES_USER", "compose-contract");
        environment.put("POSTGRES_PASSWORD", "compose-contract");
        environment.put("APP_ADMIN_USER", "compose-contract");
        environment.put("APP_ADMIN_PASSWORD", "compose-contract");
        environment.put("APP_INTERNAL_SERVICE_NAME", "compose-contract");
        environment.put("APP_INTERNAL_SERVICE_TOKEN", "compose-contract");
        environment.put("METABASE_ADMIN_EMAIL", "compose-contract@example.invalid");
        environment.put("METABASE_ADMIN_PASSWORD", "compose-contract");
        environment.putAll(variables);
    }
}
