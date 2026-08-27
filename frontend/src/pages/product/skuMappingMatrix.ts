import type { MasterDataRecord, SourceChannel } from '@/api/types';

export type SourceMappingMatrixChannel = Extract<
  SourceChannel,
  'FEIXIANG' | 'CAISHIXIAN' | 'JUFUBAO' | 'ZHONGHUI' | 'DAZHE' | 'WANQI'
>;

/**
 * 映射矩阵展示的来源渠道。
 *
 * <p>2026-08-27 生产事故（中汇 60043846 落复核）后补齐中汇/大者/万齐：此前这三个渠道
 * 不在列表里，运营**没有任何事前配置入口**，映射只能等出事后在复核抽屉里一条条补——
 * 「为什么就这个要复核」的结构性根源。后端 API 收的是 SourceChannel 枚举，本来就放行，
 * 闸门纯粹在这份前端清单上。
 *
 * <p>不列 WECOM（录入渠道，映射由草稿确认沉淀，不做事前配置）；
 * 不列 WANGQI（误建渠道，待 codex 礼包归一化 spec 的 04/05 票清理）。
 *
 * <p>大者注意：生产在用的大者 v2 模板没有商品编号列，「来源编号」按商品名称填——
 * 名称就是该渠道的唯一身份。
 */
export const SOURCE_MAPPING_CHANNELS: SourceMappingMatrixChannel[] = [
  'FEIXIANG',
  'CAISHIXIAN',
  'JUFUBAO',
  'ZHONGHUI',
  'DAZHE',
  'WANQI',
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
  // 逐键写全并由 Record 类型闭合：渠道清单加了新值而这里漏写时直接编译错
  return {
    FEIXIANG: [],
    CAISHIXIAN: [],
    JUFUBAO: [],
    ZHONGHUI: [],
    DAZHE: [],
    WANQI: [],
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
