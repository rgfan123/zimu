package cn.zimu.fulfillment.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** Selects the server-authenticated identity required at each HTTP namespace. */
public interface RequestAuthenticationPolicy {

    enum Requirement {
        NONE,
        /** 仅要求服务器可验证的 Basic 凭据；不要求 X-Operator 与凭据一致（供浏览器 <img> 等无法携带自定义头的只读媒体请求）。 */
        BUSINESS_CREDENTIALS_ONLY,
        BUSINESS_OPERATOR,
        INTERNAL_SERVICE
    }

    Requirement requirementFor(HttpServletRequest request);
}
