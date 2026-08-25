package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 回填结果工作簿：表头必须与发货清单逐字一致（对方拿到的是自己那张表被填好），
 * 后六列取已落库事实，缺事实写空而不是猜。
 */
class TrackingResultWorkbookServiceTest {

    private final TrackingResultWorkbookService service = new TrackingResultWorkbookService(null);

    /** 夹具用今天线上真单的事实：运单 JDVA46707982590 / 京东物流 / 4 件原切眼肉牛排。 */
    private static TrackingResultWorkbookService.ResultRow shippedRow() {
        return new TrackingResultWorkbookService.ResultRow(
                "SHIP-8057265EA22841F1BC210349C6AB945E", "202608250001", "ORD-E2C07DA3",
                "CAISHIXIAN", "2608250617646490", 1, "李",
                "EMG4418904462756", "子牧原切眼肉牛排150g*4", "150g*4", "件",
                new BigDecimal("4"), new BigDecimal("4"),
                "京东物流", "JDVA46707982590", "2026-08-25 15:34:43", null);
    }

    @Test
    void headerMatchesShippingListVerbatim() throws Exception {
        byte[] bytes = service.workbook(List.of(shippedRow()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < ProviderFileService.THIRD_PARTY_HEADERS.size(); i++) {
                assertThat(header.getCell(i).getStringCellValue())
                        .as("第 %d 列表头必须与发货清单一致", i)
                        .isEqualTo(ProviderFileService.THIRD_PARTY_HEADERS.get(i));
            }
        }
    }

    @Test
    void resultColumnsCarryBackfilledFacts() throws Exception {
        byte[] bytes = service.workbook(List.of(shippedRow()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> headers = ProviderFileService.THIRD_PARTY_HEADERS;
            Row row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(headers.indexOf("结果")).getStringCellValue()).isEqualTo("已发货");
            assertThat(row.getCell(headers.indexOf("实际发货数量")).getStringCellValue()).isEqualTo("4");
            assertThat(row.getCell(headers.indexOf("快递公司")).getStringCellValue()).isEqualTo("京东物流");
            assertThat(row.getCell(headers.indexOf("物流单号")).getStringCellValue()).isEqualTo("JDVA46707982590");
            assertThat(row.getCell(headers.indexOf("发货时间")).getStringCellValue())
                    .isEqualTo("2026-08-25 15:34:43");
        }
    }

    @Test
    void contactColumnsStayEmptyBecauseResultSheetIsNotAPiiCarrier() throws Exception {
        byte[] bytes = service.workbook(List.of(shippedRow()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> headers = ProviderFileService.THIRD_PARTY_HEADERS;
            Row row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(headers.indexOf("电话")).getStringCellValue()).isEmpty();
            assertThat(row.getCell(headers.indexOf("地址")).getStringCellValue()).isEmpty();
        }
    }

    @Test
    void missingTrackingLeavesResultBlankInsteadOfClaimingShipped() throws Exception {
        TrackingResultWorkbookService.ResultRow pending = new TrackingResultWorkbookService.ResultRow(
                "SHIP-X", "202608250002", "ORD-X", "CAISHIXIAN", "REF-X", 1, "王",
                "EMG-X", "商品", "规格", "件",
                new BigDecimal("2"), null, null, null, null, null);
        byte[] bytes = service.workbook(List.of(pending));
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            List<String> headers = ProviderFileService.THIRD_PARTY_HEADERS;
            Row row = workbook.getSheetAt(0).getRow(1);
            assertThat(row.getCell(headers.indexOf("结果")).getStringCellValue())
                    .as("没有运单就不得写「已发货」")
                    .isEmpty();
            assertThat(row.getCell(headers.indexOf("物流单号")).getStringCellValue()).isEmpty();
        }
    }

    @Test
    void emptyRowsRefuseToProduceAMisleadingBlankSheet() {
        assertThatThrownBy(() -> service.workbook(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有可回发的回填结果");
    }
}
