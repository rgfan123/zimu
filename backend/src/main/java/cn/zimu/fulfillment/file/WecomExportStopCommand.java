package cn.zimu.fulfillment.file;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 人工停止企微发送命令：版本 CAS + 明确理由（expected_version 与 reason 均必填）。 */
public record WecomExportStopCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @Size(max = 500) String reason) {}
