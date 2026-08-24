package cn.zimu.fulfillment.connector.wecom;

import java.util.Set;

/**
 * 企业微信临时素材类型（官方文档 path/101463）：file / image / voice / video。
 *
 * <p>逐类型限制（2026-08-21 核对）：file ≤ 20MiB；image ≤ 10MiB；voice ≤ 2MiB；video ≤ 10MiB；
 * 图片仅支持 png/jpg/jpeg/gif。本票范围只开放 {@link #FILE}（xlsx/xls）与 {@link #IMAGE}；
 * voice/video 的协议值与大小上限仍在此登记，但无允许扩展名，上传会被校验拒绝。
 */
public enum WecomMediaType {

    FILE("file", 20 * 1024 * 1024, Set.of("xlsx", "xls")),
    IMAGE("image", 10 * 1024 * 1024, Set.of("png", "jpg", "jpeg", "gif")),
    VOICE("voice", 2 * 1024 * 1024, Set.of()),
    VIDEO("video", 10 * 1024 * 1024, Set.of());

    private final String protocolValue;
    private final long maxSizeBytes;
    private final Set<String> allowedExtensions;

    WecomMediaType(String protocolValue, long maxSizeBytes, Set<String> allowedExtensions) {
        this.protocolValue = protocolValue;
        this.maxSizeBytes = maxSizeBytes;
        this.allowedExtensions = allowedExtensions;
    }

    /** 协议 body.type 取值。 */
    public String protocolValue() {
        return protocolValue;
    }

    /** 官方逐类型大小上限（字节）。 */
    public long maxSizeBytes() {
        return maxSizeBytes;
    }

    /** 该类型允许的扩展名（小写，不含点）；为空表示本票不支持上传该类型。 */
    public Set<String> allowedExtensions() {
        return allowedExtensions;
    }
}
