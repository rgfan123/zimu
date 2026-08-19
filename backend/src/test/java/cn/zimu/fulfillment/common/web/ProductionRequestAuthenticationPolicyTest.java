package cn.zimu.fulfillment.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 生产认证策略 fail-closed 单元测试：不依赖 Spring 容器，直接对策略本身断言。
 *
 * <p>意义：以后有人新增端点却忘了在豁免白名单登记时，未登记的路径默认要求已认证操作人，
 * 本测试直接断言 requirementFor 的兜底，任何把新端点默认放行的改动都会被拦住。
 */
class ProductionRequestAuthenticationPolicyTest {

    private final ProductionRequestAuthenticationPolicy policy = new ProductionRequestAuthenticationPolicy();

    @Test
    void unknownPathsNotInTheWhitelistFallBackToBusinessOperator() {
        // 一个全新前缀（没有任何控制器）：默认要求已认证业务操作人，而不是放行。
        assertThat(requirementFor("/v1/future-endpoint/things"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR);
        // 新加的公共写端点前缀：同样默认要求业务操作人。
        assertThat(requirementFor("/new-public-prefix/v1/items"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR);
        // 普通 /api 业务命名空间：不是豁免，由 BUSINESS_OPERATOR 兜底覆盖。
        assertThat(requirementFor("/api/v1/customers"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR);
    }

    @Test
    void prefixMatchingRespectsPathSegmentBoundaries() {
        // /demo/v1 豁免不带尾斜杠：路径段边界外的近似前缀不能被误豁免（fail-closed 侧从严）。
        assertThat(requirementFor("/demo/v10"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR);
        assertThat(requirementFor("/demo/v1evil"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR);
        // /wecom/callbacks/ 豁免带尾斜杠：裸 /wecom/callbacks 不命中，同样按业务操作人要求。
        assertThat(requirementFor("/wecom/callbacks"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR);
    }

    @Test
    void whitelistedPathsKeepTheirDocumentedRequirements() {
        // 内部服务命名空间：要求服务身份。
        assertThat(requirementFor("/internal/v1/orders"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.INTERNAL_SERVICE);
        assertThat(requirementFor("/internal/v1/procurement/tickets"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.INTERNAL_SERVICE);
        // 复核页原图：只要求可验证 Basic 凭据，豁免操作人一致性。
        assertThat(requirementFor("/api/v1/message-media/1/content"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.BUSINESS_CREDENTIALS_ONLY);
        // 健康检查与 actuator（仅 health,info 暴露）：无业务数据，放行探活。
        assertThat(requirementFor("/healthz")).isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        assertThat(requirementFor("/actuator/health")).isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        assertThat(requirementFor("/actuator/info")).isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        // 企业微信回调：靠 msg_signature 自证身份。
        assertThat(requirementFor("/wecom/callbacks/abc123"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        // Demo 入口（含写端点）：保留既有无认证行为（见工单 05 Resolution 的残留风险）。
        assertThat(requirementFor("/demo/v1")).isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        assertThat(requirementFor("/demo/v1/scenarios"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        assertThat(requirementFor("/demo/v1/extracted-orders"))
                .isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
        assertThat(requirementFor("/demo/v1/runs/1")).isEqualTo(RequestAuthenticationPolicy.Requirement.NONE);
    }

    private RequestAuthenticationPolicy.Requirement requirementFor(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return policy.requirementFor(request);
    }
}
