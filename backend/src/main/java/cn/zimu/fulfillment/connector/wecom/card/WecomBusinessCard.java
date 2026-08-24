package cn.zimu.fulfillment.connector.wecom.card;

/** 一行业务卡投递记录（app.wecom_business_cards）。 */
public record WecomBusinessCard(
        long id,
        String cardDomain,
        long entityId,
        long entityVersion,
        String taskId,
        String routeType,
        String chatId,
        String status,
        int attemptCount) {}
