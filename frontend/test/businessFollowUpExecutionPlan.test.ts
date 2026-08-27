import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildBusinessFollowUpCreateInput,
  executionPlanByteLength,
  executionPlanSizeError,
  formalItemCountError,
  isValidExecutionDecimal,
  isValidExecutionInteger,
  isValidIsoDate,
} from '../src/pages/workbench/businessFollowUpExecutionPlan.ts';

test('CUSTOMER requests never carry a stale execution plan', () => {
  const input = buildBusinessFollowUpCreateInput({
    message_submission_id: '91',
    employee_draft: ' 普通客户跟进 ',
    business_kind: 'CUSTOMER',
    execution_plan: {
      sample_name: '切换类型前残留的样品计划',
    },
  });

  assert.deepEqual(input, {
    message_submission_id: '91',
    employee_draft: '普通客户跟进',
    business_kind: 'CUSTOMER',
  });
});

test('numeric, date and item-count guards match backend storage bounds', () => {
  assert.equal(isValidExecutionDecimal(0.001), true);
  assert.equal(isValidExecutionDecimal(99_999_999_999.999), true);
  assert.equal(isValidExecutionDecimal(0.0001), false);
  assert.equal(isValidExecutionDecimal(100_000_000_000), false);
  assert.equal(isValidExecutionInteger(1), true);
  assert.equal(isValidExecutionInteger(2_147_483_647), true);
  assert.equal(isValidExecutionInteger(2_147_483_648), false);
  assert.equal(isValidExecutionInteger(1.5), false);
  assert.equal(isValidIsoDate('2028-02-29'), true);
  assert.equal(isValidIsoDate('2026-02-29'), false);
  assert.equal(formalItemCountError([{}]), null);
  assert.equal(formalItemCountError(Array.from({ length: 500 }, () => ({}))), null);
  assert.equal(formalItemCountError([]), '正式订单商品明细必须为 1..500 行');
  assert.equal(formalItemCountError(Array.from({ length: 501 }, () => ({}))), '正式订单商品明细必须为 1..500 行');
});

test('FORMAL requests fix order_type and preserve only populated whitelist fields', () => {
  const input = buildBusinessFollowUpCreateInput({
    message_submission_id: '92',
    employee_draft: '正式订单证据',
    business_kind: 'FORMAL',
    execution_plan: {
      name: ' 月度供货 ',
      delivery_date: '2026-09-10',
      delivery_address: ' 已授权地址 ',
      settlement_period: ' ',
      business_note: '按月执行',
      commercial_terms: {
        payment_terms: ' 月结 ',
        quoted_price: '',
      },
      items: [
        { product_name: ' 牛肩切片 ', quantity_per_unit: 10, quantity_unit: 'kg', unit_count: 5 },
        { product_name: ' 羊小腿 ', quantity_per_unit: 2.5, quantity_unit: '箱', unit_count: 3 },
      ],
    },
  });

  assert.deepEqual(input, {
    message_submission_id: '92',
    employee_draft: '正式订单证据',
    business_kind: 'FORMAL',
    execution_plan: {
      order_type: 'formal',
      name: '月度供货',
      delivery_date: '2026-09-10',
      delivery_address: '已授权地址',
      business_note: '按月执行',
      commercial_terms: { payment_terms: '月结' },
      items: [
        { product_name: '牛肩切片', quantity_per_unit: 10, quantity_unit: 'kg', unit_count: 5 },
        { product_name: '羊小腿', quantity_per_unit: 2.5, quantity_unit: '箱', unit_count: 3 },
      ],
    },
  });
});

test('64 KiB guard is immediate for executable plans and irrelevant to CUSTOMER', () => {
  const values = {
    message_submission_id: '93',
    employee_draft: '大计划',
    business_kind: 'SAMPLE' as const,
    execution_plan: { requirements: '中'.repeat(22_000) },
  };

  assert.equal(executionPlanSizeError(values), '执行计划不能超过 64 KiB，请精简说明或商品明细');
  assert.equal(executionPlanSizeError({ ...values, business_kind: 'CUSTOMER' }), null);
});

test('FORMAL size guard measures the normalized payload including server-contract order_type', () => {
  const values = {
    message_submission_id: '94',
    employee_draft: '正式订单',
    business_kind: 'FORMAL' as const,
    execution_plan: {
      name: ' 九月供货 ',
      delivery_date: '2026-09-10',
      delivery_address: ' 已授权地址 ',
      settlement_period: ' ',
      commercial_terms: { payment_terms: ' 月结 ', quoted_price: '' },
      items: [{ product_name: ' 牛肩切片 ', quantity_per_unit: 10, quantity_unit: 'kg', unit_count: 5 }],
    },
  };
  const input = buildBusinessFollowUpCreateInput(values);
  assert.equal(input.business_kind, 'FORMAL');
  const expected = new TextEncoder().encode(JSON.stringify(input.execution_plan)).byteLength;

  assert.equal(executionPlanByteLength(values), expected);
  assert.equal('order_type' in values.execution_plan, false);
  assert.match(JSON.stringify(input.execution_plan), /"order_type":"formal"/);
});
