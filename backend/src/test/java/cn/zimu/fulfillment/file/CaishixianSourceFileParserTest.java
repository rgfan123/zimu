package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CaishixianSourceFileParserTest {

    private static final Path SAMPLE = Path.of("..", "待发货订单-测试", "彩食鲜待发货订单.xlsx");

    @Test
    void currentCaishixianWorkbookFormatParsesEveryBusinessRow() throws Exception {
        assumeTrue(
                Files.isRegularFile(SAMPLE),
                () -> "缺少真实样表，跳过本用例：需要「彩食鲜待发货订单.xlsx」，"
                        + "放到仓库外相对路径 ../待发货订单-测试/（即 jry/待发货订单-测试/，"
                        + "相对于 jry/backend 的 Maven 工作目录）。样表来自供应商真实业务数据，不进仓库。");

        byte[] workbook = Files.readAllBytes(SAMPLE);

        ParsedSourceFile parsed = new SourceFileParser().parse(workbook);

        assertThat(parsed.sourceChannel()).isEqualTo(SourceChannel.CAISHIXIAN);
        assertThat(parsed.rows()).hasSize(6).allSatisfy(row -> {
            assertThat(row.valid()).isTrue();
            assertThat(row.sourceOrderRef()).isNotBlank();
            assertThat(row.sourceSkuRef()).isNotBlank();
        });
        assertThat(parsed.rows().stream().map(ParsedSourceRow::sourceOrderRef).distinct())
                .hasSize(5);
        assertThat(parsed.rows().stream().map(ParsedSourceRow::sourceSkuRef))
                .containsExactly("2047705", "2047848", "2066622", "2066578", "2047778", "2047705");
    }
}
