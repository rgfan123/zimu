package cn.zimu.fulfillment.connector.caishixian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 彩食鲜 JSON 直连客户端测试：HTTP 全部用桩（mock HttpClient），绝不触网。
 *
 * <p>交叉验证边界（如实声明）：本测试锁定的是「我方按 2026-08-18 抓包契约发什么、
 * 怎么解析响应」——请求体逐字段（含 orderStatus="3" 与 pageNum/pageSize）、
 * totalNum/waitDepotNum 的解析与缺失语义。平台侧 orderStatus/pageSize 的真实语义
 * 仍基于单次抓包观测，只能靠生产对账日志（waitDepotNum vs 实取行数）继续定案。</p>
 */
class CaishixianPullClientTest {

    private final HttpClient http = mock(HttpClient.class);
    private final CaishixianPullClient.Http client =
            new CaishixianPullClient.Http(http, new ObjectMapper());

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        return response;
    }

    /** 从 HttpRequest 的 BodyPublisher 同步取出请求体字符串（测试桩专用）。 */
    private static String bodyOf(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        List<ByteBuffer> buffers = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                buffers.add(item);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.join();
        int size = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer all = ByteBuffer.allocate(size);
        buffers.forEach(all::put);
        return new String(all.array(), StandardCharsets.UTF_8);
    }

    @Test
    void pullOrderPageSendsCapturedContractBodyAndParsesReconciliationFacts() throws Exception {
        String payload = """
                {"code": 200000, "message": "success", "data": {
                   "pageNum": 2, "pageSize": 10, "totalNum": 12,
                   "data": [{"orderCode": "MAIN-11"}, {"orderCode": "MAIN-12"}],
                   "number": {"waitDepotNum": 12, "deliveryNum": 3, "all": 15, "notInt": "x"}
                }}
                """;
        HttpResponse<String> stubbed = response(200, payload);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stubbed);

        CaishixianPullClient.OrderPage page =
                client.pullOrderPage("token-1", "2026-07-29", "2026-08-28", 2, 10);

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest request = captor.getValue();
        assertThat(request.uri().toString()).isEqualTo("https://wapi.freshfood.cn/scc/bbc/order/orderList");
        assertThat(request.headers().firstValue("login-token")).contains("token-1");
        assertThat(request.headers().firstValue("supplier-code")).isPresent();
        // 请求体与 §4.4 抓包契约逐字段一致（orderStatus 是字符串 "3"，页参数真实生效）
        assertThat(bodyOf(request)).isEqualTo(
                "{\"payTimeBegin\":\"2026-07-29\",\"payTimeEnd\":\"2026-08-28\","
                        + "\"pageNum\":2,\"pageSize\":10,\"orderStatus\":\"3\"}");

        assertThat(page.pageNum()).isEqualTo(2);
        assertThat(page.totalNum()).isEqualTo(12);
        assertThat(page.orders()).hasSize(2);
        assertThat(page.orders().getFirst().path("orderCode").asText()).isEqualTo("MAIN-11");
        // 对账王牌：waitDepotNum 原样带回；number 里的整数计数全部进快照
        assertThat(page.waitDepotNum()).isEqualTo(12);
        assertThat(page.statusCounts())
                .containsEntry("waitDepotNum", 12)
                .containsEntry("deliveryNum", 3)
                .containsEntry("all", 15)
                .doesNotContainKey("notInt");
    }

    @Test
    void pullOrderPageKeepsWaitDepotNumHonestlyNullWhenPlatformOmitsIt() throws Exception {
        String payload = """
                {"code": 200000, "data": {"pageNum": 1, "totalNum": 0, "data": []}}
                """;
        HttpResponse<String> stubbed = response(200, payload);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stubbed);

        CaishixianPullClient.OrderPage page =
                client.pullOrderPage("token-1", "2026-07-29", "2026-08-28", 1, 10);

        // 平台没报 number 时如实为 null（不造 0）——对账文案会写「未报告」而不是假装一致
        assertThat(page.waitDepotNum()).isNull();
        assertThat(page.statusCounts()).isEmpty();
        assertThat(page.orders()).isEmpty();
        assertThat(page.totalNum()).isZero();
    }

    @Test
    void pullOrderPageSurfacesPlatformBusinessError() throws Exception {
        HttpResponse<String> stubbed = response(200, "{\"code\": 110500000, \"message\": \"登录已过期\"}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stubbed);

        assertThatThrownBy(() -> client.pullOrderPage("token-1", "2026-07-29", "2026-08-28", 1, 10))
                .isInstanceOf(CaishixianPullClient.PullTransportException.class)
                .hasMessageContaining("登录已过期");
    }

    @Test
    void pullOrderPageFailsOnHttpError() throws Exception {
        HttpResponse<String> stubbed = response(502, "");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stubbed);

        assertThatThrownBy(() -> client.pullOrderPage("token-1", "2026-07-29", "2026-08-28", 1, 10))
                .isInstanceOf(CaishixianPullClient.PullTransportException.class)
                .hasMessageContaining("502");
    }

    @Test
    void pullOrderDetailEncodesIdAndReturnsDataNode() throws Exception {
        String payload = """
                {"code": 200000, "data": {"orderCode": "MAIN-1", "receiverProvince": "河南省"}}
                """;
        HttpResponse<String> stubbed = response(200, payload);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stubbed);

        var detail = client.pullOrderDetail("token-1", "98 76");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captor.getValue().uri().toString())
                .isEqualTo("https://wapi.freshfood.cn/scc/bbc/order/detail?id=98+76");
        assertThat(captor.getValue().method()).isEqualTo("GET");
        assertThat(detail.path("orderCode").asText()).isEqualTo("MAIN-1");
    }

    @Test
    void pullOrderDetailFailsWhenDataMissing() throws Exception {
        HttpResponse<String> stubbed = response(200, "{\"code\": 200000, \"data\": null}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stubbed);

        assertThatThrownBy(() -> client.pullOrderDetail("token-1", "9876"))
                .isInstanceOf(CaishixianPullClient.PullTransportException.class);
    }

    @Test
    void loginWithoutCredentialsFailsClosedWithoutNetwork() {
        // 本用例只在环境未配置真实凭据时有意义（CI 无凭据必跑；本机导出了 CSX_* 时跳过）
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("CSX_USERNAME") == null && System.getenv("CSX_PASSWORD") == null,
                "本机已配置 CSX 凭据，跳过凭据缺失用例");

        CaishixianPullClient.LoginResult result = client.login();

        assertThat(result.ok()).isFalse();
        assertThat(result.businessCode()).isEqualTo("CREDENTIALS_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(http);
    }
}
