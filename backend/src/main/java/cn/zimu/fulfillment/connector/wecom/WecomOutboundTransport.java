package cn.zimu.fulfillment.connector.wecom;

/** 企业微信外部传输 seam；生产适配器是当前长连接。 */
interface WecomOutboundTransport {

    WecomSendResult send(WecomOutboundMessage message);
}
