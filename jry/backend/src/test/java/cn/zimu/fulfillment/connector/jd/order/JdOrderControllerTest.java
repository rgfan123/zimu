package cn.zimu.fulfillment.connector.jd.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdOrderControllerTest {

    private final JdOrderService service = mock(JdOrderService.class);
    private final JdOrderController controller =
            new JdOrderController(service, mock(AuditLogService.class), new ObjectMapper());

    @Test
    void outboundOrderNosMapsSnakeCaseHttpParamsToCamelCaseSdkCommand() {
        when(service.queryOrderNosByPage(anyMap())).thenReturn(new JdResult(
                true, "1000", "ok", "request-1",
                Map.of("totalNum", 1, "resultList", List.of(
                        Map.of("orderNo", "JD-SO-1001", "erpOrderNo", "ZM202608120001")))));

        JdResult result = controller.outboundOrderNos(
                "2026-08-01", "2026-08-13", null, null,
                "10", "1", "SHOP-1", "1", "50");

        Map<String, Object> command = capturedCommand();
        assertThat(command).containsEntry("startDate", "2026-08-01");
        assertThat(command).containsEntry("endDate", "2026-08-13");
        assertThat(command).containsEntry("status", "10");
        assertThat(command).containsEntry("orderType", "1");
        assertThat(command).containsEntry("shopNo", "SHOP-1");
        assertThat(command).containsEntry("currentPage", 1);
        assertThat(command).containsEntry("pageSize", 50);
        assertThat(command).doesNotContainKey("startFinishDate");

        assertThat(data(result)).containsKey("resultList");
    }

    @Test
    void httpResultRemovesPersonalDataButKeepsBusinessFacts() {
        when(service.queryAdjustment(anyMap())).thenReturn(new JdResult(
                true, "200", "ok", "request-2",
                Map.of(
                        "adjustmentNo", "ADJ-001",
                        "status", "CONFIRMED",
                        "customerInfo", Map.of("mobile", "13800000000", "name", "张三"),
                        "receiverInfo", Map.of("phone", "010-88886666"))));

        JdResult result = controller.adjustments(
                "ADJ-001", null, null, null, "CONFIRMED", "1");

        Map<String, Object> safeData = data(result);
        assertThat(safeData).containsKey("adjustmentNo");
        assertThat(safeData).containsKey("status");
        assertThat(safeData).doesNotContainKey("customerInfo");
        assertThat(safeData).doesNotContainKey("receiverInfo");
    }

    @Test
    void httpResultRemovesPhoneAndMobileKeysBySuffixIncludingNestedMaps() {
        when(service.queryCityTrack(anyMap())).thenReturn(new JdResult(
                true, "200", "ok", "request-6",
                Map.of(
                        "deliveryNo", "DELIVERY-1",
                        "transporterPhone", "13800000000",
                        "cityTrack", List.of(Map.of(
                                "city", "北京",
                                "transporterMobile", "13900000000")))));

        JdResult result = controller.cityTracks("DELIVERY-1", null);

        Map<String, Object> safeData = data(result);
        assertThat(safeData).containsKey("deliveryNo");
        assertThat(safeData).doesNotContainKey("transporterPhone");
        Map<String, Object> track = row(safeData, "cityTrack");
        assertThat(track).containsKey("city");
        assertThat(track).doesNotContainKey("transporterMobile");
    }

    @Test
    void httpResultRedactsEmailFaxAndAddressKeysRegardlessOfCase() {
        when(service.queryProcessed(anyMap())).thenReturn(new JdResult(
                true, "200", "ok", "request-7",
                Map.of(
                        "processedNo", "PR-1",
                        "backEmail", "refund@example.com",
                        "ContactFax", "010-88889999",
                        "linkAddress", "北京市亦庄经济开发区")));

        JdResult result = controller.processedOrders("PR-1", null);

        Map<String, Object> safeData = data(result);
        assertThat(safeData).containsKey("processedNo");
        assertThat(safeData).doesNotContainKey("backEmail");
        assertThat(safeData).doesNotContainKey("ContactFax");
        assertThat(safeData).doesNotContainKey("linkAddress");
    }

    @Test
    void httpResultRedactsPersonRoleNamesButKeepsBusinessEntityNames() {
        when(service.queryCityTrack(anyMap())).thenReturn(new JdResult(
                true, "200", "ok", "request-8",
                Map.of(
                        "deliveryNo", "DELIVERY-1",
                        "transporterName", "李师傅",
                        "shipperName", "王寄件",
                        "ownerName", "子牧餐饮",
                        "shopName", "北京门店",
                        "cityTrack", List.of(Map.of(
                                "city", "北京",
                                "operateName", "张操作")))));

        JdResult result = controller.cityTracks("DELIVERY-1", null);

        Map<String, Object> safeData = data(result);
        assertThat(safeData).containsKey("deliveryNo");
        assertThat(safeData).doesNotContainKey("transporterName");
        assertThat(safeData).doesNotContainKey("shipperName");
        assertThat(safeData).containsKey("ownerName");
        assertThat(safeData).containsKey("shopName");
        @SuppressWarnings("unchecked")
        Map<String, Object> track = (Map<String, Object>) ((List<?>) safeData.get("cityTrack")).get(0);
        assertThat(track).containsKey("city");
        assertThat(track).doesNotContainKey("operateName");
    }

    @Test
    void sdkFailureResultPassesThroughWithStableBusinessCode() {
        when(service.queryCityTrack(anyMap())).thenReturn(new JdResult(
                false, "SDK_CALL_FAILED", "京东服务暂时不可用，请稍后重试", null, null));

        JdResult result = controller.cityTracks("DELIVERY-1", null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("SDK_CALL_FAILED");
        assertThat(result.message()).isEqualTo("京东服务暂时不可用，请稍后重试");
    }

    @Test
    void invalidNumericParamsAreDroppedInsteadOfFailingTheRequest() {
        when(service.queryOrderNosByPage(anyMap())).thenReturn(new JdResult(
                true, "1000", "ok", "request-3", Map.of()));

        controller.outboundOrderNos(
                null, null, null, null, null, null, null, "not-a-number", "50");

        Map<String, Object> command = capturedCommand();
        assertThat(command).doesNotContainKey("currentPage");
        assertThat(command).containsEntry("pageSize", 50);
    }

    @Test
    void adjustmentsNormalizesSpaceSeparatedTimesToIsoForTheSdkContract() {
        when(service.queryAdjustment(anyMap())).thenReturn(new JdResult(
                true, "200", "ok", "request-4", Map.of()));

        controller.adjustments(
                "ADJ-001", null, "2026-08-01 00:00:00", "2026-08-13 23:59:59", null, null);

        Map<String, Object> command = capturedAdjustmentCommand();
        assertThat(command).containsEntry("startTime", "2026-08-01T00:00:00");
        assertThat(command).containsEntry("endTime", "2026-08-13T23:59:59");
    }

    @Test
    void adjustmentsPassesThroughIsoTimesAndNullsUnchanged() {
        when(service.queryAdjustment(anyMap())).thenReturn(new JdResult(
                true, "200", "ok", "request-5", Map.of()));

        controller.adjustments(
                null, null, "2026-08-01T00:00:00", "2026-08-13", "10", null);

        Map<String, Object> command = capturedAdjustmentCommand();
        assertThat(command).containsEntry("startTime", "2026-08-01T00:00:00");
        assertThat(command).containsEntry("endTime", "2026-08-13");
        assertThat(command).containsEntry("status", 10);
        assertThat(command).doesNotContainKey("adjustmentNo");
        assertThat(command).doesNotContainKey("bizType");
    }

    @Test
    void adjustmentsRejectsInvalidTimeFormatsWithParameterErrorWithoutCallingService() {
        JdResult result = controller.adjustments(
                "ADJ-001", null, "2026-08-01 00:00", null, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("INVALID_PARAM");
        assertThat(result.message()).isEqualTo("时间格式需为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        assertThat(result.data()).isNull();
        verifyNoInteractions(service);
    }

    @Test
    void adjustmentsRejectsInvalidEndTimeFormatWithoutCallingService() {
        JdResult result = controller.adjustments(
                null, null, null, "2026-08-13 23:59", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("INVALID_PARAM");
        verifyNoInteractions(service);
    }

    @Test
    void mockClientReturnsStableDataForEveryQueryWithoutTouchingTheNetwork() {
        MockJdOrderClient mock = new MockJdOrderClient();

        JdResult page = mock.queryOrderNosByPage(Map.of("currentPage", 1, "pageSize", 20));
        assertThat(page.success()).isTrue();
        assertThat(page.businessCode()).isEqualTo("MOCK_SUCCESS");
        assertThat(page.requestId()).isEqualTo("mock-queryOrderNosByPage");
        assertThat(page.data()).isNotNull();

        assertThat(mock.queryAdjustment(Map.of()).success()).isTrue();
        assertThat(mock.queryDestroy(Map.of()).success()).isTrue();
        assertThat(mock.queryException(Map.of()).success()).isTrue();
        assertThat(mock.queryPurchase(Map.of()).success()).isTrue();
        assertThat(mock.queryProcessed(Map.of()).success()).isTrue();
        assertThat(mock.queryOperateRelation(Map.of()).success()).isTrue();
        assertThat(mock.queryDeliveryTime(Map.of()).success()).isTrue();
        assertThat(mock.queryCityTrack(Map.of()).success()).isTrue();

        JdResult again = mock.queryOrderNosByPage(Map.of("currentPage", 1, "pageSize", 20));
        assertThat(again.data()).isEqualTo(page.data());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedCommand() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryOrderNosByPage(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedAdjustmentCommand() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(service).queryAdjustment(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(JdResult result) {
        return (Map<String, Object>) result.data();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> parent, String key) {
        return (Map<String, Object>) ((List<?>) parent.get(key)).get(0);
    }
}
