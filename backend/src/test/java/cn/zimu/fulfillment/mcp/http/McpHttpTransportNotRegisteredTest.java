package cn.zimu.fulfillment.mcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code app.mcp.http.enabled=true} 但未配置 {@code app.mcp.http.token} 时：MCP HTTP/SSE
 * 端点必须直接不注册（404），而不是放行到无鉴权状态。这是与
 * {@link McpHttpTransportAcceptanceTest}（token 已配置）互补的独立场景——不同的属性组合
 * 需要独立的 Spring 容器才能验证 {@link McpHttpTransportCondition} 在真实启动流程里
 * 确实生效（穷举其纯逻辑分支见 {@link McpHttpTransportConditionTest}）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.http.enabled=true"
            // app.mcp.http.token 故意不配置
        })
class McpHttpTransportNotRegisteredTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Test
    void streamableHttpPostEndpointDoesNotExist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(
                "/mcp",
                HttpMethod.POST,
                new HttpEntity<>("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamableHttpGetEndpointDoesNotExist() {
        ResponseEntity<String> response = http.getForEntity("/mcp", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void legacySseEndpointsDoNotExist() {
        assertThat(http.getForEntity("/mcp/sse", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = http.exchange(
                "/mcp/messages?sessionId=whatever",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
