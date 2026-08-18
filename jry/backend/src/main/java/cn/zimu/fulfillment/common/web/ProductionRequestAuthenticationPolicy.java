package cn.zimu.fulfillment.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production policy has no runtime off switch: direct business and internal calls are authenticated. */
@Component
@Profile("!test-fixtures")
public final class ProductionRequestAuthenticationPolicy implements RequestAuthenticationPolicy {

    @Override
    public Requirement requirementFor(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/internal/")) {
            return Requirement.INTERNAL_SERVICE;
        }
        // 复核页原图：浏览器 <img> 无法携带 X-Operator 头，保留 Basic Auth 凭据校验、豁免操作人一致性。
        if (request.getRequestURI().startsWith("/api/v1/message-media/")) {
            return Requirement.BUSINESS_CREDENTIALS_ONLY;
        }
        if (request.getRequestURI().startsWith("/api/")) {
            return Requirement.BUSINESS_OPERATOR;
        }
        return Requirement.NONE;
    }
}
