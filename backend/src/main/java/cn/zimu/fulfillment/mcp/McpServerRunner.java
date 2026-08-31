package cn.zimu.fulfillment.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MCP stdio 进程入口：{@code app.mcp.enabled=true}（环境变量 {@code MCP_ENABLED=true}）时
 * 从 System.in 读取协议帧并把响应写入 System.out，进程随 EOF 退出。
 *
 * <p>stdout 是协议信道：以 MCP 模式启动时应用日志必须重定向到文件
 * （例如启动命令携带 {@code LOGGING_FILE_NAME=logs/mcp.log}），否则日志会破坏协议帧。
 * Agent 身份由启动方通过环境变量注入（{@code MCP_AGENT_IDENTITY}），服务端启动时捕获，
 * 工具参数无法伪造；本入口不打印任何凭据或配置。
 */
@Component
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpServerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpServerRunner.class);

    private final McpToolRegistry registry;
    private final McpAgentIdentity identity;
    private final ObjectMapper mapper;
    private final boolean privileged;
    private final String privilegedIdentity;

    public McpServerRunner(
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${app.mcp.stdio-privileged:false}")
                    boolean privileged,
            @org.springframework.beans.factory.annotation.Value("${app.mcp.agent-identity:}")
                    String privilegedIdentity) {
        this.registry = registry;
        this.identity = identity;
        this.mapper = mapper;
        this.privileged = privileged;
        this.privilegedIdentity = privilegedIdentity;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (privileged && (privilegedIdentity == null || privilegedIdentity.isBlank())) {
            // 特权面（issue-181）：写调用必须可归因。开了特权却不报身份 = 配置错误，
            // fail-fast 而不是静默降级——降级会让启动方以为拿到了全权限。
            throw new IllegalStateException(
                    "MCP_STDIO_PRIVILEGED=true 时必须设置 MCP_AGENT_IDENTITY（审计归因身份）");
        }
        McpServer server = new McpServer(System.in, System.out, registry, identity, mapper, privileged);
        log.info(privileged
                ? "MCP stdio server started (PRIVILEGED: Agent 全工具面，含写；identity=" + privilegedIdentity + ")"
                : "MCP stdio server started");
        server.run();
    }
}
