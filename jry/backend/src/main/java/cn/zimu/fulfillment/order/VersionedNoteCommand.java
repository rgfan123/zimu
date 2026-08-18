package cn.zimu.fulfillment.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 带可选备注的版本化人工动作命令；备注仅作审计留痕，不强制填写。 */
public record VersionedNoteCommand(
        @JsonProperty("expected_version") @NotNull Long expectedVersion,
        @Size(max = 1000) String note) {}
