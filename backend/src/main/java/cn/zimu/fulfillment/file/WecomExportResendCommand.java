package cn.zimu.fulfillment.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 人工重发命令：版本 CAS 必填；reason 可选（审计可追溯）。 */
public record WecomExportResendCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {}
