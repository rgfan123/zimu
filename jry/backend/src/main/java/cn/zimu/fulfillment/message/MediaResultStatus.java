package cn.zimu.fulfillment.message;

/** 一次媒体证据处理尝试的对外结果状态：SUCCEEDED 成功；PENDING 暂时失败待重试；FAILED 已达重试上限的终态失败。 */
public enum MediaResultStatus {
    SUCCEEDED,
    PENDING,
    FAILED
}
