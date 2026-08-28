package cn.zimu.fulfillment.connector.caishixian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class CaishixianShipmentArtifactFactoryTest {

    @Test
    void renderKeepsOnlyTargetShipmentRowsAndFillsTheCapturedReturnColumns() throws Exception {
        byte[] original = workbook();

        byte[] rendered = CaishixianShipmentArtifactFactory.render(
                original,
                List.of(new CaishixianShipmentArtifactFactory.RowFill(
                        0, 3, "2", "JD", "JDVA123")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("main-target");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("sub-target");
            assertThat(sheet.getRow(1).getCell(17).getStringCellValue()).isEqualTo("2");
            assertThat(sheet.getRow(1).getCell(18).getStringCellValue()).isEqualTo("JD");
            assertThat(sheet.getRow(1).getCell(19).getStringCellValue()).isEqualTo("JDVA123");
            assertThat(sheet.getRow(1).getCell(21).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LIGHT_BLUE.getIndex());
            assertThat(sheet.getRow(0).getCell(0).getCellStyle().getFillPattern())
                    .isEqualTo(FillPatternType.SOLID_FOREGROUND);
            assertThat(sheet.getRow(1).getCell(17).getCellStyle().getFillForegroundColor())
                    .isEqualTo(IndexedColors.LIGHT_YELLOW.getIndex());
            assertThat(sheet.getColumnWidth(0)).isEqualTo(6_000);
        }
    }

    @Test
    void rejectsFormulaCellsInsteadOfCopyingExecutableWorkbookContent() throws Exception {
        byte[] original;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbook()));
                var output = new ByteArrayOutputStream()) {
            workbook.getSheetAt(0).getRow(2).getCell(16).setCellFormula("HYPERLINK(\"https://attacker.example\",\"x\")");
            workbook.write(output);
            original = output.toByteArray();
        }

        assertThatThrownBy(() -> CaishixianShipmentArtifactFactory.render(
                        original,
                        List.of(new CaishixianShipmentArtifactFactory.RowFill(
                                0, 3, "2", "JD", "JDVA123"))))
                .hasMessageContaining("公式");
    }

    @Test
    void rendersByteIdenticalArtifactForTheSameShipmentFacts() throws Exception {
        byte[] original = workbook();
        var fills = List.of(new CaishixianShipmentArtifactFactory.RowFill(
                0, 3, "2", "JD", "JDVA123"));

        byte[] first = CaishixianShipmentArtifactFactory.render(original, fills);
        Thread.sleep(2_200);
        byte[] second = CaishixianShipmentArtifactFactory.render(original, fills);

        assertThat(second).containsExactly(first);
        Map<String, ZipPart> parts = unzip(second);
        assertThat(parts.values())
                .extracting(ZipPart::modifiedAt)
                .containsOnly(LocalDateTime.of(2000, 1, 1, 0, 0));
        assertThat(parts.keySet()).containsExactlyElementsOf(parts.keySet().stream().sorted().toList());
        assertThat(new String(parts.get("docProps/core.xml").content(), StandardCharsets.UTF_8))
                .contains(
                        "<dcterms:created xsi:type=\"dcterms:W3CDTF\">"
                                + "2000-01-01T00:00:00Z</dcterms:created>")
                .contains(
                        "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">"
                                + "2000-01-01T00:00:00Z</dcterms:modified>");
    }

    @Test
    void rejectsExtraDuplicateOrReorderedTemplateColumns() throws Exception {
        List<byte[]> unsafe = List.of(
                workbookWithHeader(row -> row.createCell(22).setCellValue("额外列")),
                workbookWithHeader(row -> row.getCell(1).setCellValue("主订单编号")),
                workbookWithHeader(row -> {
                    String first = row.getCell(0).getStringCellValue();
                    row.getCell(0).setCellValue(row.getCell(1).getStringCellValue());
                    row.getCell(1).setCellValue(first);
                }));

        for (byte[] original : unsafe) {
            assertThatThrownBy(() -> CaishixianShipmentArtifactFactory.render(
                            original,
                            List.of(new CaishixianShipmentArtifactFactory.RowFill(
                                    0, 3, "2", "JD", "JDVA123"))))
                    .hasMessageContaining("精确 22 列");
        }
    }

    @Test
    void outputRowsNeverCopyCellsBeyondTheCapturedTwentyTwoColumns() throws Exception {
        byte[] original;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbook()));
                var output = new ByteArrayOutputStream()) {
            workbook.getSheetAt(0).getRow(2).createCell(22).setCellValue("不得上传的额外值");
            workbook.write(output);
            original = output.toByteArray();
        }

        byte[] rendered = CaishixianShipmentArtifactFactory.render(
                original,
                List.of(new CaishixianShipmentArtifactFactory.RowFill(
                        0, 3, "2", "JD", "JDVA123")));

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(rendered))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getLastCellNum()).isEqualTo((short) 22);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(22)).isNull();
        }
    }

    @Test
    void deterministicPackagingRejectsDuplicateZipEntries() throws Exception {
        byte[] duplicateArchive;
        try (var output = new ByteArrayOutputStream(); var zip = new ZipArchiveOutputStream(output)) {
            zip.putArchiveEntry(new ZipArchiveEntry("docProps/core.xml"));
            zip.write("first".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
            zip.putArchiveEntry(new ZipArchiveEntry("docProps/core.xml"));
            zip.write("second".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
            zip.finish();
            duplicateArchive = output.toByteArray();
        }

        assertThatThrownBy(() -> CaishixianShipmentArtifactFactory.normalizeOoxmlZip(duplicateArchive))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 entry");
    }

    @Test
    void renderFromValuesBuildsDeterministicTwentyTwoColumnTemplate() throws Exception {
        List<List<String>> lines = List.of(List.of(
                "main-1", "main-1-01", "CG-1", "20075684", "", "张三", "13800000000",
                "河南省", "郑州市", "金水区", "测试路 1 号", "ER1", "常温",
                "G-1", "羊小腿", "2", "尽快发", "2", "JD", "JDVA123", "0", ""));

        byte[] first = CaishixianShipmentArtifactFactory.renderFromValues(lines);
        byte[] second = CaishixianShipmentArtifactFactory.renderFromValues(lines);

        // 结构化分支的上传幂等哈希依赖字节级确定性
        assertThat(first).isEqualTo(second);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(first))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("主订单编号");
            assertThat(sheet.getRow(0).getCell(21).getStringCellValue()).isEqualTo("错误原因");
            assertThat((int) sheet.getRow(0).getLastCellNum()).isEqualTo(22);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("main-1");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEmpty(); // 站点编码已知缺失
            assertThat(sheet.getRow(1).getCell(17).getStringCellValue()).isEqualTo("2");
            assertThat(sheet.getRow(1).getCell(19).getStringCellValue()).isEqualTo("JDVA123");
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
        }
    }

    @Test
    void renderFromValuesRejectsWrongColumnCountInsteadOfGuessing() {
        assertThatThrownBy(() -> CaishixianShipmentArtifactFactory.renderFromValues(
                List.of(List.of("only", "four", "columns", "here"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> CaishixianShipmentArtifactFactory.renderFromValues(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] workbook() throws Exception {
        List<String> headers = List.of(
                "主订单编号", "子订单编号", "采购单号", "供应商编码", "站点编码", "收货人",
                "联系电话", "省", "市", "区", "详细地址", "物流要求编码", "物流要求名称",
                "商品编号", "商品名称", "下单数量", "订单备注", "发货数量", "物流公司代码",
                "物流单号", "vip订单标识", "错误原因");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("待发货订单");
            sheet.setColumnWidth(0, 6_000);
            var header = sheet.createRow(0);
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.LIGHT_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
                header.getCell(column).setCellStyle(headerStyle);
            }
            var dataStyle = workbook.createCellStyle();
            dataStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            dataStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            row(sheet, 1, "main-other", "sub-other", dataStyle);
            row(sheet, 2, "main-target", "sub-target", dataStyle);
            row(sheet, 3, "main-other-2", "sub-other-2", dataStyle);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] workbookWithHeader(Consumer<org.apache.poi.ss.usermodel.Row> mutation) throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(workbook()));
                var output = new ByteArrayOutputStream()) {
            mutation.accept(workbook.getSheetAt(0).getRow(0));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private Map<String, ZipPart> unzip(byte[] content) throws Exception {
        Map<String, ZipPart> parts = new LinkedHashMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                parts.put(entry.getName(), new ZipPart(input.readAllBytes(), entry.getTimeLocal()));
            }
        }
        return parts;
    }

    private void row(
            org.apache.poi.ss.usermodel.Sheet sheet,
            int index,
            String main,
            String sub,
            org.apache.poi.ss.usermodel.CellStyle style) {
        var row = sheet.createRow(index);
        for (int column = 0; column < 22; column++) {
            row.createCell(column).setCellValue("");
            row.getCell(column).setCellStyle(style);
        }
        row.getCell(0).setCellValue(main);
        row.getCell(1).setCellValue(sub);
        row.getCell(15).setCellValue("2");
    }

    private record ZipPart(byte[] content, LocalDateTime modifiedAt) {}
}
