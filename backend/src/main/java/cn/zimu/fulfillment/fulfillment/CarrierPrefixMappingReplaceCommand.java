package cn.zimu.fulfillment.fulfillment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Atomic replacement command for the complete carrier-prefix rule set. */
public record CarrierPrefixMappingReplaceCommand(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull @Size(max = 200) List<@Valid CarrierPrefixMappingEntry> mappings) {}
