package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderSkuMappingReferenceCountContractTest {

    private final ProviderSkuMappingReferenceService service =
            new ProviderSkuMappingReferenceService(new SourceFileParser());

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.000"})
    void directPackagingMultiplierIsNormalizedToAnInteger(String rawMultiplier) throws Exception {
        Map<String, Object> preview = service.preview(
                directReference(rawMultiplier),
                caishixianSource("直接映射商品"));

        Map<?, ?> row = firstRow(preview);
        assertThat(row.get("match_status")).isEqualTo("MATCHED");
        assertThat(row.get("quantity_multiplier")).isEqualTo(3).isInstanceOf(Integer.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5", "-1", "2147483648"})
    void directPackagingMultiplierRejectsFractionalNegativeAndOverflowValues(String rawMultiplier)
            throws Exception {
        Map<String, Object> preview = service.preview(
                directReference(rawMultiplier),
                caishixianSource("直接映射商品"));

        assertThat(firstRow(preview).get("match_status")).isEqualTo("NEED_REVIEW");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.000"})
    void bundleComponentCountIsNormalizedToAnInteger(String rawQuantity) throws Exception {
        Map<String, Object> preview = service.preview(
                bundleReference(rawQuantity),
                caishixianSource("整数礼包"));

        Map<?, ?> row = firstRow(preview);
        assertThat(row.get("match_status")).isEqualTo("MATCHED");
        List<?> components = (List<?>) row.get("bundle_components");
        assertThat(components).singleElement().satisfies(component -> {
            Object quantity = ((Map<?, ?>) component).get("quantity_per_bundle");
            assertThat(quantity).isEqualTo(3).isInstanceOf(Integer.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5", "-1", "2147483648"})
    void bundleComponentCountRejectsFractionalNegativeAndOverflowValues(String rawQuantity)
            throws Exception {
        Map<String, Object> preview = service.preview(
                bundleReference(rawQuantity),
                caishixianSource("整数礼包"));

        assertThat(firstRow(preview).get("match_status")).isEqualTo("NEED_REVIEW");
    }

    @Test
    void numericMultiplierCannotUseDisplayFormatToHideAFraction() throws Exception {
        Map<String, Object> preview = service.preview(
                directNumericReference(2.5, "0"),
                caishixianSource("直接映射商品"));

        assertThat(firstRow(preview).get("match_status")).isEqualTo("NEED_REVIEW");
    }

    private Map<?, ?> firstRow(Map<String, Object> preview) {
        return (Map<?, ?>) ((List<?>) preview.get("rows")).getFirst();
    }

    private byte[] directReference(String multiplier) throws Exception {
        try (var workbook = referenceWorkbook(); var output = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).createRow(1);
            row.createCell(0).setCellValue("直接映射商品");
            row.createCell(1).setCellValue(multiplier);
            row.createCell(4).setCellValue("京东羊棒骨");
            row.createCell(5).setCellValue("JD-SKU-COUNT-1");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] directNumericReference(double multiplier, String format) throws Exception {
        try (var workbook = referenceWorkbook(); var output = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).createRow(1);
            row.createCell(0).setCellValue("直接映射商品");
            var count = row.createCell(1);
            count.setCellValue(multiplier);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            count.setCellStyle(style);
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(count)).isEqualTo("3");
            row.createCell(4).setCellValue("京东羊棒骨");
            row.createCell(5).setCellValue("JD-SKU-COUNT-1");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] bundleReference(String quantity) throws Exception {
        try (var workbook = referenceWorkbook(); var output = new ByteArrayOutputStream()) {
            var catalog = workbook.getSheetAt(0).createRow(1);
            catalog.createCell(4).setCellValue("京东羊棒骨");
            catalog.createCell(5).setCellValue("JD-SKU-COUNT-1");
            var component = workbook.getSheetAt(1).createRow(0);
            component.createCell(0).setCellValue("整数礼包");
            component.createCell(1).setCellValue("JD-SKU-COUNT-1");
            component.createCell(2).setCellValue(quantity);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private XSSFWorkbook referenceWorkbook() {
        var workbook = new XSSFWorkbook();
        workbook.createSheet("来源映射");
        workbook.createSheet("礼包BOM");
        workbook.createSheet("京东目录");
        workbook.createSheet("聚福宝补充");
        return workbook;
    }

    private byte[] caishixianSource(String productName) throws Exception {
        List<String> headers = List.of(
                "主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "商品名称",
                "下单数量", "收货人", "联系电话", "省", "市", "区", "详细地址", "规格", "单位");
        List<String> values = List.of(
                "CSX-REF-1", "CSX-REF-LINE-1", "SUP-1", "SITE-1", "CSX-SKU-1", productName,
                "1", "张三", "13800000000", "河南省", "郑州市", "金水区", "测试路1号", "500g", "份");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("待发货订单");
            var header = sheet.createRow(0);
            var row = sheet.createRow(1);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
                row.createCell(index).setCellValue(values.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
