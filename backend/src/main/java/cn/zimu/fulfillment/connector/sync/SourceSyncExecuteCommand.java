package cn.zimu.fulfillment.connector.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 人工执行确认只绑定一次已展示检查的稳定哈希。 */
public record SourceSyncExecuteCommand(
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "expected_check_hash 必须是 SHA-256")
        String expectedCheckHash) {}
