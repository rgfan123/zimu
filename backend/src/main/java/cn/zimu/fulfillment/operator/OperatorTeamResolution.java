package cn.zimu.fulfillment.operator;

import java.util.List;

/**
 * 责任团队解析结果（Issue #89）：active 人员、可推送 userid 与未绑定人员的显式诊断。
 *
 * <p>绝不静默过滤：任何未绑定 userid 的成员都出现在 {@code unboundMemberNames}，
 * {@code pushable=false}（PUSHABLE 以外状态均不可全员推送）；空团队为结构化
 * {@link OperatorResolutionStatus#NO_MEMBERS}，不是异常。
 */
public record OperatorTeamResolution(
        String responsibleTeam,
        List<OperatorResolutionMember> members,
        List<String> pushableUserIds,
        List<String> unboundMemberNames,
        OperatorResolutionStatus status,
        boolean pushable) {

    /** 单个 active 成员：displayName + 可空 wecomUserid。 */
    public record OperatorResolutionMember(String displayName, String wecomUserid) {}
}
