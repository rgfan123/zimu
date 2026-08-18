package cn.zimu.fulfillment.connector.jd;

public record JdResult(
        boolean success,
        String businessCode,
        String message,
        String requestId,
        Object data) {}
