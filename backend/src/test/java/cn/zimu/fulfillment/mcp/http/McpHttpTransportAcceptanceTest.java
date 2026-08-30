package cn.zimu.fulfillment.mcp.http;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpServer;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * MCP 外部 HTTP/SSE 传输面验收：{@code app.mcp.http.enabled=true} 且配置了
 * {@code app.mcp.http.token} 时的行为——鉴权、Streamable HTTP、老 SSE 兼容传输，逐一
 * 与直接调用 {@link McpServer#handleRequest(String)}（stdio 面使用的同一分发逻辑）比对，
 * 证明协议行为不因传输面分叉（08 决策的只读收紧同样在 HTTP 面生效）。
 *
 * <p>"token 未配置 → 端点不注册（404）"的场景由 {@link McpHttpTransportNotRegisteredTest}
 * 单独验证（不同配置需要独立的 Spring 容器）；纯逻辑分支穷举见
 * {@link McpHttpTransportConditionTest} 与 {@link McpHttpTokenAuthenticatorTest}。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.http.enabled=true",
            "app.mcp.http.token=" + McpHttpTransportAcceptanceTest.TOKEN,
            "app.mcp.protocol-modules=masterdata,write"
        })
class McpHttpTransportAcceptanceTest {

    static final String TOKEN = "mcp-http-acceptance-test-token-0123456789";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private ObjectMapper mapper;

    // ------------------------------------------------------------------
    // Streamable HTTP：鉴权
    // ------------------------------------------------------------------

    @Test
    void postWithoutAuthorizationHeaderIsRejected() {
        ResponseEntity<String> response = postToMcp(null, initializeRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postWithWrongTokenIsRejected() {
        ResponseEntity<String> response = postToMcp("Bearer wrong-token", initializeRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getStreamWithoutAuthorizationIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<String> response =
                http.exchange("/mcp", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    // Streamable HTTP：initialize / tools-list / tools-call 与 stdio 行为一致
    // ------------------------------------------------------------------

    @Test
    void initializeReturnsProtocolCapabilities() throws Exception {
        ResponseEntity<String> response = postToMcp(bearer(), initializeRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("result").get("serverInfo").get("name").asText())
                .isEqualTo("fulfillment-hub-mcp");
        assertThat(body.get("result").get("capabilities").get("tools").get("listChanged").asBoolean())
                .isFalse();
    }

    @Test
    void toolsListMatchesDirectStdioProtocolHandling() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}";

        ResponseEntity<String> httpResponse = postToMcp(bearer(), request);
        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode httpBody = mapper.readTree(httpResponse.getBody());

        JsonNode directBody = directStdioRpc(request);
        assertThat(httpBody).as("HTTP 传输面必须与 stdio 面返回完全相同的协议帧").isEqualTo(directBody);
        assertThat(httpBody.get("result").get("tools")).isNotEmpty();
    }

    @Test
    void toolCallResultMatchesDirectStdioProtocolHandling() throws Exception {
        // list_categories：公共主数据只读工具；Agent 工具元数据明确不进入外部协议面。
        String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"list_categories\",\"arguments\":{}}}";

        ResponseEntity<String> httpResponse = postToMcp(bearer(), request);
        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode httpBody = mapper.readTree(httpResponse.getBody());

        JsonNode directBody = directStdioRpc(request);
        assertThat(httpBody).as("同一工具调用，HTTP 与 stdio 面的结果帧必须逐字段相同").isEqualTo(directBody);
        assertThat(httpBody.get("result").get("isError").asBoolean()).isFalse();
    }

    @Test
    void writeToolCallIsRejectedOnHttpTransportJustLikeStdio() throws Exception {
        // 08 决策：只读收紧是协议分发层的不变式，不因传输面分叉——HTTP 面同样拒绝写工具。
        String request = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"reinterpret_submission\",\"arguments\":{}}}";

        ResponseEntity<String> httpResponse = postToMcp(bearer(), request);
        // JSON-RPC 错误仍是协议层结果，HTTP 状态码是 200（鉴权已通过，请求已被正确处理）。
        assertThat(httpResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(httpResponse.getBody());
        assertThat(body.get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(body.get("error").get("message").asText()).contains("read-only");

        JsonNode directBody = directStdioRpc(request);
        assertThat(body).isEqualTo(directBody);
    }

    @Test
    void notificationWithoutIdReturnsAcceptedAndNoBody() {
        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        ResponseEntity<String> response = postToMcp(bearer(), notification);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNullOrEmpty();
    }

    // ------------------------------------------------------------------
    // 老 SSE 传输兼容：GET 建流 + POST 发消息，响应经流回推
    // ------------------------------------------------------------------

    @Test
    void legacySseHandshakeDeliversToolResponseOnTheStream() throws Exception {
        // 用裸 Socket + 手写 HTTP/1.1 请求，不用 java.net.http.HttpClient：在这套工具链的
        // JDK 上实测 HttpClient 读流式/chunked 响应体会挂起拿不到任何字节（用 curl 直连同一
        // 端点能立刻收到事件，证明是客户端库的问题，不是服务端）。Socket 是最朴素、最不可能
        // 有这种边角问题的 I/O 路径。
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            sendRawGet(socket, "/mcp/sse", bearer());
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(readChunkedHttpBody(socket, 200, "text/event-stream"), StandardCharsets.UTF_8));

            String endpointData = readEventData(reader, "endpoint");
            Matcher matcher = Pattern.compile("sessionId=([\\w-]+)").matcher(endpointData);
            assertThat(matcher.find()).as("endpoint 事件必须携带 sessionId: %s", endpointData).isTrue();
            String sessionId = matcher.group(1);

            String toolsListRequest = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\",\"params\":{}}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", bearer());
            ResponseEntity<String> postResponse = http.exchange(
                    "/mcp/messages?sessionId=" + sessionId,
                    HttpMethod.POST,
                    new HttpEntity<>(toolsListRequest, headers),
                    String.class);
            assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            String messageData = readEventData(reader, "message");
            JsonNode streamed = mapper.readTree(messageData);
            JsonNode direct = directStdioRpc(toolsListRequest);
            assertThat(streamed).as("老 SSE 传输回推的响应帧必须与 stdio 一致").isEqualTo(direct);
        }
    }

    @Test
    void messagesEndpointRejectsUnknownSessionId() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", bearer());
        ResponseEntity<String> response = http.exchange(
                "/mcp/messages?sessionId=does-not-exist",
                HttpMethod.POST,
                new HttpEntity<>(initializeRequest(), headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 协议级助手
    // ------------------------------------------------------------------

    private String bearer() {
        return "Bearer " + TOKEN;
    }

    private ResponseEntity<String> postToMcp(String authorization, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        return http.exchange("/mcp", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 直接调用 stdio 面同一个 {@link McpServer}，作为 HTTP 传输面结果比对的基准。 */
    private JsonNode directStdioRpc(String rawJson) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((rawJson + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity(""),
                mapper);
        server.run();
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(1);
        return mapper.readTree(lines.getFirst());
    }

    private static String initializeRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    }

    /**
     * 逐行读取 SSE 流，直到匹配到指定 event 名的那条 data 行，返回其内容。Spring 的
     * SseEmitter 写 {@code event:xxx}/{@code data:xxx}（冒号后不带空格，实测确认），
     * 所以按前缀匹配再 strip，不写死"冒号后一个空格"这种假设。
     *
     * <p>用独立线程 + {@link Future#get(long, TimeUnit)} 给这一次读取单独限时，读不到就
     * 抛清晰的 TimeoutException，不会无限挂起拖垮整个测试进程。
     */
    private static String readEventData(BufferedReader reader, String expectedEvent) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(() -> {
                String line;
                boolean sawEvent = false;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:") && line.substring("event:".length()).strip().equals(expectedEvent)) {
                        sawEvent = true;
                        continue;
                    }
                    if (sawEvent && line.startsWith("data:")) {
                        return line.substring("data:".length()).strip();
                    }
                }
                throw new AssertionError("SSE 流在读到 EOF 前未出现 event: " + expectedEvent);
            });
            return future.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 手写一条最小 HTTP/1.1 GET 请求（含 Authorization），不经任何 HTTP 客户端库。 */
    private static void sendRawGet(Socket socket, String path, String authorization) throws IOException {
        OutputStream out = socket.getOutputStream();
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Authorization: " + authorization + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /**
     * 读状态行 + 响应头，断言状态码与 content-type 前缀，再把剩下的 body 包成一个按
     * chunked 传输编码解码的 {@link InputStream}（Tomcat 对流式响应必用 chunked，
     * 没有 Content-Length）。只覆盖测试需要的最小 chunked 解析，不是通用实现。
     */
    private static InputStream readChunkedHttpBody(Socket socket, int expectedStatus, String expectedContentTypePrefix)
            throws IOException {
        InputStream raw = socket.getInputStream();
        String statusLine = readAsciiLine(raw);
        assertThat(statusLine).as("HTTP 状态行").contains(" " + expectedStatus + " ");
        boolean chunked = false;
        boolean contentTypeOk = false;
        String headerLine;
        while (!(headerLine = readAsciiLine(raw)).isEmpty()) {
            String lower = headerLine.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
            if (lower.startsWith("content-type:") && lower.contains(expectedContentTypePrefix)) {
                contentTypeOk = true;
            }
        }
        assertThat(chunked).as("流式响应必须是 chunked 编码，实测头: 见上").isTrue();
        assertThat(contentTypeOk).as("Content-Type 必须以 %s 开头", expectedContentTypePrefix).isTrue();
        return new ChunkedDecodingInputStream(raw);
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    /**
     * 最小可用的 HTTP/1.1 chunked 传输编码解码器：只服务本测试的读取需求。
     *
     * <p>关键点：显式覆写批量 {@link #read(byte[], int, int)}，把单次调用严格限制在
     * "当前已知这一块还剩多少字节"以内，绝不在同一次调用里提前去读下一块的 size 行。
     * 若只覆写单字节 {@link #read()}，{@link InputStream} 默认的批量读实现会在一次调用里
     * 反复调它直到填满调用方要的整段缓冲区——而 {@code BufferedReader}/{@code StreamDecoder}
     * 一次通常要的量远大于一个 SSE 事件的字节数，会导致这次调用在读完当前块后"贪心"地去
     * 读下一块的 size 行，而下一块（这里是老 SSE 传输经 POST 触发的第二个事件）此时还没被
     * 服务端发出——读者在服务端真正写下一块之前就卡进了阻塞读，形成死等。显式覆写批量读、
     * 每次只读到当前块末尾为止，把"要不要跨块"的决定权交还给调用方的下一次调用。
     */
    private static final class ChunkedDecodingInputStream extends InputStream {
        private final InputStream in;
        private int remainingInChunk;
        private boolean finished;

        ChunkedDecodingInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n <= 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (finished) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            if (remainingInChunk == 0 && !startNextChunk()) {
                return -1;
            }
            int toRead = Math.min(len, remainingInChunk);
            int n = in.read(b, off, toRead);
            if (n < 0) {
                finished = true;
                return -1;
            }
            remainingInChunk -= n;
            if (remainingInChunk == 0) {
                // 每个 chunk 数据后跟一个 CRLF，读掉丢弃。
                in.read();
                in.read();
            }
            return n;
        }

        /** 读下一块的 size 行；size=0（终止块）返回 false，其余情况置好 remainingInChunk 返回 true。 */
        private boolean startNextChunk() throws IOException {
            String sizeLine = readAsciiLine(in);
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).strip();
            if (hex.isEmpty()) {
                finished = true;
                return false;
            }
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
                finished = true;
                return false;
            }
            remainingInChunk = size;
            return true;
        }
    }
}
