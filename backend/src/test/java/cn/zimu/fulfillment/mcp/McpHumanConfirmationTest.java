package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 人类确认闸共享校验器的语义矩阵。
 *
 * <p>钉三件事：字面值必须精确等于「确认」（仅允许首尾空白，全角空白也算空白）；
 * 任何近似值都拒（422 + 稳定码）；放行时把 {@code human_confirmation} 从入参里剥掉——
 * 同一动作确认两次不该派生出两个不同的下游请求，用户输入也不该顺着载荷流到幂等注册表。
 */
class McpHumanConfirmationTest {

    // ------------------------------------------------------------------
    // 放行：精确等值 + 首尾空白
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"确认", " 确认", "确认 ", "  确认  ", "\t确认\n", "　确认　"})
    void acceptsTheExactWordEvenWithSurroundingWhitespace(String input) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("order_id", "11");
        args.put(McpHumanConfirmation.PARAMETER, input);

        assertThat(McpHumanConfirmation.requireConfirmed(args)).containsExactly(Map.entry("order_id", "11"));
    }

    // ------------------------------------------------------------------
    // 拒绝矩阵：相近值、空、缺失、非字符串
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(
            strings = {
                "确认。",
                "确认!",
                "确认了",
                "已确认",
                "请确认",
                "确 认",
                "确认确认",
                "ok",
                "OK",
                "yes",
                "Y",
                "是",
                "同意",
                "confirm",
                "",
                "   ",
                "　"
            })
    void rejectsEveryNearMissWithTheStableUnprocessableCode(String input) {
        assertThatThrownBy(() -> McpHumanConfirmation.requireConfirmed(
                        Map.of("order_id", "11", McpHumanConfirmation.PARAMETER, input)))
                .isInstanceOf(BusinessException.class)
                .satisfies(failure -> {
                    BusinessException ex = (BusinessException) failure;
                    assertThat(ex.getBusinessCode()).isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
                    assertThat(ex.getHttpStatus()).isEqualTo(422);
                });
    }

    @Test
    void rejectsMissingNullAndNonStringValues() {
        // 缺参数
        assertThatThrownBy(() -> McpHumanConfirmation.requireConfirmed(Map.of("order_id", "11")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
        // 显式 null
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put(McpHumanConfirmation.PARAMETER, null);
        assertThatThrownBy(() -> McpHumanConfirmation.requireConfirmed(withNull))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
        // 整个入参为 null（协议面 arguments 缺省）
        assertThatThrownBy(() -> McpHumanConfirmation.requireConfirmed(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getBusinessCode())
                .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
        // 非字符串一律拒：布尔 true 不是「用户输入了确认二字」
        for (Object value : List.of(Boolean.TRUE, 1, 1.0, List.of("确认"), Map.of("value", "确认"))) {
            assertThatThrownBy(() -> McpHumanConfirmation.requireConfirmed(
                            Map.of(McpHumanConfirmation.PARAMETER, value)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getBusinessCode())
                    .isEqualTo("HUMAN_CONFIRMATION_REQUIRED");
        }
    }

    // ------------------------------------------------------------------
    // 剥离语义：用户输入不流向下游
    // ------------------------------------------------------------------

    @Test
    void strippedArgumentsKeepEverythingElseAndNeverLeakTheUserInput() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("order_id", "11");
        args.put(McpHumanConfirmation.PARAMETER, "确认");
        args.put("idempotency_key", "raw-approve-0001");

        Map<String, Object> stripped = McpHumanConfirmation.requireConfirmed(args);

        assertThat(stripped).doesNotContainKey(McpHumanConfirmation.PARAMETER);
        assertThat(stripped).containsEntry("order_id", "11");
        assertThat(stripped).containsEntry("idempotency_key", "raw-approve-0001");
        // 原入参不被就地修改（不可变纪律）
        assertThat(args).containsKey(McpHumanConfirmation.PARAMETER);
    }

    // ------------------------------------------------------------------
    // 错误消息与 schema 描述：把「回去向用户要确认二字」写死给模型看
    // ------------------------------------------------------------------

    @Test
    void errorMessageTellsTheAgentToGoBackAndAskTheUser() {
        assertThatThrownBy(() -> McpHumanConfirmation.requireConfirmed(Map.of()))
                .hasMessageContaining("确认")
                .hasMessageContaining(McpHumanConfirmation.PARAMETER)
                .hasMessageContaining("不得代填");
    }

    @Test
    void schemaPropertyIsAStringCarryingTheMandatoryChineseInstruction() {
        assertThat(McpHumanConfirmation.property().path("type").asText()).isEqualTo("string");
        assertThat(McpHumanConfirmation.property().path("description").asText())
                .contains("人类确认闸")
                .contains("复述")
                .contains("『确认』")
                .contains("原样传入")
                .contains("不得代填");
    }
}
