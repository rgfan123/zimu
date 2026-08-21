package cn.zimu.fulfillment.connector.wecom;

import java.nio.file.Path;

/** 企业微信外部传输 seam（#82/#84 业务消费方跨包依赖）；生产适配器是当前长连接。 */
public interface WecomOutboundTransport {

    WecomSendResult send(WecomOutboundMessage message);

    /** 三步分片素材上传：#84 等业务调用方经此 seam 上传，不直接拼协议 JSON。 */
    WecomUploadResult upload(Path file, String filename, WecomMediaType type);
}
