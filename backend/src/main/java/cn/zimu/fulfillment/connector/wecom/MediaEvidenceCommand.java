package cn.zimu.fulfillment.connector.wecom;

/**
 * 媒体证据处理命令：一条已解析出的渠道媒体项（image/mixed/file 的 url + aeskey）。
 *
 * <p>由接收链路（WS 帧解析）构造：channelMessageId 必须指向已落库的 {@code channel_messages} 行
 * （外键约束）；channelMediaId 与 channelMessageId 构成幂等键；url 5 分钟有效，应在任务内即时下载。
 * aeskey 只用于本次解密，不在媒体行中持久化。
 */
public record MediaEvidenceCommand(
        Long channelMessageId,
        Long submissionId,
        String channelMediaId,
        String mediaType,
        String sourceUrl,
        String aeskeyBase64) {

    public MediaEvidenceCommand {
        if (channelMessageId == null) {
            throw new IllegalArgumentException("channelMessageId 不能为空");
        }
        if (isBlank(channelMediaId)) {
            throw new IllegalArgumentException("channelMediaId 不能为空");
        }
        if (isBlank(mediaType)) {
            throw new IllegalArgumentException("mediaType 不能为空");
        }
        if (isBlank(sourceUrl)) {
            throw new IllegalArgumentException("sourceUrl 不能为空");
        }
        if (isBlank(aeskeyBase64)) {
            throw new IllegalArgumentException("aeskeyBase64 不能为空");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
