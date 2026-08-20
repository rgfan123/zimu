package cn.zimu.fulfillment.connector.wecom;

/**
 * 素材上传前置校验失败：在发送 init（创建 upload_id）之前抛出，消息为中文可读原因，
 * 携带稳定错误码供上层映射。不属于传输失败，无重试语义，也不产生审计记录。
 */
public class WecomUploadValidationException extends RuntimeException {

    private final String code;

    WecomUploadValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 稳定错误码（如 UPLOAD_FILE_TOO_LARGE），可用于前端/日志归类。 */
    public String code() {
        return code;
    }
}
