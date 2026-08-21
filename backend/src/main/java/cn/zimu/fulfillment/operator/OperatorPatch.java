package cn.zimu.fulfillment.operator;

import jakarta.validation.constraints.NotNull;

/**
 * 运营人员更新输入（Issue #89）：null = 不改动；wecom_userid 空串 = 显式清除绑定。
 * 业务规则统一由 {@link OperatorRules} 校验（422 字段级错误）。
 */
public record OperatorPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        String displayName,
        String responsibleTeam,
        String wecomUserid,
        Boolean active) {
}
