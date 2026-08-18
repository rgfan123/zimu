package cn.zimu.fulfillment.fulfillment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** One deterministic tracking-number prefix to enabled Carrier mapping. */
public record CarrierPrefixMappingEntry(
        @NotBlank @Size(max = 32) String prefix,
        @NotBlank @Size(max = 64) String carrierCode) {}
