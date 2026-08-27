package cn.zimu.fulfillment.mcp.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link McpHttpTransportCondition} 的纯单元测试（不起 Spring 容器）：穷举
 * {@code app.mcp.http.enabled} × {@code app.mcp.http.token} 的四种组合，断言只有
 * "开关开 + token 非空" 才注册端点——尤其是"开关开了但忘配 token"必须仍然不注册，
 * 这是 MCP HTTP 传输面唯一的门禁开关，不能被拼错配置悄悄绕过。
 */
class McpHttpTransportConditionTest {

    private final McpHttpTransportCondition condition = new McpHttpTransportCondition();

    @Test
    void disabledByDefaultEvenIfTokenConfigured() {
        assertThat(matches(false, "some-token")).isFalse();
    }

    @Test
    void enabledButTokenMissingDoesNotRegister() {
        assertThat(matches(true, "")).isFalse();
        assertThat(matches(true, null)).isFalse();
        assertThat(matches(true, "   ")).isFalse();
    }

    @Test
    void disabledAndTokenMissingDoesNotRegister() {
        assertThat(matches(false, "")).isFalse();
    }

    @Test
    void enabledWithTokenRegisters() {
        assertThat(matches(true, "a-real-token")).isTrue();
    }

    private boolean matches(boolean enabled, String token) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.mcp.http.enabled", String.valueOf(enabled));
        if (token != null) {
            environment.setProperty("app.mcp.http.token", token);
        }
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return condition.matches(context, null);
    }
}
