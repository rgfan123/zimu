package cn.zimu.fulfillment.connector;

import java.time.OffsetDateTime;

public record ConnectionTestResult(
        boolean success,
        OffsetDateTime checkedAt,
        int latencyMs,
        String businessCode,
        String message) {}
