package cn.zimu.fulfillment.message;

import java.time.Instant;

/** 一次解释版本的公共白名单投影；模型原始输出不进入浏览器响应。 */
public record InterpretationDto(
        int version,
        String intent,
        String provider,
        String model,
        String promptVersion,
        String error,
        Instant createdAt) {}
