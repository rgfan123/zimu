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
 *
 * <p>协议分发核心是 {@link #handleRequest(String)}（一帧文本进、一帧响应或
 * {@code null} 出），stdio（{@link #run()}）与 HTTP/SSE 传输面
 * （{@code cn.zimu.fulfillment.mcp.http}，见 {@code McpHttpJsonRpcHandler}）
 * 共用同一份分发逻辑，不重复实现 JSON-RPC 路由，行为不因传输面分叉。
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

    /**
     * 无流构造：仅供 HTTP/SSE 传输面（{@code cn.zimu.fulfillment.mcp.http}）直接调用
     * {@link #handleRequest(String)}，不支持也不应调用 {@link #run()}——HTTP 传输自行处理
     * 请求体读取与响应写回（含 202/401/SSE 帧封装），不经 stdio 逐行帧协议。协议分发逻辑与
     * stdio 完全共用，行为（含 08 决策的只读收紧）不会因传输面而分叉。
     */
    public McpServer(McpToolRegistry registry, McpAgentIdentity identity, ObjectMapper mapper) {
        this.in = null;
        this.out = null;
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
        JsonNode response = handleRequest(line);
        if (response != null) {
            writeLine(response);
        }
    }

    /**
     * 处理一帧原始 JSON-RPC 报文，返回响应帧；通知类消息（无 {@code id}）返回 {@code null}，
     * 调用方不应回写任何响应体（stdio 静默跳过，HTTP 传输按各自协议回 202/无内容）。
     * stdio（{@link #handleLine}）与 HTTP 传输共用同一分发逻辑，行为不因传输面分叉。
     */
    public JsonNode handleRequest(String rawJson) {
        JsonNode message;
        try {
            message = mapper.readTree(rawJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return errorResponse(PARSE_ERROR, "Parse error", null);
        }
        return handleMessage(message);
    }

    private JsonNode handleMessage(JsonNode message) {
        if (message == null || !message.isObject() || !message.has("method") || !message.get("method").isTextual()) {
            return errorResponse(INVALID_REQUEST, "Invalid Request", id(message));
        }
        String method = message.get("method").asText();
        JsonNode id = id(message);
        JsonNode params = message.get("params");
        if (id == null || id.isNull()) {
            handleNotification(method);
            return null;
        }
        return switch (method) {
            case "initialize" -> successResponse(initializeResult(), id);
            case "ping" -> successResponse(mapper.createObjectNode(), id);
            case "tools/list" -> successResponse(toolsList(), id);
            case "tools/call" -> toolCallResponse(params, id);
            case "shutdown" -> successResponse(mapper.createObjectNode(), id);
            default -> errorResponse(METHOD_NOT_FOUND, "Method not found: " + method, id);
        };
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

    private JsonNode toolCallResponse(JsonNode params, JsonNode id) {
        if (params == null || !params.isObject() || !params.has("name") || !params.get("name").isTextual()) {
            return errorResponse(INVALID_PARAMS, "Invalid params: tools/call requires name", id);
        }
        String name = params.get("name").asText();
        McpTool tool = registry.find(name).orElse(null);
        if (tool == null) {
            return errorResponse(INVALID_PARAMS, "Unknown tool: " + name, id);
        }
        // 08 决策：stdio 面一期收紧为只读——外部客户端共用全局 identity、无 per-agent 权限，
        // 写工具与只读接口不共存（tools/list 也不暴露），调用写工具按无效请求拒绝。
        // 拒绝先于身份/幂等处理：只读接口上写工具不存在，认证语义（MCP_AUTH_REQUIRED）
        // 只对暴露出的只读工具生效。HTTP 传输复用同一分发逻辑，写工具同样一律拒绝。
        if (!tool.readOnly() || !tool.externallyDiscoverable()) {
            return errorResponse(INVALID_PARAMS, "Tool is read-only restricted: " + name, id);
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
        return successResponse(result, id);
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

    private JsonNode successResponse(JsonNode result, JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private JsonNode errorResponse(int code, String message, JsonNode id) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private void writeLine(JsonNode message) {
        out.println(message.toString());
        out.flush();
    }
}
