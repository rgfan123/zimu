package cn.zimu.fulfillment.message;

/**
 * 一次媒体证据处理尝试的结果（对外契约，供接收链路接线）。
 *
 * <p>status 语义：SUCCEEDED 已可用（storageRef 指向受控文件）；PENDING 暂时失败、调用方可按
 * 尝试次数重试；FAILED 已达重试上限的终态失败，应由任务框架转人工待办。
 */
public record MediaResult(
        MediaResultStatus status,
        Long mediaId,
        String storageRef,
        String sha256,
        String contentType,
        Long sizeBytes,
        String failureReason) {

    public static MediaResult succeeded(
            Long mediaId, String storageRef, String sha256, String contentType, Long sizeBytes) {
        return new MediaResult(
                MediaResultStatus.SUCCEEDED, mediaId, storageRef, sha256, contentType, sizeBytes, null);
    }

    public static MediaResult failed(MediaResultStatus status, Long mediaId, String failureReason) {
        return new MediaResult(status, mediaId, null, null, null, null, failureReason);
    }
}
