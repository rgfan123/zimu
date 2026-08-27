package cn.zimu.fulfillment.common.web;

import cn.zimu.fulfillment.common.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析请求关联信息，并复验管理 Basic Auth 或内部服务 Bearer 凭据。
 * 仅复验成功时才产生 authenticatedOperator；客户端自报 X-Operator 不是授权凭证。
 */
@Component("httpRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    private final String businessOperator;
    private final byte[] expectedBusinessCredentials;
    private final byte[] expectedGatewayAssertionToken;
    private final String internalServiceName;
    private final byte[] expectedInternalToken;
    private final RequestAuthenticationPolicy authenticationPolicy;
    private final ObjectMapper objectMapper;

    public RequestContextFilter(
            RequestAuthenticationPolicy authenticationPolicy,
            ObjectMapper objectMapper,
            @Value("${app.gateway.basic-auth.username:}") String username,
            @Value("${app.gateway.basic-auth.password:}") String password,
            @Value("${app.gateway.identity-assertion.token:}") String gatewayAssertionToken,
            @Value("${app.internal-auth.service-name:}") String internalServiceName,
            @Value("${app.internal-auth.bearer-token:}") String internalToken) {
        this.authenticationPolicy = authenticationPolicy;
        this.objectMapper = objectMapper;
        this.businessOperator = hasText(username) && hasText(password) ? username : null;
        this.expectedBusinessCredentials = businessOperator == null
                ? null
                : (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        this.expectedGatewayAssertionToken = hasText(gatewayAssertionToken)
                ? gatewayAssertionToken.getBytes(StandardCharsets.UTF_8)
                : null;
        this.internalServiceName = hasText(internalServiceName) && hasText(internalToken)
                ? internalServiceName
                : null;
        this.expectedInternalToken = this.internalServiceName == null
                ? null
                : internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID().toString().replace("-", "");
        } else if (requestId.length() > 128) {
            requestId = requestId.substring(0, 128);
        }
        String operator = request.getHeader("X-Operator");
        RequestAuthenticationPolicy.Requirement requirement = authenticationPolicy.requirementFor(request);
        AuthenticatedIdentity identity = authenticate(request, requirement);
        String verifiedOperator = identity == null ? null : identity.operator();
        RequestContext.set(new RequestContext(
                requestId,
                requestId,
                operator,
                verifiedOperator,
                identity == null ? AuthenticationKind.NONE : identity.kind()));
        response.setHeader("X-Request-Id", requestId);
        try {
            if (requirement == RequestAuthenticationPolicy.Requirement.BUSINESS_CREDENTIALS_ONLY) {
                if (verifiedOperator == null) {
                    rejectUnauthenticated(response, requirement, requestId);
                    return;
                }
            } else if (requirement != RequestAuthenticationPolicy.Requirement.NONE
                    && (verifiedOperator == null || !verifiedOperator.equals(operator))) {
                rejectUnauthenticated(response, requirement, requestId);
                return;
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private void rejectUnauthenticated(
            HttpServletResponse response,
            RequestAuthenticationPolicy.Requirement requirement,
            String requestId) throws IOException {
        boolean internal = requirement == RequestAuthenticationPolicy.Requirement.INTERNAL_SERVICE;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ApiError(
                internal ? "AUTHENTICATED_INTERNAL_SERVICE_REQUIRED" : "AUTHENTICATED_OPERATOR_REQUIRED",
                internal ? "内部写操作需要已认证的服务身份" : "业务写操作需要已认证的网关操作人",
                HttpServletResponse.SC_FORBIDDEN,
                requestId,
                requestId,
                List.of(),
                Map.of()));
    }

    private AuthenticatedIdentity authenticate(
            HttpServletRequest request, RequestAuthenticationPolicy.Requirement requirement) {
        String authorization = request.getHeader("Authorization");
        if (requirement == RequestAuthenticationPolicy.Requirement.INTERNAL_SERVICE) {
            return identity(authenticateInternalService(authorization), AuthenticationKind.INTERNAL_SERVICE);
        }
        if (requirement == RequestAuthenticationPolicy.Requirement.BUSINESS_OPERATOR_OR_INTERNAL_SERVICE) {
            String business = authenticateGatewayOperator(request);
            if (business != null) {
                return identity(business, AuthenticationKind.GATEWAY_ASSERTION);
            }
            business = authenticateBusinessOperator(authorization);
            if (business != null) {
                return identity(business, AuthenticationKind.SHARED_BASIC);
            }
            return identity(authenticateInternalService(authorization), AuthenticationKind.INTERNAL_SERVICE);
        }
        String gateway = authenticateGatewayOperator(request);
        return gateway != null
                ? identity(gateway, AuthenticationKind.GATEWAY_ASSERTION)
                : identity(authenticateBusinessOperator(authorization), AuthenticationKind.SHARED_BASIC);
    }

    private String authenticateGatewayOperator(HttpServletRequest request) {
        if (expectedGatewayAssertionToken == null) {
            return null;
        }
        String operator = request.getHeader("X-Authenticated-Operator");
        String suppliedToken = request.getHeader("X-Gateway-Assertion");
        if (!hasText(operator)
                || operator.length() > 128
                || !operator.matches("^[A-Za-z0-9._@-]+$")
                || !hasText(suppliedToken)) {
            return null;
        }
        byte[] supplied = suppliedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedGatewayAssertionToken, supplied) ? operator : null;
    }

    private String authenticateBusinessOperator(String authorization) {
        if (expectedBusinessCredentials == null
                || authorization == null
                || !authorization.regionMatches(true, 0, "Basic ", 0, "Basic ".length())) {
            return null;
        }
        try {
            byte[] supplied = Base64.getDecoder().decode(authorization.substring("Basic ".length()));
            return MessageDigest.isEqual(expectedBusinessCredentials, supplied) ? businessOperator : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String authenticateInternalService(String authorization) {
        if (expectedInternalToken == null
                || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return null;
        }
        byte[] supplied = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedInternalToken, supplied) ? internalServiceName : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static AuthenticatedIdentity identity(String operator, AuthenticationKind kind) {
        return operator == null ? null : new AuthenticatedIdentity(operator, kind);
    }

    private record AuthenticatedIdentity(String operator, AuthenticationKind kind) {}
}
