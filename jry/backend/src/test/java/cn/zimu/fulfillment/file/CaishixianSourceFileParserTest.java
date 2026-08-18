package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CaishixianSourceFileParserTest {

    @Test
    void currentCaishixianWorkbookFormatParsesEveryBusinessRow() throws Exception {
        byte[] workbook = Files.readAllBytes(Path.of(
                "..", "待发货订单-测试", "彩食鲜待发货订单.xlsx"));

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
