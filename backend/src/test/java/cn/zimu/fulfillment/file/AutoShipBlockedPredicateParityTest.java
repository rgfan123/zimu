package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.schedule.AutoShipBlockedPredicate;
import org.junit.jupiter.api.Test;

/**
 * 自动发货的阻断判据必须与确认闸门逐字符一致。
 *
 * <p><b>这个测试为什么长在 {@code file} 测试包里</b>：
 * {@link SourceBatchConfirmReadiness#blockedPredicate} 是包私有的，只有同包才读得到。
 * 而自动发货住在 {@code connector.schedule}，编译期引用不到它，只能镜像一份
 * （{@link AutoShipBlockedPredicate}）。镜像一旦与原件分叉，后果是自动发货把「本该
 * 拦下来交给人」的批次判成完全就绪、直接确认发货——花真钱，且没有任何人在看。
 *
 * <p>所以这里不比较「语义等价」而是比较**字符串本身**：任何一处改动（新增良性豁免码、
 * 改别名拼法、调整括号）都会让本测试立刻变红，改动者当场就知道还有第二处要跟着改。
 * 这是本次特性里唯一能在编译/测试期发现分叉的机制。
 *
 * <p>本类只读，不修改 {@code file} 包里的任何既有文件。
 */
class AutoShipBlockedPredicateParityTest {

    @Test
    void mirrorMatchesTheConfirmGateWithoutAlias() {
        assertThat(AutoShipBlockedPredicate.blockedPredicate(""))
                .isEqualTo(SourceBatchConfirmReadiness.blockedPredicate(""));
    }

    @Test
    void mirrorMatchesTheConfirmGateWithTableAlias() {
        // rir 是自动发货 SQL 实际使用的别名；确认闸门的列表查询也用它。
        assertThat(AutoShipBlockedPredicate.blockedPredicate("rir"))
                .isEqualTo(SourceBatchConfirmReadiness.blockedPredicate("rir"));
    }

    @Test
    void mirrorMatchesForNullAndBlankAliasToo() {
        assertThat(AutoShipBlockedPredicate.blockedPredicate(null))
                .isEqualTo(SourceBatchConfirmReadiness.blockedPredicate(null));
        assertThat(AutoShipBlockedPredicate.blockedPredicate("  "))
                .isEqualTo(SourceBatchConfirmReadiness.blockedPredicate("  "));
    }

    @Test
    void theBenignExemptionSetIsPartOfTheContract() {
        // 良性豁免码写死在断言里：新增一个豁免码等于放宽自动发货的门槛，
        // 必须是显式动作，不能靠「两边都改了吧」的默契。
        assertThat(AutoShipBlockedPredicate.blockedPredicate("rir"))
                .contains("'ORDER_ALREADY_EXISTS'")
                .contains("'SOURCE_ORDER_ALREADY_FULFILLED'")
                .contains("rir.status<>'ACCEPTED'")
                .contains("rir.order_line_id IS NULL");
    }
}
