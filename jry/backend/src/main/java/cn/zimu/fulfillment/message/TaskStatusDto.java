package cn.zimu.fulfillment.message;

import java.time.Instant;

/** 消息链路异步任务状态投影。 */
public record TaskStatusDto(
        String id,
        String taskType,
        String status,
        int attempts,
        int maxAttempts,
        String lastError,
        Instant createdAt) {}
