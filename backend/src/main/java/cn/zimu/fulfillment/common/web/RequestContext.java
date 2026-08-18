package cn.zimu.fulfillment.common.web;

/** 当前请求的关联上下文与服务端已验证身份，由过滤器写入 ThreadLocal。 */
public final class RequestContext {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private final String requestId;
    private final String traceId;
    private final String operator;
    private final String authenticatedOperator;

    public RequestContext(String requestId, String traceId, String operator) {
        this(requestId, traceId, operator, null);
    }

    public RequestContext(
            String requestId,
            String traceId,
            String operator,
            String authenticatedOperator) {
        this.requestId = requestId;
        this.traceId = traceId;
        this.operator = operator;
        this.authenticatedOperator = authenticatedOperator;
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    public static RequestContext current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getOperator() {
        return operator;
    }

    public String getAuthenticatedOperator() {
        return authenticatedOperator;
    }
}
