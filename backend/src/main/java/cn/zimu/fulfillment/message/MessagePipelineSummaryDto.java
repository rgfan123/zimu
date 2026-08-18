package cn.zimu.fulfillment.message;

/**
 * 消息链路后台运行摘要（wecom-message-intake 12）：只含计数与保留期限配置，
 * 不含任何载荷、错误原文或秘密。
 */
public record MessagePipelineSummaryDto(
        long backlog,
        long retrying,
        long finalFailures,
        long mediaFailures,
        int retentionDays,
        boolean retentionEnabled) {}
