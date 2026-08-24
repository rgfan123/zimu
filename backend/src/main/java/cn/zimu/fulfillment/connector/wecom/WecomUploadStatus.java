package cn.zimu.fulfillment.connector.wecom;

/**
 * 企业微信素材上传的可判定结局。
 *
 * <p>{@link #UNKNOWN} 专指 finish 已提交但未获 ack（或应答不可信）的未知态：服务端可能已生成
 * media_id，禁止盲目重发 finish、禁止标记可重试；必须人工对账。
 */
public enum WecomUploadStatus {
    SUCCESS,
    FAILED,
    UNKNOWN
}
