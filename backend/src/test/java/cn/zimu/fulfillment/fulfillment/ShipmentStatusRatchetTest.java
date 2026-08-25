package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 棘轮守门（ADR 0012）：已收编到 {@link ShipmentStatus} 的文件不得再出现发货批次状态的
 * Java 字面量比较，清单**只增不减**——迁移一个文件就把它加进 {@code MIGRATED}。
 *
 * <p>只检查 Java 代码字面量，text block 内的 SQL 被跳过：SQL 内联判定
 * （{@code shipment_status IN ('SHIPPED','DELIVERED')}）尚未收编，是票 02 明确的
 * 后续范围，不在本轮阻断。运单导入文件「结果」列的 SHIPPED/PARTIAL/FAILED 是另一套
 * 词汇表，相关文件不得加入本清单。
 */
class ShipmentStatusRatchetTest {

    /** 已收编文件（相对 backend/）。只增不减。 */
    private static final List<String> MIGRATED = List.of(
            "src/main/java/cn/zimu/fulfillment/connector/sync/SourceSyncFactsReader.java",
            "src/main/java/cn/zimu/fulfillment/recon/OutboundReconService.java",
            "src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdTrackingBackfillService.java",
            "src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdOutboundPreparer.java");

    private static final List<String> FORBIDDEN = List.of(
            "\"CREATED\"", "\"SHIPPED\"", "\"FAILED\"", "\"DELIVERED\"");

    @Test
    void migratedFilesAskTheModuleInsteadOfComparingStatusLiterals() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (String relative : MIGRATED) {
            Path file = resolve(relative);
            if (file == null) {
                continue;
            }
            checked++;
            int lineNumber = 0;
            boolean insideTextBlock = false;
            for (String line : Files.readString(file, StandardCharsets.UTF_8).lines().toList()) {
                lineNumber++;
                if (countTextBlockDelimiters(line) % 2 == 1) {
                    insideTextBlock = !insideTextBlock;
                    continue;
                }
                if (insideTextBlock) {
                    continue;
                }
                for (String literal : FORBIDDEN) {
                    if (line.contains(literal)) {
                        violations.add(relative + ":" + lineNumber + " → " + line.trim());
                    }
                }
            }
        }
        Assumptions.assumeTrue(checked > 0, "未找到 backend 源码目录（非标准运行方式），跳过");

        assertThat(violations)
                .as("已收编文件必须调用 ShipmentStatus 的业务问题方法，不得再写状态字面量")
                .isEmpty();
    }

    /** 清单只增不减：防止有人为了让守门通过而把文件从清单里摘掉。 */
    @Test
    void migratedListNeverShrinks() {
        assertThat(MIGRATED)
                .hasSizeGreaterThanOrEqualTo(4)
                .contains("src/main/java/cn/zimu/fulfillment/connector/sync/SourceSyncFactsReader.java");
    }

    private static int countTextBlockDelimiters(String line) {
        int count = 0;
        int index = line.indexOf("\"\"\"");
        while (index >= 0) {
            count++;
            index = line.indexOf("\"\"\"", index + 3);
        }
        return count;
    }

    private static Path resolve(String relative) {
        String userDir = System.getProperty("user.dir", ".");
        for (String prefix : new String[] {"", "backend/", "../backend/"}) {
            Path path = Path.of(userDir, prefix + relative).normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }
}
