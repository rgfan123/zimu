package cn.zimu.fulfillment.connector.sync;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 对账命令回显原始 intent 的全部稳定外部效果字段与投影版本，禁止从当前漂移事实重算键。
 */
public record SourceSyncReconcileCommand(
        @NotNull SourceSyncReconciliationDecision decision,
        @NotBlank @Size(max = 500) String note,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedCheckHash,
        @NotBlank @Size(max = 255) String expectedSourceLineRef,
        @NotBlank @Size(max = 64) String expectedCarrierCode,
        @NotBlank @Size(max = 128) String expectedTrackingNumber,
        @Min(0) long expectedVersion) {}
