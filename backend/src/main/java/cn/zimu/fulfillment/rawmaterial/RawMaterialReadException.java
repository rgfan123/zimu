package cn.zimu.fulfillment.rawmaterial;

/**
 * 原料库存只读网关的失败分类（spec D2：四类稳定错误码，各自处置不同）。
 *
 * <p>四类是对外契约（前端 {@code rawMaterialInventoryView} 的措辞按它分流），
 * 不得增删改名：未配置→去部署；不可用→稍后重试；鉴权失败→换凭据；
 * 契约漂移→上游结构变了，必须停下，绝不猜着解析出一份可能错的结存。
 */
public class RawMaterialReadException extends RuntimeException {

    public enum Code {
        RAW_MATERIAL_NOT_CONFIGURED,
        RAW_MATERIAL_UNAVAILABLE,
        RAW_MATERIAL_UNAUTHORIZED,
        RAW_MATERIAL_CONTRACT_DRIFT
    }

    private final Code code;

    public RawMaterialReadException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public RawMaterialReadException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
