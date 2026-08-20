package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手写 RFC6455 WebSocket 测试服务器（测试专用，不依赖 testcontainers / 外部网络）。
 *
 * <p>行为：接受任意数量顺序连接（每次一个当前连接）；对 {@code aibot_subscribe} 帧按可配置
 * errcode 应答；对 {@code ping} 帧默认回 {@code pong}（可关闭以模拟僵死）；对三步素材上传
 * （init/chunk/finish）按可配置 errcode 自动应答并重组分片（重复同片幂等）；所有收到的文本帧
 * 进入队列供断言。服务器→客户端帧不掩码，客户端→服务器帧自动解掩码。
 */
final class Rfc6455TestServer implements AutoCloseable {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern CMD_PATTERN = Pattern.compile("\"cmd\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REQ_ID_PATTERN = Pattern.compile("\"req_id\"\\s*:\\s*\"([^\"]*)\"");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicInteger subscribeErrcode = new AtomicInteger(0);
    private final AtomicInteger sendMessageErrcode = new AtomicInteger(0);
    private final AtomicBoolean autoPong = new AtomicBoolean(true);
    private final AtomicBoolean autoSendMessageAck = new AtomicBoolean(true);
    private final AtomicBoolean autoUploadAck = new AtomicBoolean(true);
    private final AtomicInteger uploadInitErrcode = new AtomicInteger(0);
    private final AtomicInteger uploadChunkErrcode = new AtomicInteger(0);
    private final AtomicInteger uploadFinishErrcode = new AtomicInteger(0);
    private final AtomicBoolean dropNextInitAck = new AtomicBoolean(false);
    private final AtomicBoolean dropNextChunkAck = new AtomicBoolean(false);
    private final AtomicBoolean dropNextFinishAck = new AtomicBoolean(false);
    private final AtomicBoolean omitInitUploadId = new AtomicBoolean(false);
    private final AtomicBoolean omitFinishMediaId = new AtomicBoolean(false);
    private final AtomicReference<String> finishTypeOverride = new AtomicReference<>();
    private final AtomicReference<JsonNode> finishCreatedAtOverride = new AtomicReference<>();
    private final AtomicInteger disconnectAfterChunkIndex = new AtomicInteger(-1);
    private final AtomicInteger disconnectEveryChunkIndex = new AtomicInteger(-1);
    private final AtomicInteger chunkAckDelayMillis = new AtomicInteger(0);
    private final AtomicReference<Socket> currentConnection = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> receivedTextFrames = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final Map<String, ServerUploadSession> uploadSessions = new ConcurrentHashMap<>();
    private final AtomicInteger uploadIdSequence = new AtomicInteger();

    Rfc6455TestServer() throws IOException {
        serverSocket = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(this::acceptLoop, "rfc6455-accept");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    String wsUrl() {
        return "ws://127.0.0.1:" + port() + "/";
    }

    /** 订阅应答 errcode；0 表示成功，非 0 触发客户端订阅失败计数。 */
    void subscribeErrcode(int errcode) {
        subscribeErrcode.set(errcode);
    }

    /** 主动消息应答 errcode；默认成功。 */
    void sendMessageErrcode(int errcode) {
        sendMessageErrcode.set(errcode);
    }

    void autoSendMessageAck(boolean enabled) {
        autoSendMessageAck.set(enabled);
    }

    /** 是否对 ping 自动回 pong；关闭后服务器保持静默以模拟僵死连接。 */
    void autoPong(boolean enabled) {
        autoPong.set(enabled);
    }

    // ---- 三步素材上传控制 ----

    /** init 应答 errcode；0 成功（返回 upload_id），非 0 触发客户端 fail closed。 */
    void uploadInitErrcode(int errcode) {
        uploadInitErrcode.set(errcode);
    }

    void uploadChunkErrcode(int errcode) {
        uploadChunkErrcode.set(errcode);
    }

    void uploadFinishErrcode(int errcode) {
        uploadFinishErrcode.set(errcode);
    }

    /** 总开关：关闭后对 init/chunk/finish 一律不自动应答（模拟 ack 缺失）。 */
    void autoUploadAck(boolean enabled) {
        autoUploadAck.set(enabled);
    }

    /** 一次性丢弃下一次 init/chunk/finish 的应答（ack 丢失模拟）。 */
    void dropNextInitAck() {
        dropNextInitAck.set(true);
    }

    void dropNextChunkAck() {
        dropNextChunkAck.set(true);
    }

    void dropNextFinishAck() {
        dropNextFinishAck.set(true);
    }

    /** init 成功应答省略 body.upload_id（协议字段缺失模拟）。 */
    void omitInitUploadId(boolean enabled) {
        omitInitUploadId.set(enabled);
    }

    /** finish 成功应答省略 body.media_id（协议字段缺失模拟）。 */
    void omitFinishMediaId(boolean enabled) {
        omitFinishMediaId.set(enabled);
    }

    /** finish 成功应答 body.type 覆盖；null 表示回显 init 的 type（默认）。 */
    void uploadFinishType(String type) {
        finishTypeOverride.set(type);
    }

    /** finish 成功应答 body.created_at 覆盖（任意 JsonNode，可注入 0/负数/文本）；null 表示默认 Unix 秒。 */
    void uploadFinishCreatedAt(JsonNode createdAt) {
        finishCreatedAtOverride.set(createdAt);
    }

    /** 收到指定 chunk_index 后立即断开连接且不应答（一次性）；模拟该片 ack 丢失断线。 */
    void disconnectAfterChunkIndex(int chunkIndex) {
        disconnectAfterChunkIndex.set(chunkIndex);
    }

    /** 每次收到指定 chunk_index 都断开连接且不应答（重复触发，用于耗尽重试预算/会话超期）。 */
    void disconnectEveryChunkIndex(int chunkIndex) {
        disconnectEveryChunkIndex.set(chunkIndex);
    }

    /** chunk 应答延迟（毫秒），用于 ack 超时/帧时序测试。 */
    void chunkAckDelayMillis(int delayMillis) {
        chunkAckDelayMillis.set(delayMillis);
    }

    /** 轮询快照直到出现指定 cmd 的上传帧（不消费队列）。 */
    String awaitUploadFrame(String cmd, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            String frame = uploadFrames(cmd).stream().findFirst().orElse(null);
            if (frame != null) {
                return frame;
            }
            Thread.sleep(20);
        }
        return null;
    }

    /** 当前收到的指定 cmd 上传帧列表（按到达顺序）。 */
    List<String> uploadFrames(String cmd) {
        return receivedTextFrames.stream()
                .filter(frame -> frame.contains("\"cmd\":\"" + cmd + "\""))
                .toList();
    }

    /** 已创建的上传会话 id（按创建顺序）。 */
    List<String> uploadSessionIds() {
        return uploadSessions.values().stream().map(session -> session.uploadId).toList();
    }

    /** 服务端按 chunk_index 重组后的文件字节；分片未齐全时返回 null。 */
    byte[] assembledBytes(String uploadId) {
        ServerUploadSession session = uploadSessions.get(uploadId);
        if (session == null || session.receivedChunks.get() != session.totalChunks) {
            return null;
        }
        byte[] assembled = new byte[(int) session.totalSize];
        int offset = 0;
        for (byte[] chunk : session.chunks) {
            System.arraycopy(chunk, 0, assembled, offset, chunk.length);
            offset += chunk.length;
        }
        return assembled;
    }

    /** 服务端收到的某会话各片到达顺序（chunk_index 序列，含重复）。 */
    List<Integer> chunkIndexOrder(String uploadId) {
        ServerUploadSession session = uploadSessions.get(uploadId);
        return session == null ? List.of() : List.copyOf(session.chunkOrder);
    }

    int connectionCount() {
        return connectionCount.get();
    }

    /** 所有连接收到的文本帧（含订阅/心跳/应答），按到达顺序，追加式快照。 */
    List<String> textFrames() {
        return List.copyOf(receivedTextFrames);
    }

    /** 轮询快照直到出现指定 cmd 的帧（不消费队列）。 */
    String awaitFrame(String cmd, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String expected = "\"cmd\"\s*:\s*\"" + cmd + "\"";
        while (System.nanoTime() < deadline) {
            for (String frame : receivedTextFrames) {
                if (frame.matches("(?s).*" + expected + ".*")) {
                    return frame;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    List<Throwable> failures() {
        return failures;
    }

    /** 服务端主动推送一帧文本（如 disconnected_event / 消息回调）。 */
    void sendText(String payload) throws IOException {
        Socket socket = currentConnection.get();
        if (socket == null || socket.isClosed()) {
            throw new IOException("no current connection");
        }
        sendFrame(socket.getOutputStream(), new Frame(0x1, payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** 服务端以 RFC6455 continuation frames 发送一条分片文本消息。 */
    void sendFragmentedText(String... fragments) throws IOException {
        if (fragments.length < 2) {
            throw new IllegalArgumentException("fragmented text requires at least two fragments");
        }
        Socket socket = currentConnection.get();
        if (socket == null || socket.isClosed()) {
            throw new IOException("no current connection");
        }
        OutputStream out = socket.getOutputStream();
        for (int index = 0; index < fragments.length; index++) {
            int opcode = index == 0 ? 0x1 : 0x0;
            boolean last = index == fragments.length - 1;
            sendFrame(out, new Frame(opcode, fragments[index].getBytes(StandardCharsets.UTF_8)), last);
        }
    }

    void sendAck(String requestId, int errcode) throws IOException {
        sendText("{\"headers\":{\"req_id\":\""
                + requestId
                + "\"},\"errcode\":"
                + errcode
                + ",\"errmsg\":\""
                + (errcode == 0 ? "ok" : "rejected")
                + "\"}");
    }

    /** 服务端发起关闭握手并断开。 */
    void closeConnection() throws IOException {
        Socket socket = currentConnection.get();
        if (socket == null || socket.isClosed()) {
            return;
        }
        sendFrame(socket.getOutputStream(), new Frame(0x8, new byte[] {0x03, (byte) 0xE8}));
        socket.close();
    }

    /** 直接断开 TCP（模拟网络中断，客户端应走重连）。 */
    void abortConnection() throws IOException {
        Socket socket = currentConnection.get();
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // closing
        }
        Socket socket = currentConnection.getAndSet(null);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                connectionCount.incrementAndGet();
                Socket previous = currentConnection.getAndSet(socket);
                Thread handler = new Thread(() -> handleConnection(socket), "rfc6455-conn");
                handler.setDaemon(true);
                handler.start();
                if (previous != null && previous != socket) {
                    try {
                        previous.close();
                    } catch (IOException ignored) {
                        // stale connection
                    }
                }
            } catch (IOException ex) {
                if (running.get()) {
                    failures.add(ex);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket ignored = socket) {
            handshake(socket);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            // JDK HttpClient 会把大消息拆成 16KB 分片帧：必须按 FIN 位重组后再处理
            StringBuilder textFragments = new StringBuilder();
            boolean inFragment = false;
            while (running.get()) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    break;
                }
                if (frame.opcode == 0x8) {
                    // 客户端关闭：回显关闭帧并结束连接
                    sendFrame(out, new Frame(0x8, frame.payload));
                    return;
                }
                if (frame.opcode == 0x9) {
                    if (autoPong.get()) {
                        sendFrame(out, new Frame(0xA, frame.payload));
                    }
                    continue;
                }
                if (frame.opcode == 0xA) {
                    continue;
                }
                if (frame.opcode == 0x1 || frame.opcode == 0x0) {
                    textFragments.append(new String(frame.payload, StandardCharsets.UTF_8));
                    inFragment = true;
                    if (frame.fin) {
                        String text = textFragments.toString();
                        textFragments.setLength(0);
                        inFragment = false;
                        receivedTextFrames.add(text);
                        handleTextFrame(out, text);
                    }
                } else if (!inFragment) {
                    // 未知首帧：忽略
                }
            }
        } catch (IOException ex) {
            if (running.get()) {
                failures.add(ex);
            }
        } catch (RuntimeException ex) {
            if (running.get()) {
                failures.add(ex);
            }
        } finally {
            currentConnection.compareAndSet(socket, null);
        }
    }

    private void handleTextFrame(OutputStream out, String text) throws IOException {
        String cmd = extract(CMD_PATTERN, text);
        String reqId = extract(REQ_ID_PATTERN, text);
        if ("aibot_subscribe".equals(cmd)) {
            int errcode = subscribeErrcode.get();
            sendFrame(out, new Frame(
                    0x1,
                    // 真实协议：订阅应答不带 cmd，req_id 在 headers 内（官方文档 path/101463 响应示例）。
                    ("{\"headers\":{\"req_id\":\""
                                    + reqId
                                    + "\"},\"errcode\":"
                                    + errcode
                                    + ",\"errmsg\":\""
                                    + (errcode == 0 ? "ok" : "rejected")
                                    + "\"}")
                            .getBytes(StandardCharsets.UTF_8)));
        } else if ("aibot_send_msg".equals(cmd) && autoSendMessageAck.get()) {
            int errcode = sendMessageErrcode.get();
            sendFrame(out, new Frame(
                    0x1,
                    ("{\"headers\":{\"req_id\":\""
                                    + reqId
                                    + "\"},\"errcode\":"
                                    + errcode
                                    + ",\"errmsg\":\""
                                    + (errcode == 0 ? "ok" : "rejected")
                                    + "\"}")
                            .getBytes(StandardCharsets.UTF_8)));
        } else if ("ping".equals(cmd) && autoPong.get()) {
            sendFrame(out, new Frame(
                    0x1, ("{\"cmd\":\"pong\",\"req_id\":\"" + reqId + "\"}").getBytes(StandardCharsets.UTF_8)));
        } else if ("aibot_upload_media_init".equals(cmd)) {
            handleUploadInit(out, text, reqId);
        } else if ("aibot_upload_media_chunk".equals(cmd)) {
            handleUploadChunk(out, text, reqId);
        } else if ("aibot_upload_media_finish".equals(cmd)) {
            handleUploadFinish(out, text, reqId);
        }
    }

    private void handleUploadInit(OutputStream out, String text, String reqId) throws IOException {
        if (!autoUploadAck.get() || dropNextInitAck.compareAndSet(true, false)) {
            return;
        }
        int errcode = uploadInitErrcode.get();
        JsonNode body = parseBody(text);
        if (errcode == 0) {
            String uploadId = "up-" + uploadIdSequence.incrementAndGet();
            ServerUploadSession session = new ServerUploadSession(
                    uploadId,
                    body.path("type").asText(),
                    body.path("filename").asText(),
                    body.path("total_size").asLong(-1),
                    body.path("total_chunks").asInt(-1),
                    body.path("md5").asText(null));
            uploadSessions.put(uploadId, session);
            ObjectNode responseBody = MAPPER.createObjectNode();
            if (!omitInitUploadId.get()) {
                responseBody.put("upload_id", uploadId);
            }
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, 0, responseBody)));
        } else {
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, errcode, null)));
        }
    }

    private void handleUploadChunk(OutputStream out, String text, String reqId) throws IOException {
        if (!autoUploadAck.get() || dropNextChunkAck.compareAndSet(true, false)) {
            return;
        }
        JsonNode body = parseBody(text);
        String uploadId = body.path("upload_id").asText(null);
        int chunkIndex = body.path("chunk_index").asInt(-1);
        ServerUploadSession session = uploadId == null ? null : uploadSessions.get(uploadId);
        if (session == null || chunkIndex < 0 || chunkIndex >= session.totalChunks) {
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, 46001, null)));
            return;
        }
        byte[] chunk;
        try {
            chunk = Base64.getDecoder().decode(body.path("base64_data").asText(""));
        } catch (IllegalArgumentException ex) {
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, 46002, null)));
            return;
        }
        session.chunkOrder.add(chunkIndex);
        if (session.chunks[chunkIndex] == null) {
            session.chunks[chunkIndex] = chunk;
            session.receivedChunks.incrementAndGet();
        }
        int delay = chunkAckDelayMillis.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted before chunk ack");
            }
        }
        if (disconnectAfterChunkIndex.compareAndSet(chunkIndex, -1)
                || disconnectEveryChunkIndex.get() == chunkIndex) {
            throw new IOException("simulated disconnect after chunk " + chunkIndex);
        }
        sendFrame(out, new Frame(0x1, ackWithBody(reqId, uploadChunkErrcode.get(), null)));
    }

    private void handleUploadFinish(OutputStream out, String text, String reqId) throws IOException {
        if (!autoUploadAck.get() || dropNextFinishAck.compareAndSet(true, false)) {
            return;
        }
        int errcode = uploadFinishErrcode.get();
        JsonNode body = parseBody(text);
        String uploadId = body.path("upload_id").asText(null);
        ServerUploadSession session = uploadId == null ? null : uploadSessions.get(uploadId);
        if (errcode == 0 && session != null && session.receivedChunks.get() == session.totalChunks) {
            ObjectNode responseBody = MAPPER.createObjectNode();
            String finishType = finishTypeOverride.get();
            responseBody.put("type", finishType == null ? session.type : finishType);
            if (!omitFinishMediaId.get()) {
                responseBody.put("media_id", "media-" + session.uploadId);
            }
            JsonNode createdAtOverride = finishCreatedAtOverride.get();
            responseBody.set(
                    "created_at",
                    createdAtOverride == null
                            ? MAPPER.getNodeFactory().numberNode(1_380_000_000L)
                            : createdAtOverride);
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, 0, responseBody)));
        } else if (errcode == 0 && session == null) {
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, 46001, null)));
        } else if (errcode == 0) {
            // 服务端完整性校验失败（分片未齐全）
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, 46003, null)));
        } else {
            sendFrame(out, new Frame(0x1, ackWithBody(reqId, errcode, null)));
        }
    }

    private static JsonNode parseBody(String text) {
        try {
            return MAPPER.readTree(text).path("body");
        } catch (Exception ex) {
            return MAPPER.createObjectNode();
        }
    }

    private static byte[] ackWithBody(String reqId, int errcode, ObjectNode body) {
        ObjectNode ack = MAPPER.createObjectNode();
        ack.putObject("headers").put("req_id", reqId);
        ack.put("errcode", errcode);
        ack.put("errmsg", errcode == 0 ? "ok" : "rejected");
        if (body != null) {
            ack.set("body", body);
        }
        return ack.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void handshake(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        String header = readHeader(in);
        String key = null;
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring("sec-websocket-key:".length()).trim();
            }
        }
        if (key == null || key.isBlank()) {
            throw new IOException("missing Sec-WebSocket-Key");
        }
        String accept;
        try {
            accept = Base64.getEncoder()
                    .encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception ex) {
            throw new IOException("SHA-1 unavailable", ex);
        }
        OutputStream out = socket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: "
                + accept
                + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** 逐字节读取请求头直到空行，避免缓冲吞掉首个帧字节。 */
    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("client closed during handshake");
            }
            header.write(b);
            byte[] bytes = header.toByteArray();
            if (bytes.length >= 4 && bytes[bytes.length - 4] == '\r'
                    && bytes[bytes.length - 3] == '\n'
                    && bytes[bytes.length - 2] == '\r'
                    && bytes[bytes.length - 1] == '\n') {
                return header.toString(StandardCharsets.US_ASCII);
            }
        }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        if (b1 < 0) {
            throw new EOFException("truncated frame header");
        }
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7F;
        if (length == 126) {
            length = ((in.read() << 8) | in.read()) & 0xFFFF;
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | in.read();
            }
        }
        if (length > Integer.MAX_VALUE) {
            throw new IOException("frame too large");
        }
        byte[] mask = masked ? in.readNBytes(4) : null;
        byte[] payload = in.readNBytes((int) length);
        if (payload.length != length) {
            throw new EOFException("truncated frame payload");
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return new Frame(b0 & 0x0F, (b0 & 0x80) != 0, payload);
    }

    private static void sendFrame(OutputStream out, Frame frame) throws IOException {
        sendFrame(out, frame, true);
    }

    private static void sendFrame(OutputStream out, Frame frame, boolean last) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write((last ? 0x80 : 0x00) | (frame.opcode & 0x0F));
        int length = frame.payload.length;
        if (length < 126) {
            buffer.write(length);
        } else if (length < 65_536) {
            buffer.write(126);
            buffer.write(length >> 8);
            buffer.write(length);
        } else {
            buffer.write(127);
            for (int i = 7; i >= 0; i--) {
                buffer.write((length >>> (8 * i)) & 0xFF);
            }
        }
        buffer.write(frame.payload);
        out.write(buffer.toByteArray());
        out.flush();
    }

    private record Frame(int opcode, boolean fin, byte[] payload) {

        private Frame(int opcode, byte[] payload) {
            this(opcode, true, payload);
        }
    }

    /** 服务端侧上传会话：按 chunk_index 重组分片（重复同片幂等，只计一次）。 */
    private static final class ServerUploadSession {

        private final String uploadId;
        private final String type;
        private final long totalSize;
        private final int totalChunks;
        private final byte[][] chunks;
        private final List<Integer> chunkOrder = new ArrayList<>();
        private final AtomicInteger receivedChunks = new AtomicInteger();

        private ServerUploadSession(
                String uploadId, String type, String filename, long totalSize, int totalChunks, String md5) {
            this.uploadId = uploadId;
            this.type = type;
            this.totalSize = totalSize;
            this.totalChunks = totalChunks;
            this.chunks = new byte[Math.max(0, totalChunks)][];
        }
    }
}
