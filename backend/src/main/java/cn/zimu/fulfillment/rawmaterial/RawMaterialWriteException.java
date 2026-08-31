package cn.zimu.fulfillment.rawmaterial;

/**
 * 原料库存写网关的失败分类（镜像 {@code KehuzxWriteException} 的独立写异常纪律）。
 *
 * <p>五类是稳定契约，各自处置不同：未开写→部署侧显式开启；上游拒绝（4xx 带 detail）→
 * 修正入参后重试，属 UNPROCESSABLE 语义；鉴权失败→换写凭据/提角色；不可用（5xx/网络）→
 * 稍后重试；契约漂移→上游结构变了，必须停下，绝不猜着解析一份可能错的台账。
 */
public class RawMaterialWriteException extends RuntimeException {

    public enum Code {
        RAW_MATERIAL_WRITE_DISABLED,
        RAW_MATERIAL_WRITE_REJECTED,
        RAW_MATERIAL_WRITE_UNAUTHORIZED,
        RAW_MATERIAL_WRITE_UNAVAILABLE,
        RAW_MATERIAL_WRITE_CONTRACT_DRIFT
    }

    private final Code code;

    public RawMaterialWriteException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public RawMaterialWriteException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
