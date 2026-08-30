package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MCP 只读响应的编译期评审门槛：对外工具必须逐字段投影，不得把服务层 DTO
 * 交给 Jackson 整体序列化。DTO 日后新增字段时，必须先显式修改投影和字段快照。
 */
class McpReadProjectionBoundaryTest {

    private static final List<String> READ_TOOL_SOURCES = List.of(
            "McpReadTools.java",
            "McpDomainReadTools.java",
            "McpOrdersReadTools.java",
            "McpControlReadTools.java",
            "McpProjectionSupport.java");

    @Test
    void readToolsNeverSerializeServiceDtosWholesale() throws IOException {
        Path sourceRoot = Path.of("src/main/java/cn/zimu/fulfillment/mcp");
        for (String sourceName : READ_TOOL_SOURCES) {
            String source = Files.readString(sourceRoot.resolve(sourceName));
            assertThat(source)
                    .as(sourceName + " 不得使用 valueToTree 透传服务层对象")
                    .doesNotContain("valueToTree(");
        }
    }
}
