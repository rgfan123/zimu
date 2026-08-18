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
        Map<String, Object> rawSnapshot) {
}
