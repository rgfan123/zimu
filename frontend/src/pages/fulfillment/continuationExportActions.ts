import type { ContinuationExportCommand, ContinuationExportResult, ShippingProgress } from '@/api/types';

export function canCreateContinuationExport(
  dataScope: 'BUSINESS' | 'DEMO',
  shippingProgress: ShippingProgress,
  providerType: 'JD_WAREHOUSE' | 'THIRD_PARTY' | undefined,
): boolean {
  return dataScope === 'BUSINESS'
    && shippingProgress === 'PARTIALLY_SHIPPED'
    && providerType === 'THIRD_PARTY';
}

export function buildContinuationExportCommand(
  expectedVersion: number,
  instructedQuantity: string,
  remark: string,
): ContinuationExportCommand {
  const normalizedQuantity = Number(instructedQuantity.trim());
  if (!Number.isInteger(normalizedQuantity) || normalizedQuantity <= 0 || normalizedQuantity > 2_147_483_647) {
    throw new Error('续发数量必须为 int32 正整数');
  }
  return {
    expected_version: expectedVersion,
    instructed_quantity: normalizedQuantity,
    remark: remark.trim(),
  };
}

export function continuationExportResultMessage(result: ContinuationExportResult): string {
  return `已创建第 ${result.shipment_sequence} 批续发：发货批次 ${result.shipment_id}，履约导出 ${result.fulfillment_export_id}`;
}
