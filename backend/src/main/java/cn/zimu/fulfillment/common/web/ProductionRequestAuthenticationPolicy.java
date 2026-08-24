package cn.zimu.fulfillment.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产认证策略（fail-closed）：任何未显式登记非默认规则的请求路径都要求已认证的业务操作人。
 *
 * <p>非默认规则是白名单，不是默认值——新增端点只有在确需不同认证级别时才在此登记并写明理由，否则默认按
 * {@link Requirement#BUSINESS_OPERATOR} 拒绝（普通 {@code /api} 业务命名空间同样由此兜底覆盖）。
 * 测试环境由 {@code test-fixtures} profile 下的 permissive 策略接管，本策略只服务于生产与
 * 生产认证测试（{@code production-auth-test}）。
 */
@Component
@Profile("!test-fixtures")
public final class ProductionRequestAuthenticationPolicy implements RequestAuthenticationPolicy {

    /** 非默认路由规则：路径前缀 + 该前缀要求的认证级别。 */
    private record RouteRule(String prefix, RequestAuthenticationPolicy.Requirement requirement) {}

    /**
     * 显式非默认路由规则。每条规则都必须写明理由；未登记的路径一律要求
     * {@link Requirement#BUSINESS_OPERATOR}。
     *
     * <p>匹配规则：带尾斜杠的前缀直接前缀匹配；不带尾斜杠的前缀要求路径段边界，
     * 避免近似路径误命中（边界从 fail-closed 侧从严）。
     */
    private static final List<RouteRule> ROUTE_RULES = List.of(
            // 内部服务对服务调用：要求服务名 + Bearer 令牌双重校验（见 RequestContextFilter），没有外部用户概念。
            new RouteRule("/internal/", Requirement.INTERNAL_SERVICE),
            // Demo 订单助手直连后端：浏览器仍可用已验证管理身份，服务端原型使用独立内部身份。
            new RouteRule(
                    "/demo/v1/extracted-orders",
                    Requirement.BUSINESS_OPERATOR_OR_INTERNAL_SERVICE),
            // 复核页原图：浏览器 <img> 无法携带 X-Operator 头，只保留可验证 Basic 凭据校验、
            // 豁免操作人一致性（只读媒体，无业务副作用）。
            new RouteRule("/api/v1/message-media/", Requirement.BUSINESS_CREDENTIALS_ONLY),
            // 健康检查探活：网关与编排器不带管理凭据，仅返回存活状态，无业务数据。
            new RouteRule("/healthz", Requirement.NONE),
            // actuator 只暴露 health,info（见 application.yml management.endpoints.web.exposure.include），无业务数据。
            new RouteRule("/actuator/", Requirement.NONE),
            // 企业微信回调：无法携带管理凭据，靠 msg_signature 自证身份（URL 验证与加密回调）。
            new RouteRule("/wecom/callbacks/", Requirement.NONE));

    @Override
    public Requirement requirementFor(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (RouteRule rule : ROUTE_RULES) {
            if (matches(uri, rule.prefix())) {
                return rule.requirement();
            }
        }
        return Requirement.BUSINESS_OPERATOR;
    }

    private static boolean matches(String uri, String prefix) {
        if (!uri.startsWith(prefix)) {
            return false;
        }
        return prefix.endsWith("/")
                || uri.length() == prefix.length()
                || uri.charAt(prefix.length()) == '/';
    }
}
