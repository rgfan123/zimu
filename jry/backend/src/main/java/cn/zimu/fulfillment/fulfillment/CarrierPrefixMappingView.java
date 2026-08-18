package cn.zimu.fulfillment.fulfillment;

import java.util.List;

/** Canonically ordered public view of the authoritative mapping set. */
public record CarrierPrefixMappingView(long version, List<CarrierPrefixMappingEntry> mappings) {}
