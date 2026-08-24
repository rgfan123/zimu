package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import java.util.Map;

/**
 * 结构化导入行（ticket 02）：Connector transform 产物，直接喂 importStructured。
 *
 * <p>与文件导入不同，本行不再做容器魔数/表头指纹解析——canonicalInput 已由
 * Connector 的 transform 产出；rawSnapshot 为平台原始字段快照（审计与复核证据，
 * 入库前需脱敏），写入 raw_import_rows.raw_cells 保持血缘。</p>
 */
public record StructuredOrderRow(
        String sourceRef,
        String sourceLineRef,
        CanonicalOrderInput canonicalInput,
        Map<String, Object> rawSnapshot,
        ReviewRequired reviewRequired) {

    /** 保留既有 Connector 的四参数构造契约；未声明复核时按正常结构化订单导入。 */
    public StructuredOrderRow(
            String sourceRef,
            String sourceLineRef,
            CanonicalOrderInput canonicalInput,
            Map<String, Object> rawSnapshot) {
        this(sourceRef, sourceLineRef, canonicalInput, rawSnapshot, null);
    }

    /**
     * 来源证据尚不足以创建可履约订单时，保留原始行血缘并显式进入人工复核。
     */
    public static StructuredOrderRow reviewRequired(
            String sourceRef,
            String sourceLineRef,
            CanonicalOrderInput canonicalInput,
            Map<String, Object> rawSnapshot,
            String code,
            String message) {
        return new StructuredOrderRow(
                sourceRef,
                sourceLineRef,
                canonicalInput,
                rawSnapshot,
                new ReviewRequired(code, message));
    }

    public record ReviewRequired(String code, String message) {
        public ReviewRequired {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("复核原因代码不能为空");
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("复核原因说明不能为空");
            }
        }
    }
}
