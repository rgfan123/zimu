import assert from 'node:assert/strict';
import test from 'node:test';
import { continuationExportRequest } from '../src/api/continuationExport.ts';
import {
  buildContinuationExportCommand,
  canCreateContinuationExport,
  continuationExportResultMessage,
} from '../src/pages/fulfillment/continuationExportActions.ts';

test('continuation export request carries the visible version, quantity and browser replay key', () => {
  assert.deepEqual(
    continuationExportRequest(
      '55',
      {
        expected_version: 7,
        instructed_quantity: '2.500',
        remark: '采购到货后续发',
      },
      { 'Idempotency-Key': 'replay-key-55' },
    ),
    {
      path: '/api/v1/fulfillments/55/continuation-exports',
      options: {
        method: 'POST',
        body: {
          expected_version: 7,
          instructed_quantity: '2.500',
          remark: '采购到货后续发',
        },
        headers: { 'Idempotency-Key': 'replay-key-55' },
      },
    },
  );
});

test('continuation entry is limited to partially shipped third-party business fulfillments', () => {
  assert.equal(canCreateContinuationExport('BUSINESS', 'PARTIALLY_SHIPPED', 'THIRD_PARTY'), true);
  assert.equal(canCreateContinuationExport('BUSINESS', 'SHIPPED', 'THIRD_PARTY'), false);
  assert.equal(canCreateContinuationExport('BUSINESS', 'PARTIALLY_SHIPPED', 'JD_WAREHOUSE'), false);
  assert.equal(canCreateContinuationExport('DEMO', 'PARTIALLY_SHIPPED', 'THIRD_PARTY'), false);
});

test('continuation dialog submits the visible version and names the created batch in its result', () => {
  assert.deepEqual(buildContinuationExportCommand(7, ' 2.500 ', ' 采购到货后续发 '), {
    expected_version: 7,
    instructed_quantity: '2.500',
    remark: '采购到货后续发',
  });
  assert.equal(
    continuationExportResultMessage({
      fulfillment_id: '55',
      shipment_id: '88',
      shipment_sequence: 2,
      fulfillment_export_id: '99',
      instructed_quantity: '2.500',
      fulfillment_version: 8,
    }),
    '已创建第 2 批续发：发货批次 88，履约导出 99',
  );
});
