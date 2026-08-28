package cn.zimu.fulfillment.connector.wecom;

import java.util.Set;

/**
 * 企业微信临时素材类型（官方文档 path/101463）：file / image / voice / video。
 *
 * <p>逐类型限制（2026-08-21 核对）：file ≤ 20MiB；image ≤ 10MiB；voice ≤ 2MiB；video ≤ 10MiB；
 * 官方只对 image 限定扩展名（png/jpg/jpeg/gif），file 类型只限大小——所以这里 {@link #FILE}
 * 的允许集是我方自设的范围闸门，不是协议约束。voice/video 的协议值与大小上限仍在此登记，
 * 但无允许扩展名，上传会被校验拒绝。
 *
 * <p>csv 于 2026-08-28 加入 {@link #FILE}：飞象来源回填产物本就是文本 CSV（v2 模板为 GB18030
 * 编码），此前投递时文件名被硬编码成 .xlsx，收件人用 Excel 打不开、传回平台大概率被拒。
 * 修正扩展名后若不同时放开 csv，这条投递会被本地校验直接拒收，等于彻底断掉飞象的企微投递。
 */
public enum WecomMediaType {

    FILE("file", 20 * 1024 * 1024, Set.of("xlsx", "xls", "csv")),
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
