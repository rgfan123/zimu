package cn.zimu.fulfillment.mcp.http;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * MCP 外部 HTTP/SSE 传输面的注册条件：{@code app.mcp.http.enabled=true}
 * （env {@code MCP_HTTP_ENABLED}）且 {@code app.mcp.http.token}（env {@code MCP_HTTP_TOKEN}）
 * 非空——两者都满足端点才注册。
 *
 * <p>token 缺失时端点直接不注册（{@code tools/list}/{@code tools/call} 等一律 404），
 * 而不是放行——避免「开关开了但忘配 token」变成没有任何门禁的公网入口。开关默认关闭
 * （{@code app.mcp.http.enabled} 默认 false），不主动扩大攻击面。
 */
public class McpHttpTransportCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        boolean enabled = env.getProperty("app.mcp.http.enabled", Boolean.class, false);
        String token = env.getProperty("app.mcp.http.token", "");
        return enabled && token != null && !token.isBlank();
    }
}
