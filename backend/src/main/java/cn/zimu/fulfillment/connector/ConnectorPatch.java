package cn.zimu.fulfillment.connector;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Connector 配置写入体。字段缺省一律表示「保持现值」。
 *
 * @param pullSchedule 拉取时间表；缺省表示不改。给出时必须五个字段齐全，理由见
 *                     {@link ConnectorPullSchedulePatch}
 */
public record ConnectorPatch(
        @Min(0) long expectedVersion,
        @Pattern(regexp = "MOCK|REAL") String clientMode,
        @Pattern(regexp = "EXCEL|API") String transportMode,
        Boolean enabled,
        String endpoint,
        String credentialSecretRef,
        String username,
        String password,
        @Valid ConnectorPullSchedulePatch pullSchedule) {}
