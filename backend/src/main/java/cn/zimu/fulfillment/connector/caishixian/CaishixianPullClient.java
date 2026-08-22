package cn.zimu.fulfillment.connector.caishixian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 彩食鲜「待发货订单」在线拉取客户端（ticket 07）。
 *
 * <p>契约见 {@code docs/research/caishixian-scc-wapi-export-api.md}（2026-08-18 抓包核实）：
 * <ol>
 *   <li>登录：{@code POST /ucenter/login/scc}，body {@code {username,password,businessCode:"fe-web-scc"}}，
 *       登录成功后在<b>响应头</b>返回新 JWT（自定义头 {@code login-token}）；</li>
 *   <li>发起导出：{@code POST /scc/bbc/order/exportDeliverExcl}，data = 任务 ID；</li>
 *   <li>轮询：{@code GET /task/task/my?sysCode=TASK-SCHEDULING&taskType=csx-b2b-supplier-schedule}，
 *       完成判定 {@code taskStatus==2 && progress==100 && resultCode==200000}；</li>
 *   <li>下载：{@code GET /task/file/download?name=&url=}（url 取自 {@code taskAttach[0].url}，
 *       预签名 COS URL），返回 xlsx 字节（{@code PK} 魔数校验）。</li>
 * </ol>
 *
 * <p>业务请求必带头 {@code login-token: <JWT>} 与 {@code supplier-code: <供应商代码>}。
 * 凭据只走环境变量 {@code CSX_USERNAME} / {@code CSX_PASSWORD} / {@code CSX_SUPPLIER_CODE}，
 * 绝不落盘、不打日志。</p>
 *
 * <p>本接口是 Connector 与真实 HTTP 之间的可注入缝：生产用 {@link Http}（JDK HttpClient，
 * 无外部依赖），单元测试用 mock/stub，避免真实网络与合规红线。</p>
 */
public interface CaishixianPullClient {

    /** 登录结果；token 仅在 ok 时非空。businessCode 复用业务码（CREDENTIALS_REQUIRED / PLATFORM_AUTH_FAILED / OK）。 */
    record LoginResult(boolean ok, String businessCode, String message, String token) {
        public static LoginResult failed(String businessCode, String message) {
            return new LoginResult(false, businessCode, message, null);
        }
    }

    /** 登录（内部读取环境变量凭据；未配置/失败返回失败结果而非抛异常）。 */
    LoginResult login();

    /**
     * 发起导出任务 → 轮询至完成 → 下载 xlsx 字节。
     *
     * @param token    登录返回的 login-token
     * @param payBegin 支付开始日期 yyyy-MM-dd
     * @param payEnd   支付结束日期 yyyy-MM-dd（含）
     * @return xlsx 字节（PK 魔数已校验）
     * @throws PullTransportException 传输/业务失败
     */
    byte[] pullDeliverExport(String token, String payBegin, String payEnd);

    /** 彩食鲜拉取传输/业务失败；消息不携带任何密钥或请求体。 */
    class PullTransportException extends RuntimeException {
        public PullTransportException(String message) {
            super(message, null, false, false);
        }

        public PullTransportException(String message, Throwable cause) {
            super(message, cause, false, false);
        }
    }

    /** 生产实现：JDK {@link HttpClient}，无外部依赖。 */
    @Component("caishixianPullClient")
    class Http implements CaishixianPullClient {

        private static final Logger log = LoggerFactory.getLogger(Http.class);

        private static final String BASE_URL = "https://wapi.freshfood.cn";
        private static final String ORIGIN = "https://scc.freshfood.cn";
        private static final String SYS_CODE = "TASK-SCHEDULING";
        private static final String TASK_TYPE_SUPPLIER = "csx-b2b-supplier-schedule";
        private static final String AUTH_HEADER = "login-token";
        private static final String SUPPLIER_CODE_HEADER = "supplier-code";
        private static final String BUSINESS_CODE = "fe-web-scc";
        private static final String DEFAULT_SUPPLIER_CODE = "20075684";
        private static final int TASK_STATUS_DONE = 2;
        private static final String RESULT_CODE_OK = "200000";
        private static final int POLL_INTERVAL_SECONDS = 5;
        private static final int POLL_MAX_ROUNDS = 20;
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

        private final HttpClient client;
        private final ObjectMapper mapper;

        public Http() {
            this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), new ObjectMapper());
        }

        /** 测试入口：注入 HttpClient 与 ObjectMapper（避免测试真连）。 */
        Http(HttpClient client, ObjectMapper mapper) {
            this.client = client;
            this.mapper = mapper;
        }

        @Override
        public LoginResult login() {
            String username = env("CSX_USERNAME");
            String password = env("CSX_PASSWORD");
            if (isBlank(username) || isBlank(password)) {
                return LoginResult.failed("CREDENTIALS_REQUIRED", "彩食鲜凭据未配置（CSX_USERNAME/CSX_PASSWORD）");
            }
            try {
                Map<String, Object> body = Map.of(
                        "username", username,
                        "password", password,
                        "businessCode", BUSINESS_CODE);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/ucenter/login/scc"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, */*")
                        .header("Origin", ORIGIN)
                        .header("Referer", ORIGIN + "/")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode root = parse(response.body());
                if (!codeOk(root)) {
                    return LoginResult.failed("PLATFORM_AUTH_FAILED",
                            "登录失败: " + messageOf(root, "HTTP " + response.statusCode()));
                }
                String token = response.headers().firstValue(AUTH_HEADER).orElse("");
                if (token.isBlank()) {
                    return LoginResult.failed("PLATFORM_AUTH_FAILED", "登录成功但响应头缺少 login-token");
                }
                return new LoginResult(true, "OK", "登录成功", token);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "彩食鲜登录被中断");
            } catch (Exception exception) {
                log.warn("彩食鲜登录失败", exception);
                return LoginResult.failed("PLATFORM_UNAVAILABLE", "彩食鲜登录失败: " + safeMessage(exception));
            }
        }

        @Override
        public byte[] pullDeliverExport(String token, String payBegin, String payEnd) {
            int taskId = startExportTask(token, payBegin, payEnd);
            JsonNode task = pollUntilDone(token, taskId);
            String[] attach = taskAttach(task);
            if (attach == null) {
                throw new PullTransportException("任务完成但响应中未找到文件 URL（taskAttach 为空）");
            }
            return downloadFile(token, attach[0], attach[1]);
        }

        private int startExportTask(String token, String payBegin, String payEnd) {
            Map<String, Object> body = Map.of(
                    "payTimeBegin", payBegin,
                    "payTimeEnd", payEnd,
                    "pageNum", 1,
                    "pageSize", 10,
                    "orderStatus", "3");
            JsonNode root = postJson("/scc/bbc/order/exportDeliverExcl", token, body);
            if (!codeOk(root) || !root.path("data").isNumber()) {
                throw new PullTransportException("发起导出失败: " + messageOf(root, "响应缺少任务 ID"));
            }
            return root.path("data").asInt();
        }

        private JsonNode pollUntilDone(String token, int taskId) {
            for (int round = 1; round <= POLL_MAX_ROUNDS; round++) {
                JsonNode root = getJson("/task/task/my?sysCode=" + SYS_CODE + "&taskType=" + TASK_TYPE_SUPPLIER, token);
                if (codeOk(root) && root.path("data").isArray()) {
                    for (JsonNode task : root.path("data")) {
                        if (task.path("id").asInt(-1) != taskId) {
                            continue;
                        }
                        int status = task.path("taskStatus").asInt(-1);
                        int progress = task.path("currProgress").asInt(-1);
                        int totalProgress = task.path("totalProgress").asInt(-1);
                        String resultCode = task.path("resultCode").asText("");
                        if (status == TASK_STATUS_DONE && progress == 100 && totalProgress == 100
                                && RESULT_CODE_OK.equals(resultCode)) {
                            return task;
                        }
                        if (status == TASK_STATUS_DONE) {
                            throw new PullTransportException("导出任务失败: " + taskMessage(task));
                        }
                    }
                }
                log.info("彩食鲜导出任务 {} 尚未完成（{}/{} 轮），{}s 后重试",
                        taskId, round, POLL_MAX_ROUNDS, POLL_INTERVAL_SECONDS);
                if (round < POLL_MAX_ROUNDS) {
                    sleep(POLL_INTERVAL_SECONDS * 1000L);
                }
            }
            throw new PullTransportException(
                    "轮询超时（" + POLL_MAX_ROUNDS + " 轮 × " + POLL_INTERVAL_SECONDS + "s），任务 " + taskId + " 未完成");
        }

        /** 从任务对象取 [文件名, 文件URL]。taskAttach 是 JSON 字符串（实测），内容为 [{name,url}]。 */
        private String[] taskAttach(JsonNode task) {
            JsonNode attach = task.path("taskAttach");
            if (attach.isTextual()) {
                try {
                    attach = mapper.readTree(attach.asText());
                } catch (Exception exception) {
                    attach = null;
                }
            }
            if (attach != null && attach.isArray() && !attach.isEmpty()) {
                JsonNode first = attach.get(0);
                String url = first.path("url").asText("");
                if (!url.isBlank()) {
                    String name = first.path("name").asText("待发货订单.xlsx");
                    return new String[] {name, url};
                }
            }
            return null;
        }

        private byte[] downloadFile(String token, String name, String url) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/task/file/download?name=" + encode(name) + "&url=" + encode(url)))
                    .timeout(REQUEST_TIMEOUT.multipliedBy(4))
                    .header("Accept", "application/json, */*")
                    .header("Origin", ORIGIN)
                    .header("Referer", ORIGIN + "/")
                    .header(AUTH_HEADER, token)
                    .header(SUPPLIER_CODE_HEADER, supplierCode())
                    .GET()
                    .build();
            byte[] bytes = sendBytes(request);
            if (bytes == null || bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
                throw new PullTransportException("下载内容不是 xlsx（魔数异常，可能任务未完成）");
            }
            return bytes;
        }

        // ---------------------------------------------------------------- 传输工具

        private JsonNode getJson(String pathAndQuery, String token) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + pathAndQuery))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json, */*")
                    .header("Origin", ORIGIN)
                    .header("Referer", ORIGIN + "/")
                    .header(AUTH_HEADER, token)
                    .header(SUPPLIER_CODE_HEADER, supplierCode())
                    .GET()
                    .build();
            return parse(sendText(request));
        }

        private JsonNode postJson(String path, String token, Map<String, Object> body) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, */*")
                        .header("Origin", ORIGIN)
                        .header("Referer", ORIGIN + "/")
                        .header(AUTH_HEADER, token)
                        .header(SUPPLIER_CODE_HEADER, supplierCode())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build();
                return parse(sendText(request));
            } catch (PullTransportException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new PullTransportException("请求构造失败", exception);
            }
        }

        private String sendText(HttpRequest request) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new PullTransportException("接口返回 HTTP " + response.statusCode());
                }
                return response.body();
            } catch (PullTransportException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("彩食鲜请求被中断");
            } catch (Exception exception) {
                throw new PullTransportException("彩食鲜请求失败: " + safeMessage(exception), exception);
            }
        }

        private byte[] sendBytes(HttpRequest request) {
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 400) {
                    throw new PullTransportException("下载接口返回 HTTP " + response.statusCode());
                }
                return response.body();
            } catch (PullTransportException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("彩食鲜请求被中断");
            } catch (Exception exception) {
                throw new PullTransportException("彩食鲜请求失败: " + safeMessage(exception), exception);
            }
        }

        private JsonNode parse(String body) {
            try {
                return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
            } catch (Exception exception) {
                throw new PullTransportException("彩食鲜响应解析失败");
            }
        }

        private boolean codeOk(JsonNode root) {
            return root != null && RESULT_CODE_OK.equals(root.path("code").asText(""));
        }

        private String messageOf(JsonNode root, String fallback) {
            if (root == null) {
                return fallback;
            }
            String message = root.path("message").asText("");
            return message.isBlank() ? fallback : message;
        }

        private String taskMessage(JsonNode task) {
            String message = task.path("taskMessage").asText("");
            return message.isBlank() ? "taskResult=" + task.path("taskResult").asText() : message;
        }

        private String supplierCode() {
            String code = env("CSX_SUPPLIER_CODE");
            return isBlank(code) ? DEFAULT_SUPPLIER_CODE : code;
        }

        private static String env(String name) {
            String value = System.getenv(name);
            return value == null ? "" : value.trim();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private static String safeMessage(Throwable exception) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new PullTransportException("轮询被中断");
            }
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
