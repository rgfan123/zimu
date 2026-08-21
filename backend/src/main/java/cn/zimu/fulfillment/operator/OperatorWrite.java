package cn.zimu.fulfillment.operator;

import jakarta.validation.constraints.NotNull;

/** 运营人员创建输入（Issue #89）。业务规则统一由 {@link OperatorRules} 校验（422 字段级错误）。 */
public record OperatorWrite(
        String displayName,
        String responsibleTeam,
        String wecomUserid,
        Boolean active) {
}
