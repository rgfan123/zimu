import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildCustomerResolution,
  buildDismissCommand,
  buildManualResolution,
  buildSkuResolution,
  buildSourceFollowupCompletion,
  isPositiveCountQuantity,
  reviewAction,
} from '../src/pages/workbench/manualReviewActions.ts';
import { REASON_LABELS } from '../src/pages/workbench/queuePresentation.ts';
import type { ReviewCase } from '../src/api/types.ts';

function reviewCase(reasonCode: string, detail: Record<string, unknown> = {}): ReviewCase {
  return {
    id: '41',
    case_no: 'RC-41',
    case_type: 'TEST',
    responsible_team: 'ORDER_OPS',
    reason_code: reasonCode,
    status: 'OPEN',
    order_id: '9',
    subject_type: 'ORDER',
    subject_id: '9',
    detail,
    suggestions: [],
    allowed_actions: [],
    version: 3,
    created_at: '2026-08-12T08:00:00Z',
  };
}

function trackingDraftCase(allowedActions: ReviewCase['allowed_actions']): ReviewCase {
  const item = reviewCase('WECOM_TRACKING_DRAFT');
  item.order_id = undefined;
  item.subject_type = 'TRACKING_DRAFT';
  item.subject_id = '81';
  item.allowed_actions = allowedActions;
  return item;
}

test('review commands always carry the visible expected version and explicit existing master-data ids', () => {
  const customerCase = reviewCase('CUSTOMER_MATCH_REQUIRED', { source_channel: 'WECOM', source_customer_ref: 'WECOM-C-9' });
  assert.equal(reviewAction(customerCase), 'CUSTOMER');
  assert.deepEqual(buildCustomerResolution(customerCase, '12', '核对合同'), {
    expected_version: 3,
    customer_id: '12',
    source_channel: 'WECOM',
    source_customer_ref: 'WECOM-C-9',
    remark: '核对合同',
  });

  const skuCase = reviewCase('SKU_MAPPING_REQUIRED', { source_channel: 'WECOM', missing_source_sku_refs: ['WECOM-SKU-9'] });
  assert.equal(reviewAction(skuCase), 'SKU');
  assert.deepEqual(buildSkuResolution(skuCase, '7', 2, '核对装箱规格'), {
    expected_version: 3,
    sku_id: '7',
    source_channel: 'WECOM',
    source_sku_ref: 'WECOM-SKU-9',
    quantity_multiplier: 2,
    remark: '核对装箱规格',
  });
});

test('数量换算守卫拒绝小数、非正数和超出 int32 的值，不做静默修正', () => {
  assert.equal(isPositiveCountQuantity(3), true);
  assert.equal(isPositiveCountQuantity(3.5), false);
  assert.equal(isPositiveCountQuantity(0), false);
  assert.equal(isPositiveCountQuantity(-1), false);
  assert.equal(isPositiveCountQuantity(2_147_483_648), false);
  assert.equal(isPositiveCountQuantity(null), false);

  const skuCase = reviewCase('SKU_MAPPING_REQUIRED', {
    source_channel: 'WECOM',
    missing_source_sku_refs: ['WECOM-SKU-9'],
  });
  for (const invalid of [3.5, 0, -1, 2_147_483_648, Number.POSITIVE_INFINITY]) {
    assert.throws(
      () => buildSkuResolution(skuCase, '7', invalid, ''),
      /1 至 2147483647 的整数/,
    );
  }
});

test('multi-shipment follow-up uses its dedicated guarded command instead of a generic close', () => {
  const item = reviewCase('MULTI_SHIPMENT_SOURCE_FOLLOWUP');
  assert.equal(reviewAction(item), 'SOURCE_FOLLOWUP');
  assert.deepEqual(buildSourceFollowupCompletion(item, '来源平台逐票回填完成'), {
    expected_version: 3,
    note: '来源平台逐票回填完成',
  });
});

test('京东 SKU 门禁阻断留在复核工作台且只暴露明示允许的两个动作', () => {
  const item = reviewCase('JD_SKU_MAPPING_BLOCKED', { shipment_id: '31' });
  item.subject_type = 'SHIPMENT';
  item.subject_id = '31';
  item.allowed_actions = ['OPEN_SKU_MAPPING', 'RERUN_JD_SKU_MAPPING_CHECK'];

  assert.equal(reviewAction(item), 'JD_SKU_MAPPING');
  assert.deepEqual(item.allowed_actions, ['OPEN_SKU_MAPPING', 'RERUN_JD_SKU_MAPPING_CHECK']);
});

test('WECOM order draft cases stay inside the existing review workspace', () => {
  const item = reviewCase('WECOM_ORDER_DRAFT', { message_submission_id: '18' });
  item.order_id = undefined;
  item.subject_type = 'ORDER_DRAFT';
  item.subject_id = '27';
  item.allowed_actions = ['CONFIRM_ORDER_DRAFT', 'REJECT_ORDER_DRAFT'];

  assert.equal(reviewAction(item), 'ORDER_DRAFT');
  assert.deepEqual(item.allowed_actions, ['CONFIRM_ORDER_DRAFT', 'REJECT_ORDER_DRAFT']);
});

test('WECOM tracking draft cases stay inside the existing review workspace', () => {
  const item = trackingDraftCase(['CONFIRM_TRACKING_DRAFT']);

  assert.equal(reviewAction(item), 'TRACKING_DRAFT');
});

test('WECOM tracking file failures have an operator-readable queue label and generic close actions', () => {
  const item = reviewCase('WECOM_TRACKING_FILE_REVIEW', {
    source: 'WECOM_TRACKING_FILE',
    error_code: 'WECOM_TRACKING_FILE_INVALID',
  });
  item.order_id = undefined;
  item.subject_type = 'MESSAGE_SUBMISSION';
  item.subject_id = '18';
  item.allowed_actions = ['RESOLVE_MANUALLY', 'DISMISS'];

  assert.equal(REASON_LABELS.WECOM_TRACKING_FILE_REVIEW, '企微运单文件处理失败');
  assert.equal(reviewAction(item), 'NAVIGATE');
  assert.deepEqual(item.allowed_actions, ['RESOLVE_MANUALLY', 'DISMISS']);
});

test('generic manual resolution and dismissal always carry the visible expected version', () => {
  const conflictCase = reviewCase('SKU_MAPPING_CONFLICT');
  assert.equal(reviewAction(conflictCase), 'NAVIGATE');
  assert.deepEqual(buildManualResolution(conflictCase, '已在主数据页修正冲突映射'), {
    expected_version: 3,
    note: '已在主数据页修正冲突映射',
  });
  assert.deepEqual(buildManualResolution(conflictCase, '  '), {
    expected_version: 3,
    note: '',
  });

  assert.deepEqual(buildDismissCommand(conflictCase, '误建，线下已处理'), {
    expected_version: 3,
    note: '误建，线下已处理',
  });

  // 京东运单冲突走同一版本化备注命令，但必须由 allowed_actions 显式放行
  const jdTrackingCase = reviewCase('JD_TRACKING_CARRIER_MAPPING_REQUIRED');
  jdTrackingCase.subject_type = 'SHIPMENT';
  jdTrackingCase.subject_id = '31';
  jdTrackingCase.allowed_actions = ['RESOLVE_JD_TRACKING_CONFLICT', 'DISMISS'];
  assert.equal(reviewAction(jdTrackingCase), 'NAVIGATE');
  assert.deepEqual(buildManualResolution(jdTrackingCase, '承运商映射已核对'), {
    expected_version: 3,
    note: '承运商映射已核对',
  });
});
