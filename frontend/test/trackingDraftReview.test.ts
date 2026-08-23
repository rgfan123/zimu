import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import {
  buildTrackingDraftConfirmCommand,
  initialTrackingDraftReviewForm,
  isAtomicShipmentDraft,
  trackingDraftBlockingIssues,
  trackingDraftCarrierSourceLabel,
  trackingDraftIssueLabel,
  type TrackingDraftDetail,
} from '../src/pages/workbench/trackingDraftReview.ts';

function draft(): TrackingDraftDetail {
  return {
    id: '81',
    draft_no: 'TD-18-1',
    submission_id: '18',
    line_no: 1,
    raw_receiver_name: '张三',
    masked_receiver_name: '张*',
    tracking_no: 'SF1234567890',
    carrier_code: 'SF',
    carrier_candidates: [
      { code: 'SF', name: '顺丰速运', source: 'STATED' },
    ],
    manual_carrier_options: [{ code: 'SF', name: '顺丰速运' }],
    task_id: '44',
    task_candidates: [
      {
        task_id: '44',
        fulfillment_no: 'FUL-20260813-0044',
        order_id: '12',
        order_no: 'ORD-20260813-0012',
        order_line_id: '31',
        shipment_id: '55',
        receiver_name: '张三',
        requested_quantity: '8.000',
        shipped_quantity: '0.000',
        instructed_quantity: '8.000',
      },
    ],
    source: 'WECOM_MESSAGE',
    confirmation_scope: 'SINGLE_TASK',
    shipment_judgment: 'FULL',
    default_full_shipment: true,
    actual_quantity: null,
    validation_issues: [],
    status: 'OPEN',
    revision: 4,
    confirmed_by: null,
    confirmed_at: null,
    review_case_id: '99',
    review_case_version: 7,
    created_at: '2026-08-13T08:00:00Z',
  };
}

test('single-line task-reference review defaults only unique deterministic candidates', () => {
  const form = initialTrackingDraftReviewForm(draft());

  assert.deepEqual(form, {
    task_id: '44',
    task_no: '',
    carrier_code: 'SF',
    actual_quantity: '',
    remark: '',
  });
  assert.deepEqual(trackingDraftBlockingIssues(draft(), form), []);
});

test('file partial drafts carry the parsed quantity through an explicit human confirmation', () => {
  const current = draft();
  current.source = 'WECOM_TRACKING_FILE';
  current.shipment_judgment = 'PARTIAL';
  current.default_full_shipment = false;
  current.actual_quantity = '1.250';
  current.carrier_candidates = [{ code: 'SF', name: '顺丰速运', source: 'FILE' }];

  const form = initialTrackingDraftReviewForm(current);
  assert.equal(form.actual_quantity, '1.250');
  assert.deepEqual(trackingDraftBlockingIssues(current, form), []);
  assert.equal(buildTrackingDraftConfirmCommand(current, 7, form).actual_quantity, '1.250');
  assert.deepEqual(
    trackingDraftBlockingIssues(current, { ...form, actual_quantity: '0' }),
    ['部分发货的实发数量无效'],
  );
});

test('file atomic shipment drafts expose every required task without pretending they are alternatives', () => {
  const current = draft();
  current.source = 'WECOM_TRACKING_FILE';
  current.confirmation_scope = 'ATOMIC_SHIPMENT';
  current.carrier_candidates = [{ code: 'SF', name: '顺丰速运', source: 'FILE' }];
  current.task_candidates.push({
    task_id: '45', fulfillment_no: 'FUL-20260813-0045', order_id: '12', order_no: 'ORD-20260813-0012',
    order_line_id: '32', shipment_id: '55', receiver_name: '张三',
    requested_quantity: '2.000', shipped_quantity: '0.000', instructed_quantity: '2.000',
  });

  assert.equal(isAtomicShipmentDraft(current), true);
  assert.equal(initialTrackingDraftReviewForm(current).task_id, '44');
  assert.deepEqual(trackingDraftBlockingIssues(current, initialTrackingDraftReviewForm(current)), []);
  assert.equal(trackingDraftCarrierSourceLabel('FILE'), '回传文件明确的物流公司');
});

test('confirmation carries both visible versions and never sends a fake shipment time', () => {
  const current = draft();
  const command = buildTrackingDraftConfirmCommand(
    current,
    7,
    { ...initialTrackingDraftReviewForm(current), remark: '  已核对原始企微消息  ' },
  );

  assert.deepEqual(command, {
    expected_draft_revision: 4,
    expected_case_version: 7,
    task_id: '44',
    task_no: null,
    carrier_code: 'SF',
    actual_quantity: null,
    remark: '已核对原始企微消息',
  });
  assert.equal('shipped_at' in command, false);
});

test('ticket 08 blocks ambiguous, invalid, or non-full drafts instead of guessing', () => {
  const invalid = draft();
  invalid.task_id = null;
  invalid.task_candidates = [
    {
      task_id: '44', fulfillment_no: 'FUL-A', order_id: '12', order_no: 'ORD-A',
      order_line_id: '31', shipment_id: '55', receiver_name: '张三',
      requested_quantity: '8.000', shipped_quantity: '0.000', instructed_quantity: '8.000',
    },
    {
      task_id: '45', fulfillment_no: 'FUL-B', order_id: '13', order_no: 'ORD-B',
      order_line_id: '32', shipment_id: '56', receiver_name: '张三',
      requested_quantity: '8.000', shipped_quantity: '0.000', instructed_quantity: '8.000',
    },
  ];
  invalid.carrier_code = null;
  invalid.carrier_candidates = [];
  invalid.default_full_shipment = false;
  invalid.validation_issues = ['TASK_SHIPMENT_MULTI_MATCH', 'CARRIER_CONFLICT'];

  const form = initialTrackingDraftReviewForm(invalid);
  assert.equal(form.task_id, '');
  assert.equal(form.carrier_code, '');
  assert.deepEqual(trackingDraftBlockingIssues(invalid, form), [
    '发货任务未唯一确定',
    '物流公司未唯一确定',
    '当前流程仅支持整项发货确认',
    '发货任务存在多个待回传发货批次',
    '消息明示物流公司与运单前缀候选冲突',
  ]);
  assert.throws(
    () => buildTrackingDraftConfirmCommand(invalid, 7, form),
    /还不能确认运单草稿/,
  );
});

test('an operator can explicitly choose among deterministic carrier candidates but cannot invent one', () => {
  const current = draft();
  current.carrier_code = null;
  current.carrier_candidates = [
    { code: 'SF', name: '顺丰速运', source: 'STATED' },
    { code: 'ZTO', name: '中通快递', source: 'PREFIX' },
  ];
  current.manual_carrier_options = [
    { code: 'SF', name: '顺丰速运' },
    { code: 'ZTO', name: '中通快递' },
  ];
  current.validation_issues = ['CARRIER_CONFLICT'];
  const form = initialTrackingDraftReviewForm(current);

  assert.equal(form.carrier_code, '');
  assert.deepEqual(trackingDraftBlockingIssues(current, form), [
    '物流公司未唯一确定',
    '消息明示物流公司与运单前缀候选冲突',
  ]);
  assert.deepEqual(
    trackingDraftBlockingIssues(current, { ...form, carrier_code: 'ZTO' }),
    [],
  );
  assert.deepEqual(
    trackingDraftBlockingIssues(current, { ...form, carrier_code: 'FAKE' }),
    [
      '物流公司未唯一确定',
      '消息明示物流公司与运单前缀候选冲突',
    ],
  );
});

test('an operator can explicitly choose among masked-name task candidates but cannot invent one', () => {
  const current = draft();
  current.task_id = null;
  current.task_candidates = [
    {
      task_id: '44', fulfillment_no: 'FUL-A', order_id: '12', order_no: 'ORD-A',
      order_line_id: '31', shipment_id: '55', receiver_name: '张三',
      requested_quantity: '8.000', shipped_quantity: '0.000', instructed_quantity: '8.000',
    },
    {
      task_id: '45', fulfillment_no: 'FUL-B', order_id: '13', order_no: 'ORD-B',
      order_line_id: '32', shipment_id: '56', receiver_name: '张四',
      requested_quantity: '8.000', shipped_quantity: '0.000', instructed_quantity: '8.000',
    },
  ];
  current.validation_issues = ['TASK_NAME_MULTI_MATCH'];
  const form = initialTrackingDraftReviewForm(current);

  assert.deepEqual(trackingDraftBlockingIssues(current, form), [
    '发货任务未唯一确定',
    '收货人匹配到多个待回传发货任务',
  ]);
  const selected = { ...form, task_id: '45' };
  assert.deepEqual(trackingDraftBlockingIssues(current, selected), []);
  assert.equal(buildTrackingDraftConfirmCommand(current, 7, selected).task_id, '45');
  assert.throws(
    () => buildTrackingDraftConfirmCommand(current, 7, { ...form, task_id: '999' }),
    /还不能确认运单草稿/,
  );
});

test('a stale or closed review case cannot produce a confirmation command', () => {
  assert.throws(
    () => buildTrackingDraftConfirmCommand(draft(), undefined, initialTrackingDraftReviewForm(draft())),
    /复核版本/,
  );

  const closed = draft();
  closed.status = 'CONFIRMED';
  assert.throws(
    () => buildTrackingDraftConfirmCommand(closed, 7, initialTrackingDraftReviewForm(closed)),
    /不是待确认状态/,
  );
});

test('a task id without its single deterministic candidate remains blocked from confirmation', () => {
  const incomplete = draft();
  incomplete.task_candidates = [];

  const form = initialTrackingDraftReviewForm(incomplete);
  assert.deepEqual(trackingDraftBlockingIssues(incomplete, form), [
    '发货任务未唯一确定',
  ]);
});

test('unknown validation codes never leak as untranslated internal fields', () => {
  const unknown = draft();
  unknown.validation_issues = ['INTERNAL_SECRET_VALIDATION_CODE'];

  assert.equal(
    trackingDraftIssueLabel('INTERNAL_SECRET_VALIDATION_CODE'),
    '存在暂不支持的校验问题，请联系管理员处理',
  );
  assert.deepEqual(
    trackingDraftBlockingIssues(unknown, initialTrackingDraftReviewForm(unknown)),
    ['存在暂不支持的校验问题，请联系管理员处理'],
  );
});

test('OpenAPI exposes file provenance, atomic confirmation scope, and FILE carrier evidence', () => {
  const openapi = readFileSync(new URL('../../docs/openapi.yaml', import.meta.url), 'utf8');
  assert.match(openapi, /source: \{ type: string, enum: \[STATED, PREFIX, FILE\] \}/);
  assert.match(openapi, /enum: \[WECOM_MESSAGE, WECOM_TRACKING_FILE\]/);
  assert.match(openapi, /enum: \[SINGLE_TASK, ATOMIC_SHIPMENT\]/);
});
