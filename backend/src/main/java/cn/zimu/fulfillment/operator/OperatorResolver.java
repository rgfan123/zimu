package cn.zimu.fulfillment.operator;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 责任团队 → 运营人员/可推送企微 userid 解析 seam（Issue #89）。
 *
 * <p>输入 {@code responsible_team}，返回 active 人员及可推送 userid；团队无人员、或有人
 * 未绑定 userid 时返回明确结构化诊断（{@link OperatorTeamResolution}），绝不静默过滤。
 * 每次调用实时读库（无缓存），登记/修改/停用后下一次解析立即生效。
 *
 * <p>需要「全员可推送」的消费侧用 {@link #requirePushable}：不满足时抛 422
 * OPERATOR_TEAM_NOT_PUSHABLE，消息含团队、未绑定人员名单与运营应对（首次使用前先与
 * 企微机器人打招呼）。「必须先与机器人有过会话」是待真实实测的外部门禁，本 seam 不 mock
 * 验收：它只保证 userid 已登记；真实可达性由调度者后续企微测试补证。
 */
@Component
public class OperatorResolver {

    private final InternalOperatorRepository operators;

    OperatorResolver(InternalOperatorRepository operators) {
        this.operators = operators;
    }

    /** 按责任团队解析：结构化结果，含未绑定人员显式诊断；空白团队抛 422。 */
    public OperatorTeamResolution resolve(String responsibleTeam) {
        String team = OperatorRules.requireTeamForResolution(responsibleTeam);
        List<InternalOperator> members =
                operators.findByResponsibleTeamAndActiveTrueOrderByIdAsc(team);
        List<OperatorTeamResolution.OperatorResolutionMember> memberViews = members.stream()
                .map(value -> new OperatorTeamResolution.OperatorResolutionMember(
                        value.getDisplayName(), value.getWecomUserid()))
                .toList();
        List<String> pushableUserIds = members.stream()
                .map(InternalOperator::getWecomUserid)
                .filter(userid -> userid != null)
                .toList();
        List<String> unboundMemberNames = members.stream()
                .filter(value -> value.getWecomUserid() == null)
                .map(InternalOperator::getDisplayName)
                .toList();
        OperatorResolutionStatus status;
        if (members.isEmpty()) {
            status = OperatorResolutionStatus.NO_MEMBERS;
        } else if (unboundMemberNames.isEmpty()) {
            status = OperatorResolutionStatus.PUSHABLE;
        } else if (pushableUserIds.isEmpty()) {
            status = OperatorResolutionStatus.ALL_UNBOUND;
        } else {
            status = OperatorResolutionStatus.PARTIALLY_BOUND;
        }
        return new OperatorTeamResolution(
                team,
                memberViews,
                pushableUserIds,
                unboundMemberNames,
                status,
                status == OperatorResolutionStatus.PUSHABLE);
    }

    /**
     * 需要全员可推送时的 fail-closed 入口：不可推送时抛 422（含未绑定名单与运营应对），
     * 可推送时直接返回结构化结果。
     */
    public OperatorTeamResolution requirePushable(String responsibleTeam) {
        OperatorTeamResolution resolution = resolve(responsibleTeam);
        if (!resolution.pushable()) {
            throw BusinessException.unprocessable(
                    OperatorRules.TEAM_NOT_PUSHABLE_ERROR_CODE, notPushableMessage(resolution));
        }
        return resolution;
    }

    private static String notPushableMessage(OperatorTeamResolution resolution) {
        if (resolution.members().isEmpty()) {
            return "责任团队 " + resolution.responsibleTeam()
                    + " 暂无 active 运营人员，无法推送；请先在系统管理 → 运营人员登记人员并绑定企微 userid";
        }
        return "责任团队 " + resolution.responsibleTeam() + " 有 "
                + resolution.unboundMemberNames().size()
                + " 人未绑定企微 userid：" + String.join("、", resolution.unboundMemberNames())
                + "。需要推送时不会静默跳过：请先完成绑定，并要求首次使用前先与企微机器人打招呼"
                + "（@机器人 发一条消息）";
    }
}
