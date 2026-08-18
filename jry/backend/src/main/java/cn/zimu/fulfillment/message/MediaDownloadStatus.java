package cn.zimu.fulfillment.message;

/** 渠道媒体下载/解密的持久化状态机（对齐 V8 表 CHECK 枚举顺序）。 */
public enum MediaDownloadStatus {
    PENDING,
    DOWNLOADING,
    AVAILABLE,
    FAILED
}
