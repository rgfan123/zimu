package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ZhonghuiSourceFileParserTest {

    @Test
    void currentZhonghuiWorkbookFormatParsesEveryBusinessRow() throws Exception {
        byte[] workbook = Files.readAllBytes(Path.of(
                "..", "待发货订单-测试", "中汇待发货订单.xlsx"));

        ParsedSourceFile parsed = new SourceFileParser().parse(workbook);

        assertThat(parsed.sourceChannel()).isEqualTo(SourceChannel.ZHONGHUI);
        assertThat(parsed.rows()).hasSize(5).allSatisfy(row -> {
            assertThat(row.valid()).isTrue();
            assertThat(row.sourceOrderRef()).isNotBlank();
            assertThat(row.sourceSkuRef()).isNotBlank();
            assertThat(row.receiverName()).isNotBlank();
            assertThat(row.receiverPhone()).isNotBlank();
            assertThat(row.receiverAddress()).isNotBlank();
        });
        assertThat(parsed.rows().stream().map(ParsedSourceRow::sourceOrderRef).distinct())
                .containsExactlyInAnyOrder("S260817313276-13", "S260817376559-1", "S260817586318-1");
        assertThat(parsed.rows().stream().map(ParsedSourceRow::sourceSkuRef))
                .containsExactly("60043823", "60043825", "60043832", "60043849", "60043845");
        assertThat(parsed.rows().stream().map(ParsedSourceRow::quantity))
                .containsExactly("4", "1", "1", "1", "1");
        assertThat(parsed.rows().stream().map(ParsedSourceRow::receiverName))
                .containsExactly("李花花", "阿敏", "周老师", "周老师", "周老师");
    }
}
