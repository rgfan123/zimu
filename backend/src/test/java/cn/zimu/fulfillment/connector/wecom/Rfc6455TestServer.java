package cn.zimu.fulfillment.connector.wecom;

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
import java.util.Base64;
import java.util.List;
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
 * errcode 应答；对 {@code ping} 帧默认回 {@code pong}（可关闭以模拟僵死）；所有收到的文本帧
 * 进入队列供断言。服务器→客户端帧不掩码，客户端→服务器帧自动解掩码。
 */
final class Rfc6455TestServer implements AutoCloseable {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern CMD_PATTERN = Pattern.compile("\"cmd\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REQ_ID_PATTERN = Pattern.compile("\"req_id\"\\s*:\\s*\"([^\"]*)\"");

    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicInteger subscribeErrcode = new AtomicInteger(0);
    private final AtomicInteger sendMessageErrcode = new AtomicInteger(0);
    private final AtomicBoolean autoPong = new AtomicBoolean(true);
    private final AtomicBoolean autoSendMessageAck = new AtomicBoolean(true);
    private final AtomicReference<Socket> currentConnection = new AtomicReference<>();
    private final CopyOnWriteArrayList<String> receivedTextFrames = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

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
            while (running.get()) {
                Frame frame = readFrame(in);
                if (frame == null) {
                    break;
                }
                switch (frame.opcode) {
                    case 0x1 -> {
                        String text = new String(frame.payload, StandardCharsets.UTF_8);
                        receivedTextFrames.add(text);
                        handleTextFrame(out, text);
                    }
                    case 0x8 -> {
                        // 客户端关闭：回显关闭帧并结束连接
                        sendFrame(out, new Frame(0x8, frame.payload));
                        return;
                    }
                    case 0x9 -> {
                        if (autoPong.get()) {
                            sendFrame(out, new Frame(0xA, frame.payload));
                        }
                    }
                    case 0xA -> {
                        // pong，无需处理
                    }
                    default -> {
                        // 忽略其他帧类型
                    }
                }
            }
        } catch (IOException ignored) {
            // 客户端断开
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
        }
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
        return new Frame(b0 & 0x0F, payload);
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

    private record Frame(int opcode, byte[] payload) {}
}
