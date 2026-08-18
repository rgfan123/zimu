package cn.zimu.fulfillment.common.web;

/** 写命令上下文：请求关联信息、声明的操作人与服务端已验证的登录主体。 */
public record CommandContext(
        String requestId,
        String traceId,
        String operator,
        String authenticatedOperator) {

    /** 非 HTTP 用例的兼容构造器；不得将其视为已通过身份认证。 */
    public CommandContext(String requestId, String traceId, String operator) {
        this(requestId, traceId, operator, null);
    }
}
