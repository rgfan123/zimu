package cn.zimu.fulfillment.common.dto;

import java.time.Instant;
import java.util.Map;

/** 主数据通用记录：id/code/name/active/version + attributes。 */
public record MasterDataRecord(
        String id,
        String code,
        String name,
        boolean active,
        long version,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt) {
}
