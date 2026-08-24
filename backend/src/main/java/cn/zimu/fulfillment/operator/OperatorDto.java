package cn.zimu.fulfillment.operator;

import java.time.Instant;

/** 运营人员响应（Issue #89）：wecom_userid 为 null 表示未绑定企微 userid。 */
public record OperatorDto(
        String id,
        String displayName,
        String responsibleTeam,
        String wecomUserid,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
