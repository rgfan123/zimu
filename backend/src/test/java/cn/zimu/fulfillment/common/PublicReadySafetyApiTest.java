package cn.zimu.fulfillment.common;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicReadySafetyApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired AuditLogService auditLogService;

    @Test
    void malformedWorkbookReturnsStableActionableMessageWithoutParserDetails() {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(new byte[] {'P', 'K', 3, 4, 0, 1}) {
            @Override
            public String getFilename() {
                return "broken.xlsx";
            }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "public-ready-broken-file-001");
        headers.set("X-Operator", "public-ready-test");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .containsEntry("business_code", "FILE_READ_FAILED")
                .containsEntry("message", "文件无法识别，请确认文件未损坏且格式为 Excel 或 CSV 后重试");
        assertThat(response.getBody().get("message").toString())
                .doesNotContain("zip", "Zip", "broken.xlsx", "Unexpected", "exception");
    }

    @Test
    void auditDetailMasksPersonalDataBeforeItCanReachThePublicHttpResponse() {
        Map<String, Object> chineseRawCells = Map.of(
                "收件人", "中文收件人原文",
                "姓名", "中文姓名原文",
                "电话", "021-88886666",
                "手机号", "13711112222",
                "*收货人手机", "13611112222",
                "地址", "上海市中文地址原文 88 号",
                "邮箱", "nested-pii@example.com",
                "商品名称", "子牧羊小腿");
        AuditLog persisted = auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId("public-ready-audit-pii-001")
                .operator("public-ready-test")
                .actorType(AuditActorType.HUMAN)
                .service("order")
                .operation("order.create")
                .requestPayload(Map.of(
                        "receiver", Map.of(
                                "name", "张三",
                                "phone", "13800000000",
                                "address", "上海市浦东新区测试路 1 号",
                                "email", "zhangsan@example.com"),
                        "product_name", "子牧羊小腿",
                        "contacts", List.of(Map.of("mobile", "13900000000"))))
                .responsePayload(Map.of(
                        "receiver_name", "张三",
                        "receiver_phone", "13800000000",
                        "receiver_address", "上海市浦东新区测试路 1 号",
                        "order_no", "ORDER-001",
                        "rows", List.of(Map.of("raw_cells", chineseRawCells))))
                .httpStatus(201)
                .businessCode("ORDER_CREATED"));

        Map<?, ?> persistedReceiver =
                (Map<?, ?>) persisted.getRequestPayload().get("receiver");
        assertThat(persistedReceiver.get("phone")).isEqualTo("***");
        assertThat(persisted.getResponsePayload().get("receiver_name")).isEqualTo("***");
        Map<?, ?> persistedRawCells = (Map<?, ?>) ((Map<?, ?>) ((List<?>)
                persisted.getResponsePayload().get("rows")).getFirst()).get("raw_cells");
        assertThat(persistedRawCells.get("收件人")).isEqualTo("***");
        assertThat(persistedRawCells.get("姓名")).isEqualTo("***");
        assertThat(persistedRawCells.get("电话")).isEqualTo("***");
        assertThat(persistedRawCells.get("手机号")).isEqualTo("***");
        assertThat(persistedRawCells.get("*收货人手机")).isEqualTo("***");
        assertThat(persistedRawCells.get("地址")).isEqualTo("***");
        assertThat(persistedRawCells.get("邮箱")).isEqualTo("***");
        assertThat(persistedRawCells.get("商品名称")).isEqualTo("子牧羊小腿");
        assertThat(persisted.getResponsePayload().toString()).doesNotContain(
                "中文收件人原文", "中文姓名原文", "021-88886666", "13711112222", "13611112222",
                "上海市中文地址原文 88 号", "nested-pii@example.com");

        ResponseEntity<Map> list = http.getForEntity(
                "/api/v1/audit-logs?request_id=public-ready-audit-pii-001", Map.class);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) list.getBody().get("items")).getFirst();
        ResponseEntity<Map> detail = http.getForEntity(
                "/api/v1/audit-logs/" + item.get("id"), Map.class);

        Map<?, ?> request = (Map<?, ?>) detail.getBody().get("request_payload");
        Map<?, ?> receiver = (Map<?, ?>) request.get("receiver");
        Map<?, ?> contact = (Map<?, ?>) ((List<?>) request.get("contacts")).getFirst();
        Map<?, ?> response = (Map<?, ?>) detail.getBody().get("response_payload");
        assertThat(receiver.get("name")).isEqualTo("***");
        assertThat(receiver.get("phone")).isEqualTo("***");
        assertThat(receiver.get("address")).isEqualTo("***");
        assertThat(receiver.get("email")).isEqualTo("***");
        assertThat(contact.get("mobile")).isEqualTo("***");
        assertThat(request.get("product_name")).isEqualTo("子牧羊小腿");
        assertThat(response.get("receiver_name")).isEqualTo("***");
        assertThat(response.get("receiver_phone")).isEqualTo("***");
        assertThat(response.get("receiver_address")).isEqualTo("***");
        assertThat(response.get("order_no")).isEqualTo("ORDER-001");
        Map<?, ?> responseRawCells = (Map<?, ?>) ((Map<?, ?>) ((List<?>) response.get("rows")).getFirst())
                .get("raw_cells");
        assertThat(responseRawCells.get("收件人")).isEqualTo("***");
        assertThat(responseRawCells.get("姓名")).isEqualTo("***");
        assertThat(responseRawCells.get("电话")).isEqualTo("***");
        assertThat(responseRawCells.get("手机号")).isEqualTo("***");
        assertThat(responseRawCells.get("*收货人手机")).isEqualTo("***");
        assertThat(responseRawCells.get("地址")).isEqualTo("***");
        assertThat(responseRawCells.get("邮箱")).isEqualTo("***");
        assertThat(responseRawCells.get("商品名称")).isEqualTo("子牧羊小腿");
        assertThat(detail.getBody().toString()).doesNotContain(
                "中文收件人原文", "中文姓名原文", "021-88886666", "13711112222", "13611112222",
                "上海市中文地址原文 88 号", "nested-pii@example.com");
    }

    @Test
    void jdPersonalContainersAreMaskedBeforeAuditPersistenceAndPublicHttpResponse() {
        Map<String, Object> receiverInfo = Map.of(
                "name", "京东收件人原文",
                "mobile", "13511112222",
                "postCode", "200120",
                "province", "上海市",
                "city", "上海市",
                "county", "浦东新区",
                "town", "潍坊新村街道",
                "detailAddress", "测试路 99 号");
        Map<String, Object> senderInfo = Map.of(
                "name", "京东寄件人原文",
                "postCode", "100000",
                "province", "北京市",
                "city", "北京市");
        Map<String, Object> consignee = Map.of(
                "name", "京东联系人原文",
                "phone", "010-88886666",
                "address", "北京市测试地址");
        Map<String, Object> afterSalesInfo = Map.of(
                "afterSalesName", "京东售后联系人原文",
                "afterSalesMobile", "13800000000",
                "afterSalesAddress", "北京市售后测试地址");
        AuditLog persisted = auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId("public-ready-jd-audit-pii-001")
                .operator("jd-client")
                .actorType(AuditActorType.SYSTEM)
                .service("jd.isc")
                .operation("queryOutboundOrder")
                .responsePayload(Map.of("data", Map.of(
                        "receiverInfo", receiverInfo,
                        "senderInfo", senderInfo,
                        "consignee", consignee,
                        "afterSalesInfo", afterSalesInfo,
                        "goodsInfo", Map.of("goodsNo", "JD-SKU-001", "goodsName", "子牧羊小腿"),
                        "status", "SHIPPED")))
                .httpStatus(200)
                .businessCode("1000"));

        Map<?, ?> persistedData = (Map<?, ?>) persisted.getResponsePayload().get("data");
        assertThat(persistedData.get("receiverInfo")).isEqualTo("***");
        assertThat(persistedData.get("senderInfo")).isEqualTo("***");
        assertThat(persistedData.get("consignee")).isEqualTo("***");
        assertThat(persistedData.get("afterSalesInfo")).isEqualTo("***");
        assertThat(persistedData.get("goodsInfo"))
                .isEqualTo(Map.of("goodsNo", "JD-SKU-001", "goodsName", "子牧羊小腿"));
        assertThat(persistedData.get("status")).isEqualTo("SHIPPED");
        assertThat(persisted.getResponsePayload().toString()).doesNotContain(
                "京东收件人原文", "13511112222", "200120", "上海市", "浦东新区", "潍坊新村街道", "测试路 99 号",
                "京东寄件人原文", "100000", "北京市", "京东联系人原文", "010-88886666", "北京市测试地址",
                "京东售后联系人原文", "13800000000", "北京市售后测试地址");

        ResponseEntity<Map> list = http.getForEntity(
                "/api/v1/audit-logs?request_id=public-ready-jd-audit-pii-001", Map.class);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) list.getBody().get("items")).getFirst();
        ResponseEntity<Map> detail = http.getForEntity(
                "/api/v1/audit-logs/" + item.get("id"), Map.class);

        Map<?, ?> response = (Map<?, ?>) detail.getBody().get("response_payload");
        Map<?, ?> responseData = (Map<?, ?>) response.get("data");
        assertThat(responseData.get("receiverInfo")).isEqualTo("***");
        assertThat(responseData.get("senderInfo")).isEqualTo("***");
        assertThat(responseData.get("consignee")).isEqualTo("***");
        assertThat(responseData.get("afterSalesInfo")).isEqualTo("***");
        assertThat(responseData.get("goodsInfo"))
                .isEqualTo(Map.of("goodsNo", "JD-SKU-001", "goodsName", "子牧羊小腿"));
        assertThat(responseData.get("status")).isEqualTo("SHIPPED");
        assertThat(detail.getBody().toString()).doesNotContain(
                "京东收件人原文", "13511112222", "200120", "上海市", "浦东新区", "潍坊新村街道", "测试路 99 号",
                "京东寄件人原文", "100000", "北京市", "京东联系人原文", "010-88886666", "北京市测试地址",
                "京东售后联系人原文", "13800000000", "北京市售后测试地址");
    }
}
