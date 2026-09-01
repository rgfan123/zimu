import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildOrderDraftConfirmCommand,
  buildOrderDraftRejectCommand,
  initialOrderDraftReviewForm,
  orderDraftReviewPermissions,
  orderDraftMissingFields,
  type OrderDraftDetail,
} from '../src/pages/workbench/orderDraftReview.ts';
import {
  emptyMasterDataOptionState,
  hasMoreMasterDataOptions,
  loadMasterDataOptionPage,
} from '../src/pages/workbench/orderDraftMasterData.ts';
import type { MasterDataPage } from '../src/api/types.ts';

function draft(): OrderDraftDetail {
  return {
    id: '27',
    draft_no: 'OD-18-1',
    source_order_no: 'WECOM-SUB-18-1',
    submission_id: '18',
    status: 'OPEN',
    revision: 4,
    customer_candidates: [
      { customer_id: '12', customer_code: 'CUST-WECOM-0001', customer_name: '子牧测试客户', matched_by: 'deterministic-mapping' },
    ],
    customer_name_raw: '客户原始描述',
    receiver_name: '张三',
    receiver_phone: '13800000000',
    receiver_address: '上海市浦东新区测试路 1 号',
    settlement_method: 'MONTHLY',
    settlement_time: '2026-08-31T16:00:00.000Z',
    missing_fields: [],
    lines: [
      {
        id: '31',
        line_no: 1,
        sku_candidates: [{ sku_id: '44', sku_code: 'SKU-JD-000001', product_name: '子牧羊小腿' }],
        product_name_raw: '羊小腿原始描述',
        spec_raw: '500g/盒',
        unit_raw: '盒',
        quantity: 2,
      },
    ],
    review_case_id: '99',
    review_case_version: 7,
    created_at: '2026-08-13T08:00:00Z',
    updated_at: '2026-08-13T08:00:00Z',
  };
}

test('review form defaults deterministic unique candidates and a persisted settlement time', () => {
  const form = initialOrderDraftReviewForm(draft());

  assert.equal(form.customer_id, '12');
  assert.equal(form.items[1]?.sku_id, '44');
  assert.equal(form.items[1]?.quantity, '2');
  assert.equal(form.settlement_time, '2026-08-31T16:00:00.000Z');
  assert.deepEqual(orderDraftMissingFields(draft(), form), []);

  const missingTime = draft();
  missingTime.settlement_time = null;
  assert.deepEqual(orderDraftMissingFields(missingTime, initialOrderDraftReviewForm(missingTime)), ['settlement_time']);

  const ambiguous = draft();
  ambiguous.customer_candidates = [
    { customer_id: '12', customer_name: 'A' },
    { customer_id: '13', customer_name: 'B' },
  ];
  ambiguous.lines[0].sku_candidates = [];
  const ambiguousForm = initialOrderDraftReviewForm(ambiguous);
  assert.equal(ambiguousForm.customer_id, '');
  assert.equal(ambiguousForm.items[1]?.sku_id, '');

  const unsupportedSettlement = draft();
  unsupportedSettlement.settlement_method = '月结';
  const unsupportedSettlementForm = initialOrderDraftReviewForm(unsupportedSettlement);
  unsupportedSettlementForm.settlement_time = '2026-08-31T16:00:00.000Z';
  assert.equal(unsupportedSettlementForm.settlement_method, '');
  assert.deepEqual(
    orderDraftMissingFields(unsupportedSettlement, unsupportedSettlementForm),
    ['settlement_method'],
  );
});

test('a zero-line draft remains incomplete even when every header field is filled', () => {
  const empty = draft();
  empty.lines = [];
  const form = initialOrderDraftReviewForm(empty);
  form.settlement_time = '2026-08-31T16:00:00.000Z';

  assert.deepEqual(orderDraftMissingFields(empty, form), ['items']);
  assert.throws(
    () => buildOrderDraftConfirmCommand(empty, form),
    /items/,
  );
});

test('confirm command carries visible draft/case versions and explicit human choices but no provider id', () => {
  const current = draft();
  const form = initialOrderDraftReviewForm(current);
  form.settlement_time = '2026-08-31T16:00:00.000Z';
  form.items[1] = { sku_id: '45', quantity: '3' };
  form.remark = '  已与原始消息核对  ';

  const command = buildOrderDraftConfirmCommand(current, form);

  assert.deepEqual(command, {
    expected_revision: 4,
    expected_case_version: 7,
    customer: { customer_id: '12' },
    receiver: {
      name: '张三',
      phone: '13800000000',
      province: '',
      city: '',
      district: '',
      town: '',
      address: '上海市浦东新区测试路 1 号',
    },
    settlement: { method: 'MONTHLY', settlement_time: '2026-08-31T16:00:00.000Z' },
    items: [{ line_no: 1, sku_id: '45', quantity: 3 }],
    remark: '已与原始消息核对',
  });
  assert.equal('fulfillment_provider_id' in command, false);
});

test('reject command requires the visible versions and a non-empty reason', () => {
  assert.deepEqual(buildOrderDraftRejectCommand(draft(), '  客户已取消需求  '), {
    expected_revision: 4,
    expected_case_version: 7,
    reason: '客户已取消需求',
  });
  assert.throws(() => buildOrderDraftRejectCommand(draft(), '   '), /拒绝理由/);
});

test('confirm and reject permissions independently control their editing surfaces', () => {
  assert.deepEqual(
    orderDraftReviewPermissions('OPEN', 'OPEN', ['CONFIRM_ORDER_DRAFT']),
    { canConfirm: true, canReject: false },
  );
  assert.deepEqual(
    orderDraftReviewPermissions('OPEN', 'OPEN', ['REJECT_ORDER_DRAFT']),
    { canConfirm: false, canReject: true },
  );
  assert.deepEqual(
    orderDraftReviewPermissions('RESOLVED', 'OPEN', [
      'CONFIRM_ORDER_DRAFT',
      'REJECT_ORDER_DRAFT',
    ]),
    { canConfirm: false, canReject: false },
  );
});

test('customer picker uses server search and can page beyond the first result window', async () => {
  const calls: Array<{ page: number; size: number; query?: string }> = [];
  const loader = async (query: { page: number; size: number; query?: string }): Promise<MasterDataPage> => {
    calls.push(query);
    return {
      page: query.page,
      size: query.size,
      total_elements: 3,
      total_pages: 2,
      items: query.page === 0
        ? [
            { id: '201', code: 'C-201', name: '超大客户一部', active: true, version: 0 },
            { id: '202', code: 'C-202', name: '已停用客户', active: false, version: 0 },
          ]
        : [{ id: '203', code: 'C-203', name: '超大客户二部', active: true, version: 0 }],
    };
  };

  const first = await loadMasterDataOptionPage(
    loader,
    emptyMasterDataOptionState(),
    { query: '  超大客户  ', reset: true },
  );
  const second = await loadMasterDataOptionPage(loader, first, { query: '超大客户' });

  assert.deepEqual(calls, [
    { page: 0, size: 50, query: '超大客户' },
    { page: 1, size: 50, query: '超大客户' },
  ]);
  assert.deepEqual(second.items.map((item) => item.id), ['201', '203']);
  assert.equal(hasMoreMasterDataOptions(second), false);
});

test('sku picker paginates through the existing list contract without inventing a search parameter', async () => {
  const calls: Array<{ page: number; size: number; query?: string }> = [];
  const loader = async (query: { page: number; size: number; query?: string }): Promise<MasterDataPage> => {
    calls.push(query);
    return {
      page: query.page,
      size: query.size,
      total_elements: 51,
      total_pages: 2,
      items: [{
        id: query.page === 0 ? '301' : '351',
        code: query.page === 0 ? 'SKU-301' : 'SKU-351',
        name: query.page === 0 ? '首页 SKU' : '后续页 SKU',
        active: true,
        version: 0,
      }],
    };
  };

  const first = await loadMasterDataOptionPage(loader, emptyMasterDataOptionState(), { reset: true });
  const second = await loadMasterDataOptionPage(loader, first);

  assert.deepEqual(calls, [
    { page: 0, size: 50 },
    { page: 1, size: 50 },
  ]);
  assert.deepEqual(second.items.map((item) => item.id), ['301', '351']);
});
