package cn.zimu.fulfillment.connector.feixiang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code POST /order/ajaxSendOrderProduct} 的生产实现。
 *
 * <p><b>不持有会话</b>：登录、Cookie jar、掉线判定全部借用 {@link FeixiangSession}
 * （即拉取侧那个单例）。本类只做三件事：构造报文、过写门闩、解析响应。
 *
 * <p><b>门闩在 socket 之前</b>：{@link #submit} 先构造完整报文，再问
 * {@link FeixiangShipmentWriteGate}；未放行时打印报文到日志并返回
 * {@link FeixiangShipmentGateway.Outcome#NOT_SENT}，<b>一个字节都不发出</b>。
 * 这是「dry-run 与真写共用同一份报文」的落点：人在演练里核对过的字符串，
 * 与真写时发出去的字符串来自同一次 {@code formBody()} 调用。
 *
 * <p><b>响应判定 fail-closed</b>：{@code status==1} 才 ACCEPTED，{@code status==0} 才
 * REJECTED，其余一切（未知数值、缺字段、非 JSON、HTML 登录页、非 2xx、超时）一律
 * UNKNOWN。飞象的失败文案库整个没有采样过，把未知判成功等于把未知平台行为记成已发货。
 */
@Component
public class FeixiangHttpShipmentGateway implements FeixiangShipmentGateway {

    private static final Logger log = LoggerFactory.getLogger(FeixiangHttpShipmentGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SEND_PATH = "/order/ajaxSendOrderProduct";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);
    /** 响应体上限：平台异常时可能回一整页 HTML，不为一次写请求吞掉无界内存。 */
    private static final int MAX_RESPONSE_CHARS = 64 * 1024;

    private final FeixiangSession session;
    private final FeixiangPullClient pullClient;
    private final FeixiangShipmentWriteGate gate;

    public FeixiangHttpShipmentGateway(
            FeixiangSession session, FeixiangPullClient pullClient, FeixiangShipmentWriteGate gate) {
        this.session = session;
        this.pullClient = pullClient;
        this.gate = gate;
    }

    @Override
    public FeixiangShipmentWriteMode writeMode() {
        return gate.mode();
    }

    @Override
    public void prepareWrite() {
        FeixiangPullClient.LoginResult login = session.login();
        if (!login.ok()) {
            // 写前准备失败属于「可安全重试」：此时确定没有产生任何平台效果。
            throw new FeixiangPullClient.PullTransportException(
                    "飞象发货写前登录失败: " + login.businessCode());
        }
    }

    @Override
    public FeixiangOrderDetail orderDetail(String orderSonId) {
        return pullClient.fetchOrderDetail(orderSonId);
    }

    @Override
    public SubmitResult submit(String orderSonId, FeixiangShipmentRequest request) {
        // 报文先构造：无论是否发出，人看到的都是这一份。
        String body = request.formBody();
        FeixiangShipmentWriteGate.Decision decision = gate.inspectExternalWrite();
        if (!decision.allowed()) {
            log.info(
                    "飞象发货写门闩未放行，未发出请求: mode={}, code={}, order_son_id={}, form_body={}",
                    gate.mode(), decision.businessCode(), orderSonId, body);
            return SubmitResult.notSent(decision.businessCode(), decision.message());
        }
        log.info(
                "飞象发货提交: mode={}, order_son_id={}, lines={}, express_code={}",
                gate.mode(), orderSonId, request.orderProductIds().size(), request.expressCode());
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(session.baseUrl() + SEND_PATH))
                .timeout(WRITE_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", session.baseUrl())
                .header("Referer", session.baseUrl() + "/order/delivery")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = session.exchange(httpRequest, "发货提交");
        } catch (RuntimeException exception) {
            // 请求已经发出，响应没拿到：确定无法判断平台是否已受理。
            return SubmitResult.unknown("飞象发货请求已发出但结果未知（传输失败或超时）");
        }
        return interpret(response.body());
    }

    /** 响应解析：与传输分离，便于单测覆盖每一种响应形状。 */
    static SubmitResult interpret(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return SubmitResult.unknown("飞象发货响应为空，无法确认平台是否已受理");
        }
        String body = rawBody.length() > MAX_RESPONSE_CHARS
                ? rawBody.substring(0, MAX_RESPONSE_CHARS)
                : rawBody;
        if (body.stripLeading().startsWith("<")) {
            // 会话失效时平台回登录页 HTML。绝不回显正文（可能含 PII 与表单字段）。
            return SubmitResult.unknown("飞象发货返回 HTML 而非 JSON（可能会话已失效），结果未知");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception exception) {
            return SubmitResult.unknown("飞象发货响应不是合法 JSON，结果未知");
        }
        JsonNode status = root.path("status");
        Integer code = statusCode(status);
        String message = FeixiangExternalMessageSanitizer.sanitize(
                root.path("msg").asText(""), "平台未提供原因");
        if (code == null) {
            return SubmitResult.unknown("飞象发货响应缺少可解析的 status，结果未知");
        }
        if (code == 1) {
            return SubmitResult.accepted();
        }
        if (code == 0) {
            return SubmitResult.rejected("FEIXIANG_SHIPMENT_REJECTED", message);
        }
        // 既非 0 也非 1：飞象失败文案库未采样，宁可进对账也不判成功或判可重试。
        return SubmitResult.unknown("飞象发货返回未知 status，结果未知：" + message);
    }

    private static Integer statusCode(JsonNode status) {
        if (status.isNumber()) {
            return status.asInt();
        }
        if (status.isTextual()) {
            try {
                return Integer.valueOf(status.asText().trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }
}
