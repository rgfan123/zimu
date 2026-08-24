package cn.zimu.fulfillment.message;

/** 企微运单文件任务对外可见的稳定失败码；原始 URL、密钥与异常文本均不得进入公开投影。 */
public enum WecomTrackingFileFailureCode {
    WECOM_TRACKING_FILE_CHAT_UNSUPPORTED,
    WECOM_TRACKING_FILE_PAYLOAD_INVALID,
    WECOM_TRACKING_FILE_DOWNLOAD_FAILED,
    WECOM_TRACKING_FILE_TOO_LARGE,
    WECOM_TRACKING_FILE_INVALID,
    WECOM_TRACKING_FILE_PROCESSING_FAILED;

    public static final String REVIEW_REASON = "WECOM_TRACKING_FILE_REVIEW";

    public String publicMessage() {
        return switch (this) {
            case WECOM_TRACKING_FILE_CHAT_UNSUPPORTED -> "当前仅支持把运单文件单聊直发给机器人";
            case WECOM_TRACKING_FILE_PAYLOAD_INVALID -> "企微文件消息缺少可用的下载信息，请重新单聊发送原文件";
            case WECOM_TRACKING_FILE_DOWNLOAD_FAILED -> "运单文件下载或解密失败，请重新单聊发送原文件";
            case WECOM_TRACKING_FILE_TOO_LARGE -> "运单文件超过 20MB 上限，请拆分后重新发送";
            case WECOM_TRACKING_FILE_INVALID -> "回传文件格式或内容不符合精确 24 列模板，请下载原件核对";
            case WECOM_TRACKING_FILE_PROCESSING_FAILED -> "运单文件处理失败，请人工复核并重试";
        };
    }
}
