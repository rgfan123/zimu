package cn.zimu.fulfillment.message;

import java.time.Instant;
import java.util.List;

/** 消息提交的白名单投影：包含解释历史与最近任务状态，不包含协议原始载荷与秘密。 */
public record MessageSubmissionDetailDto(
        String id,
        String submissionNo,
        String status,
        String sourceMessageId,
        String currentIntent,
        String latestError,
        List<InterpretationDto> interpretations,
        TaskStatusDto latestTask,
        Instant createdAt) {}
