package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 来源回填文件命名：以原始文件名为基名，只允许追加后缀。
 *
 * <p>断言钉的是「原名主干原样保留」这个性质，不是某个拼出来的字符串——
 * 后缀形式以后可能调整，但基名不许被替换掉。用户的话是「文件名 你可以添加后缀
 * 但不能改名（除了给第三方的发货清单）」。
 */
class SourceReturnFileNamingTest {

    /** 生产库里的真实值：飞象是用户从平台手工导出再上传的，这个名字必须保住。 */
    private static final List<String> REAL_ORIGINAL_NAMES = List.of(
            "订单导出2026-08-28_09_57_54.csv",
            "caishixian-deliver-2026-08-28.xlsx",
            "dazhe-wecom.xlsx");

    @Test
    void keepsTheOriginalStemForEveryRealWorldName() {
        for (String original : REAL_ORIGINAL_NAMES) {
            String stem = original.substring(0, original.lastIndexOf('.'));
            String result = SourceReturnFileNaming.fileName(original, "彩食鲜", 1, ".xlsx");
            assertThat(result)
                    .withFailMessage("原名主干必须原样出现在结果里: original=%s result=%s", original, result)
                    .contains(stem);
        }
    }

    @Test
    void appendsOnlyASuffixAndNeverReplacesTheStem() {
        String result = SourceReturnFileNaming.fileName("订单导出2026-08-28_09_57_54.csv", "飞象", 1, ".csv");
        // 结果必须以原名主干开头：后缀只能追加在后面，不能把基名换掉。
        assertThat(result).startsWith("订单导出2026-08-28_09_57_54");
        assertThat(result).endsWith(".csv");
    }

    @Test
    void usesTheActualArtifactExtensionWhenTheOriginalDisagrees() {
        // 原名是 .xlsx，实际产物是 CSV（飞象 v2）——以产物为准，主干仍保留。
        String result = SourceReturnFileNaming.fileName("订单导出.xlsx", "飞象", 1, ".csv");
        assertThat(result).contains("订单导出");
        assertThat(result).endsWith(".csv");
        assertThat(result).doesNotContain(".xlsx");
    }

    @Test
    void differentVersionsOfTheSameBatchDoNotCollide() {
        String first = SourceReturnFileNaming.fileName("订单导出.xlsx", "彩食鲜", 1, ".xlsx");
        String second = SourceReturnFileNaming.fileName("订单导出.xlsx", "彩食鲜", 2, ".xlsx");
        assertThat(first).isNotEqualTo(second);
        assertThat(second).contains("订单导出");
    }

    @Test
    void fallsBackToTheChannelNameInsteadOfFailingWhenTheOriginalIsUnusable() {
        // 原名缺失/空白不能抛异常挡住下载。
        for (String unusable : new String[] {null, "", "   "}) {
            String result = SourceReturnFileNaming.fileName(unusable, "彩食鲜", 1, ".xlsx");
            assertThat(result).startsWith("彩食鲜");
            assertThat(result).endsWith(".xlsx");
        }
    }

    @Test
    void stripsCharactersThatWouldBreakHeadersOrMultipartBoundaries() {
        // 这个名字会进 Content-Disposition，也会成为 multipart 的 filename，
        // 其中有的构造方式是裸字符串拼接，还会被 resolve 成临时文件路径。
        String result = SourceReturnFileNaming.fileName(
                "../../etc/pa\"ss;wd\r\n订单.xlsx", "彩食鲜", 1, ".xlsx");
        assertThat(result)
                .doesNotContain("/")
                .doesNotContain("\\")
                .doesNotContain("\"")
                .doesNotContain(";")
                .doesNotContain("\r")
                .doesNotContain("\n");
        // 清理不该顺手把可读部分也丢掉。
        assertThat(result).contains("订单");
    }

    @Test
    void keepsTheNameBoundedForAbsurdlyLongOriginals() {
        String longName = "订".repeat(500) + ".xlsx";
        String result = SourceReturnFileNaming.fileName(longName, "彩食鲜", 1, ".xlsx");
        assertThat(result.length()).isLessThan(200);
        assertThat(result).endsWith(".xlsx");
    }
}
