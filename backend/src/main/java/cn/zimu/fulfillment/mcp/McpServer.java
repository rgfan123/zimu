package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 轻量 MCP Server：JSON-RPC 2.0 over stdio（每行一个 JSON 消息），一期实现
 * initialize / notifications/initialized / ping / tools/list / tools/call。
 *
 * <p>stdout 专用于协议帧，进程内任何其他输出都会破坏协议——以 MCP 模式启动时
 * 必须把应用日志重定向到文件（见 {@link McpServerRunner}）。业务失败返回
 * {@code isError=true} 的工具结果（携带稳定业务码），不向客户端暴露配置或凭据。
 * 非 Spring bean：由 {@link McpServerRunner} 与测试用显式流构造。
 */
public class McpServer {

    static final String PROTOCOL_VERSION = "2025-03-26";
    static final String SERVER_NAME = "fulfillment-hub-mcp";
    static final String SERVER_VERSION = "0.1.0";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    private final BufferedReader in;
    private final PrintWriter out;
    private final McpToolRegistry registry;
    private final McpAgentIdentity identity;
    private final ObjectMapper mapper;

    public McpServer(
            InputStream in, OutputStream out, McpToolRegistry registry, McpAgentIdentity identity, ObjectMapper mapper) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = new PrintWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        this.registry = registry;
        this.identity = identity;
        this.mapper = mapper;
    }

    /** 阻塞处理 stdin 直到 EOF。 */
    public void run() {
        String line;
        while (true) {
            try {
                line = in.readLine();
            } catch (IOException ex) {
                return;
            }
            if (line == null) {
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            handleLine(line);
        }
    }

    private void handleLine(String line) {
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            writeError(PARSE_ERROR, "Parse error", null);
            return;
        }
        if (message == null || !message.isObject() || !message.has("method") || !message.get("method").isTextual()) {
            writeError(INVALID_REQUEST, "Invalid Request", id(message));
            return;
        }
        String method = message.get("method").asText();
        JsonNode id = id(message);
        JsonNode params = message.get("params");
        if (id == null || id.isNull()) {
            handleNotification(method);
            return;
        }
        switch (method) {
            case "initialize" -> writeResult(initializeResult(), id);
            case "ping" -> writeResult(mapper.createObjectNode(), id);
            case "tools/list" -> writeResult(toolsList(), id);
            case "tools/call" -> handleToolCall(params, id);
            case "shutdown" -> writeResult(mapper.createObjectNode(), id);
            default -> writeError(METHOD_NOT_FOUND, "Method not found: " + method, id);
        }
    }

    private void handleNotification(String method) {
        switch (method) {
            case "notifications/initialized", "notifications/cancelled", "shutdown" -> {
                // 无响应；shutdown 后由客户端关闭 stdin 触发 EOF 退出
            }
            default -> {
                // 未知通知同样无需响应
            }
        }
    }

    private void handleToolCall(JsonNode params, JsonNode id) {
        if (params == null || !params.isObject() || !params.has("name") || !params.get("name").isTextual()) {
            writeError(INVALID_PARAMS, "Invalid params: tools/call requires name", id);
            return;
        }
        String name = params.get("name").asText();
        McpTool tool = registry.find(name).orElse(null);
        if (tool == null) {
            writeError(INVALID_PARAMS, "Unknown tool: " + name, id);
            return;
        }
        // 08 决策：stdio 面一期收紧为只读——外部客户端共用全局 identity、无 per-agent 权限，
        // 写工具与只读接口不共存（tools/list 也不暴露），调用写工具按无效请求拒绝。
        // 拒绝先于身份/幂等处理：只读接口上写工具不存在，认证语义（MCP_AUTH_REQUIRED）
        // 只对暴露出的只读工具生效。
        if (!tool.readOnly() || !tool.externallyDiscoverable()) {
            writeError(INVALID_PARAMS, "Tool is read-only restricted: " + name, id);
            return;
        }
        JsonNode arguments = params.get("arguments");
        Map<String, Object> args = arguments == null || arguments.isNull()
                ? Map.of()
                : mapper.convertValue(arguments, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        McpRequestContext context = identity.newContext();
        ObjectNode result = mapper.createObjectNode();
        try {
            JsonNode payload = tool.invoke(context, args);
            result.put("isError", false);
            content(result, payload.toString());
        } catch (BusinessException ex) {
            result.put("isError", true);
            ObjectNode error = mapper.createObjectNode();
            error.put("code", ex.getBusinessCode());
            error.put("http_status", ex.getHttpStatus());
            error.put("message", ex.getMessage());
            content(result, error.toString());
        } catch (RuntimeException ex) {
            result.put("isError", true);
            ObjectNode error = mapper.createObjectNode();
            error.put("code", "MCP_INTERNAL_ERROR");
            error.put("message", "内部错误，请联系运维");
            content(result, error.toString());
        }
        writeResult(result, id);
    }

    private void content(ObjectNode result, String text) {
        ArrayNode content = result.putArray("content");
        ObjectNode textItem = content.addObject();
        textItem.put("type", "text");
        textItem.put("text", text);
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        // 08 决策：stdio 面只暴露只读工具（readOnly=true），写工具不外露
        for (McpTool tool : registry.all()) {
            if (!tool.readOnly() || !tool.externallyDiscoverable()) {
                continue;
            }
            ObjectNode item = tools.addObject();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private static JsonNode id(JsonNode message) {
        return message == null ? null : message.get("id");
    }

    private void writeResult(JsonNode result, JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        writeLine(response);
    }

    private void writeError(int code, String message, JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        writeLine(response);
    }

    private void writeLine(JsonNode message) {
        out.println(message.toString());
        out.flush();
    }
}
