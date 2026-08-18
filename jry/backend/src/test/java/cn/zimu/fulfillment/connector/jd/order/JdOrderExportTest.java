package cn.zimu.fulfillment.connector.jd.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class JdOrderExportTest {

    private final JdOrderService service = mock(JdOrderService.class);
    private final JdOrderController controller =
            new JdOrderController(service, mock(AuditLogService.class), new ObjectMapper());

    @Test
    void exportReturnsXlsxWithWhitelistedColumnsAndListData() throws Exception {
        when(service.queryOrderNosByPage(anyMap())).thenReturn(new JdResult(
                true, "1000", "ok", "request-export",
                Map.of("totalNum", 2, "resultList", List.of(
                        Map.of("orderNo", "JD-SO-1001", "erpOrderNo", "ZM202608120001"),
                        Map.of("orderNo", "JD-SO-1002", "erpOrderNo", "ZM202608120002")))));

        ResponseEntity<byte[]> response = controller.exportOutboundOrderNos(
                "2026-08-01", "2026-08-13", null, null, "10", "1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment; filename=\"jd-outbound-order-nos-")
                .endsWith(".xlsx");
        assertThat(response.getBody()).isNotNull();

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(0))).isEqualTo("京东单号");
            assertThat(formatter.formatCellValue(sheet.getRow(0).getCell(1))).isEqualTo("ERP单号");
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(0))).isEqualTo("JD-SO-1001");
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(1))).isEqualTo("ZM202608120001");
            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(0))).isEqualTo("JD-SO-1002");
            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(1))).isEqualTo("ZM202608120002");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void exportExtractsRowsFromMockNestedEnvelopeShape() throws Exception {
        // 回归验证：与 MockJdOrderClient.queryOrderNosByPage 的真实输出形状一致——
        // operation/request/response 信封壳，壳内键为 camelCase（totalNum/resultList/orderNo/erpOrderNo），
        // 该形状由 JdMockShapeContractTest 强制，防止用例与 Mock 实际形状脱节。
        when(service.queryOrderNosByPage(anyMap())).thenReturn(new JdResult(
                true, "MOCK_SUCCESS", "mock client completed", "mock-queryOrderNosByPage",
                Map.of("operation", "queryOrderNosByPage", "request", Map.of(), "response", Map.of(
                        "totalNum", 1,
                        "resultList", List.of(Map.of(
                                "orderNo", "MOCK-SO-1001",
                                "erpOrderNo", "ZM202608120001"))))));

        ResponseEntity<byte[]> response = controller.exportOutboundOrderNos(
                null, null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            DataFormatter formatter = new DataFormatter();
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(0))).isEqualTo("MOCK-SO-1001");
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(1))).isEqualTo("ZM202608120001");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void exportReturns502JsonWhenTheUnderlyingQueryFails() throws Exception {
        when(service.queryOrderNosByPage(anyMap())).thenReturn(new JdResult(
                false, "SDK_CALL_FAILED", "京东服务暂时不可用，请稍后重试", null, null));

        ResponseEntity<byte[]> response = controller.exportOutboundOrderNos(
                null, null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/json");
        String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("\"business_code\":\"SDK_CALL_FAILED\"");
        assertThat(body).contains("\"http_status\":502");
        assertThat(body).contains("京东服务暂时不可用");
    }
}
