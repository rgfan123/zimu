import type { MasterDataRecord, SourceChannel } from '@/api/types';

export type SourceMappingMatrixChannel = Extract<
  SourceChannel,
  'FEIXIANG' | 'CAISHIXIAN' | 'JUFUBAO'
>;

export const SOURCE_MAPPING_CHANNELS: SourceMappingMatrixChannel[] = [
  'FEIXIANG',
  'CAISHIXIAN',
  'JUFUBAO',
];

export interface SourceSkuMappingMatrixRow {
  sku: MasterDataRecord;
  mappingsByChannel: Record<SourceMappingMatrixChannel, MasterDataRecord[]>;
}

export interface SourceSkuMappingMatrix {
  channels: SourceMappingMatrixChannel[];
  rows: SourceSkuMappingMatrixRow[];
}

export interface InternalSkuPresentation {
  primary: string;
  secondary: string;
  meta: string;
}

export interface SourceMappingPresentation {
  primary: string;
  secondary: string;
  multiplier: string;
}

function recordAttribute(record: MasterDataRecord, key: string): unknown {
  if (key in record) return (record as unknown as Record<string, unknown>)[key];
  return record.attributes?.[key];
}

export function internalSkuPresentation(sku: MasterDataRecord): InternalSkuPresentation {
  const specification = recordAttribute(sku, 'specification');
  const unit = recordAttribute(sku, 'unit');
  return {
    primary: sku.name,
    secondary: sku.code,
    meta: [specification, unit].filter(Boolean).join(' · ') || '未设置规格',
  };
}

export function sourceMappingPresentation(mapping: MasterDataRecord): SourceMappingPresentation {
  const sourceSkuRef = String(recordAttribute(mapping, 'source_sku_ref') ?? mapping.code);
  return {
    primary: mapping.name || sourceSkuRef,
    secondary: sourceSkuRef,
    multiplier: String(recordAttribute(mapping, 'quantity_multiplier') ?? '—'),
  };
}

function emptyCells(): Record<SourceMappingMatrixChannel, MasterDataRecord[]> {
  return {
    FEIXIANG: [],
    CAISHIXIAN: [],
    JUFUBAO: [],
  };
}

export function buildSourceSkuMappingMatrix(
  skus: MasterDataRecord[],
  mappings: MasterDataRecord[],
  selectedChannels: SourceMappingMatrixChannel[] = SOURCE_MAPPING_CHANNELS,
): SourceSkuMappingMatrix {
  const selected = new Set(selectedChannels);
  const channels = SOURCE_MAPPING_CHANNELS.filter((channel) => selected.has(channel));
  const rowsBySku = new Map<string, SourceSkuMappingMatrixRow>(
    skus.map((sku) => [sku.id, { sku, mappingsByChannel: emptyCells() }]),
  );

  for (const mapping of mappings) {
    const channel = String(recordAttribute(mapping, 'source_channel')) as SourceChannel;
    if (!SOURCE_MAPPING_CHANNELS.includes(channel as SourceMappingMatrixChannel)) continue;
    const row = rowsBySku.get(String(recordAttribute(mapping, 'sku_id') ?? ''));
    if (!row) continue;
    row.mappingsByChannel[channel as SourceMappingMatrixChannel].push(mapping);
  }

  return { channels, rows: [...rowsBySku.values()] };
}
