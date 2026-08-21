package cn.zimu.fulfillment.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.operator.OperatorTeamResolution.OperatorResolutionMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #89: responsible_team → 运营人员/可推送 userid 解析 seam。
 *
 * <p>输入责任团队，返回 active 人员及可推送 userid；团队无人员、有人未绑定 userid 时返回
 * 明确结构化诊断（不静默过滤）；需要全员可推送的消费侧用 {@link OperatorResolver#requirePushable}。
 * 本 seam 只读、无推送副作用；「必须先与机器人有过会话」的外部门禁不在此 mock 验收，
 * 由调度者后续企微实测补证。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorResolverTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OperatorResolver resolver;

    @Autowired
    private InternalOperatorRepository operators;

    /** 同一 Testcontainers 容器在类内所有用例间共享：每例先清空登记簿，避免用例间数据污染。 */
    @BeforeEach
    void cleanRegistry() {
        operators.deleteAll();
    }

    @Test
    void resolveReturnsActiveMembersPushableUserIdsAndExplicitUnboundDiagnostics() {
        seed("张三", "ORDER_OPS", "zhangsan", true);
        seed("李四", "ORDER_OPS", "lisi", true);
        seed("王五", "ORDER_OPS", null, true);
        seed("已停用赵六", "ORDER_OPS", "zhaoliu", false);

        OperatorTeamResolution resolution = resolver.resolve("ORDER_OPS");

        assertThat(resolution.responsibleTeam()).isEqualTo("ORDER_OPS");
        // 只含 active 人员，按登记顺序（id 升序）稳定返回；停用人员不参与解析
        assertThat(resolution.members()).extracting(OperatorResolutionMember::displayName)
                .containsExactly("张三", "李四", "王五");
        assertThat(resolution.pushableUserIds()).containsExactly("zhangsan", "lisi");
        // 未绑定 userid 的人员显式列出，绝不静默过滤
        assertThat(resolution.unboundMemberNames()).containsExactly("王五");
        assertThat(resolution.status()).isEqualTo(OperatorResolutionStatus.PARTIALLY_BOUND);
        assertThat(resolution.pushable()).isFalse();
    }

    @Test
    void resolveReportsPushableWhenEveryActiveMemberIsBound() {
        seed("张三", "ORDER_OPS", "zhangsan", true);
        seed("李四", "ORDER_OPS", "lisi", true);

        OperatorTeamResolution resolution = resolver.resolve("ORDER_OPS");

        assertThat(resolution.status()).isEqualTo(OperatorResolutionStatus.PUSHABLE);
        assertThat(resolution.pushable()).isTrue();
        assertThat(resolution.pushableUserIds()).containsExactly("zhangsan", "lisi");
        assertThat(resolution.unboundMemberNames()).isEmpty();
    }

    @Test
    void resolveReportsAllUnboundWhenNobodyHasAUserid() {
        seed("张三", "CUSTOMER_OPS", null, true);
        seed("李四", "CUSTOMER_OPS", null, true);

        OperatorTeamResolution resolution = resolver.resolve("CUSTOMER_OPS");

        assertThat(resolution.status()).isEqualTo(OperatorResolutionStatus.ALL_UNBOUND);
        assertThat(resolution.unboundMemberNames()).containsExactly("张三", "李四");
        assertThat(resolution.pushableUserIds()).isEmpty();
        assertThat(resolution.pushable()).isFalse();
    }

    @Test
    void resolveReportsNoMembersForUnstaffedTeamWithoutThrowing() {
        OperatorTeamResolution resolution = resolver.resolve("SKU_OPS");

        assertThat(resolution.status()).isEqualTo(OperatorResolutionStatus.NO_MEMBERS);
        assertThat(resolution.members()).isEmpty();
        assertThat(resolution.pushableUserIds()).isEmpty();
        assertThat(resolution.unboundMemberNames()).isEmpty();
        assertThat(resolution.pushable()).isFalse();
    }

    @Test
    void resolveNormalizesTeamCaseAndWhitespaceAndRejectsBlankTeam() {
        seed("张三", "ORDER_OPS", "zhangsan", true);

        assertThat(resolver.resolve(" order_ops ").pushableUserIds()).containsExactly("zhangsan");
        assertThat(resolver.resolve("Order_Ops").pushableUserIds()).containsExactly("zhangsan");

        assertThatThrownBy(() -> resolver.resolve("  "))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getHttpStatus())
                .isEqualTo(422);
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getBusinessCode())
                .isEqualTo("OPERATOR_TEAM_REQUIRED");
    }

    @Test
    void requirePushableThrowsActionableErrorWhenNotFullyPushableAndDeactivationRecovers() {
        seed("张三", "ORDER_OPS", "zhangsan", true);
        seed("王五", "ORDER_OPS", null, true);

        assertThatThrownBy(() -> resolver.requirePushable("ORDER_OPS"))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException business = (BusinessException) exception;
                    assertThat(business.getHttpStatus()).isEqualTo(422);
                    assertThat(business.getBusinessCode()).isEqualTo("OPERATOR_TEAM_NOT_PUSHABLE");
                    // 明确可操作提示：团队、未绑定人员名单与「先与机器人打招呼」应对
                    assertThat(business.getMessage()).contains("ORDER_OPS", "王五", "打招呼");
                });

        // 停用未绑定人员后团队恢复可推送：只返回剩余 active 人员的 userid
        operators.findAll().stream()
                .filter(value -> "王五".equals(value.getDisplayName()))
                .forEach(value -> {
                    value.setActive(false);
                    operators.saveAndFlush(value);
                });

        OperatorTeamResolution pushable = resolver.requirePushable("ORDER_OPS");
        assertThat(pushable.pushable()).isTrue();
        assertThat(pushable.pushableUserIds()).containsExactly("zhangsan");
    }

    private InternalOperator seed(String displayName, String team, String userid, boolean active) {
        InternalOperator value = new InternalOperator();
        value.setDisplayName(displayName);
        value.setResponsibleTeam(team);
        value.setWecomUserid(userid);
        value.setActive(active);
        return operators.saveAndFlush(value);
    }
}
