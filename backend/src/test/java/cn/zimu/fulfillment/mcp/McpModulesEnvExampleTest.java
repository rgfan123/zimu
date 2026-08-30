package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 环境变量样例文件必须写明 MCP 模块开放清单。
 *
 * <p>空值语义反转成 fail-safe 之后，「照 .env.example 拉起的环境」拿到的是零工具而不是全部工具；
 * 这本身是安全的，但样例文件必须同时说清「留空 = 不开放任何 MCP 工具」并给出一个真能用的示例值，
 * 否则新环境只会得到一个查不出原因的哑 MCP。这条约束靠人肉 review 记不住，用例钉住它。
 *
 * <p><b>期望值为什么从生产值改成开发值</b>：本用例原先断言样例值逐字等于生产的只读三模块
 * {@code masterdata,inventory,orders-read}。那个期望在「样例值 = 抄生产」的口径下是对的，但它
 * 让样例文件变成一个跑不起来的配置——本仓迁移自带的 7 个种子 Agent，白名单跨到了 procurement /
 * messages / orders / control / write / followup 六个模块，而
 * {@code AgentToolBindingFactory#bind} 对白名单里找不到的工具是直接抛 IllegalArgumentException
 * 不是跳过，于是照样例拉起的开发/演示环境里这些 Agent 一跑就炸。样例文件的用途是「照着拉一个能用
 * 的环境」，不是「留一份生产配置的副本」，所以期望值改为开发口径的模块并集；生产不受影响，它在
 * override compose 里显式配三模块，override 覆盖 base compose。
 *
 * <p>改成开发值之后，「生产用的是更窄的只读三模块」这件事不能就此失传，所以它从「断言值」降级为
 * 「断言注释里必须写着」——事实仍然被钉住，只是钉在解释它的地方。样例值与种子 Agent 的实际配套关系
 * 由 {@link SeedAgentToolModuleGateTest} 逐工具核对。
 *
 * <p>Surefire 工作目录为 backend/，样例文件在仓库根；按候选路径都找不到时跳过而不是失败
 * （与 {@code AgentContextDocTest} 同一处理方式）。
 */
class McpModulesEnvExampleTest {

    /** 样例值口径：能让本仓 7 个种子 Agent 全部绑定成功的最小模块并集（开发/演示环境）。 */
    private static final String DEVELOPMENT_MODULES =
            "masterdata,inventory,orders,procurement,messages,followup,control,write";

    /** 生产当前在 override compose 里的显式配置（只读三模块）；样例注释必须写明它。 */
    private static final String PRODUCTION_MODULES = "masterdata,inventory,orders-read";

    @Test
    void envExampleDeclaresDevelopmentValueAndExplainsFailSafeAndProduction() throws IOException {
        Path envExample = resolveEnvExample();
        Assumptions.assumeTrue(envExample != null, "未找到仓库根 .env.example，跳过");

        List<String> lines = Files.readAllLines(envExample, StandardCharsets.UTF_8);
        int index = indexOfSetting(lines);
        assertThat(index)
                .as(".env.example 必须包含 MCP_MODULES 配置项")
                .isNotNegative();

        assertThat(lines.get(index).strip())
                .as("样例值是开发口径：照它拉起的环境必须能跑通本仓自带的全部种子 Agent")
                .isEqualTo("MCP_MODULES=" + DEVELOPMENT_MODULES);

        String comment = commentBlockAbove(lines, index);
        assertThat(comment)
                .as("必须注明留空的语义，否则运维只会看到一个不解释自己的哑 MCP")
                .contains("留空 = 不开放任何 MCP 工具");
        assertThat(comment)
                .as("必须写明样例值是开发口径、生产在 override 里另配更窄的只读三模块，"
                        + "否则读的人会把这一行当成生产配置照抄")
                .contains(PRODUCTION_MODULES);
        assertThat(comment)
                .as("followup/messages 含 PII、write 含真实写动作，样例值把它们打开了就必须单独提示")
                .contains("PII")
                .contains("写动作");
    }

    private static int indexOfSetting(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("MCP_MODULES=")) {
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
}
