package cn.zimu.fulfillment.connector;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record ConnectorPatch(
        @Min(0) long expectedVersion,
        @Pattern(regexp = "MOCK|REAL") String clientMode,
        @Pattern(regexp = "EXCEL|API") String transportMode,
        Boolean enabled,
        String endpoint,
        String credentialSecretRef) {}
