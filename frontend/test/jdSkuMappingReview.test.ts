import assert from 'node:assert/strict';
import test from 'node:test';
import {
  jdSkuMappingReviewEvidence,
  rerunJdSkuMappingReview,
  jdSkuMappingRerunResultMessage,
  jdSkuMappingReviewPermissions,
} from '../src/pages/workbench/jdSkuMappingReview.ts';

test('京东 SKU 复核动作由 OPEN 状态和 allowed_actions 共同门禁', () => {
  assert.deepEqual(jdSkuMappingReviewPermissions('OPEN', [
    'OPEN_SKU_MAPPING',
    'RERUN_JD_SKU_MAPPING_CHECK',
  ]), { canOpenMapping: true, canRerun: true });
  assert.deepEqual(jdSkuMappingReviewPermissions('OPEN', ['OPEN_SKU_MAPPING']), {
    canOpenMapping: true,
    canRerun: false,
  });
  assert.deepEqual(jdSkuMappingReviewPermissions('RESOLVED', [
    'OPEN_SKU_MAPPING',
    'RERUN_JD_SKU_MAPPING_CHECK',
  ]), { canOpenMapping: false, canRerun: false });
});

test('重跑结果区分已通过和仍阻断', () => {
  assert.equal(jdSkuMappingRerunResultMessage({
    shipment_id: '31', check_run_no: 'CHK-1', gate_status: 'PASSED',
    checked_mapping_count: 2, blocking_issue_count: 0, warning_count: 0,
  }), '映射门禁已通过，阻断事项已解决');
  assert.match(jdSkuMappingRerunResultMessage({
    shipment_id: '31', check_run_no: 'CHK-2', gate_status: 'BLOCKED',
    checked_mapping_count: 2, blocking_issue_count: 3, warning_count: 1,
  }), /3 个阻断问题/);
});

test('受影响 ShipmentItem 只展示白名单标识与可扫读问题，不泄露原始 detail', () => {
  assert.deepEqual(jdSkuMappingReviewEvidence({
    affected_shipment_items: [{
      shipment_item_id: '71',
      fulfillment_id: '81',
      order_line_id: '91',
      line_no: 2,
      component_no: 1,
      sku_id: '101',
      sku_code: 'SKU-101',
      issues: [
        { code: 'UNIT_CONVERSION_MISSING', message: 'raw backend text' },
        { code: 'UNKNOWN_INTERNAL', message: 'secret_token=abc' },
      ],
      raw_payload: { token: 'secret' },
    }],
  }), [{
    evidenceKey: '71:91:1:101',
    shipmentItemId: '71',
    orderLineId: '91',
    lineLabel: '第 2 行 · 组件 1',
    skuLabel: 'SKU-101',
    issues: ['缺少显式京东件数换算', '需进一步核对'],
  }]);
});

test('同一 ShipmentItem 的多个礼包组件使用不同表格键', () => {
  const rows = jdSkuMappingReviewEvidence({
    affected_shipment_items: [1, 2].map((componentNo) => ({
      shipment_item_id: '71',
      order_line_id: '91',
      line_no: 2,
      component_no: componentNo,
      sku_id: String(100 + componentNo),
      sku_code: `SKU-${componentNo}`,
      issues: [{ code: 'MAPPING_MISSING' }],
    })),
  });

  assert.deepEqual(rows.map((row) => row.evidenceKey), [
    '71:91:1:101',
    '71:91:2:102',
  ]);
});

test('重跑仍阻断时重新读取当前 ReviewCase，通过时不再读取旧事项', async () => {
  const calls: string[] = [];
  const blocked = await rerunJdSkuMappingReview(
    { id: 'case-1', subject_id: 'shipment-1' },
    {
      check: async (shipmentId) => {
        calls.push(`check:${shipmentId}`);
        return {
          shipment_id: shipmentId,
          check_run_no: 'CHK-2',
          gate_status: 'BLOCKED' as const,
          checked_mapping_count: 2,
          blocking_issue_count: 1,
          warning_count: 0,
        };
      },
      loadReviewCase: async (reviewCaseId) => {
        calls.push(`detail:${reviewCaseId}`);
        return { id: reviewCaseId, detail: { check_run_no: 'CHK-2' } };
      },
    },
  );
  assert.deepEqual(calls, ['check:shipment-1', 'detail:case-1']);
  assert.deepEqual(blocked.refreshedCase, { id: 'case-1', detail: { check_run_no: 'CHK-2' } });

  calls.length = 0;
  const passed = await rerunJdSkuMappingReview(
    { id: 'case-1', subject_id: 'shipment-1' },
    {
      check: async () => ({
        shipment_id: 'shipment-1',
        check_run_no: 'CHK-3',
        gate_status: 'PASSED' as const,
        checked_mapping_count: 2,
        blocking_issue_count: 0,
        warning_count: 0,
      }),
      loadReviewCase: async () => {
        calls.push('unexpected-detail');
        return { id: 'case-1' };
      },
    },
  );
  assert.equal(passed.refreshedCase, null);
  assert.deepEqual(calls, []);
});
