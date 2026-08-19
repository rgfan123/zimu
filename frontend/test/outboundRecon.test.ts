import assert from 'node:assert/strict';
import test from 'node:test';
import {
  cellText,
  isDiffRow,
  jdStatusPresentation,
  queryTypeLabel,
  reconSummary,
  rowStatePresentation,
} from '../src/pages/fulfillment/outboundRecon.ts';
import type { OutboundReconView } from '../src/api/types.ts';

test('outbound recon JD status presentation distinguishes ok, no-record and unavailable', () => {
  const ok = jdStatusPresentation('OK');
  assert.equal(ok.tone, 'success');
  assert.match(ok.title, /已返回/);

  const notFound = jdStatusPresentation('NOT_FOUND');
  assert.equal(notFound.tone, 'warning');
  assert.match(notFound.title, /没有这笔出库记录/);

  // 失败/超时是「未取到」，不是空值：文案必须显式区分
  const unavailable = jdStatusPresentation('UNAVAILABLE', 'simulated timeout');
  assert.equal(unavailable.tone, 'error');
  assert.match(unavailable.title, /未取到/);
  assert.match(unavailable.description, /不是.*空值|这不是「字段为空」/);
  assert.match(unavailable.description, /simulated timeout/);
});

test('outbound recon row states map to distinct markers', () => {
  assert.deepEqual(rowStatePresentation('MATCH'), { tone: 'success', label: '一致' });
  assert.deepEqual(rowStatePresentation('MISMATCH'), { tone: 'error', label: '不一致' });
  assert.deepEqual(rowStatePresentation('INTERNAL_ONLY'), { tone: 'warning', label: '仅内部有' });
  assert.deepEqual(rowStatePresentation('JD_ONLY'), { tone: 'warning', label: '仅京东有' });
  assert.deepEqual(rowStatePresentation('EMPTY'), { tone: 'default', label: '两侧均为空' });
  assert.deepEqual(rowStatePresentation('JD_UNAVAILABLE'), { tone: 'error', label: '京东未取到' });
  assert.deepEqual(rowStatePresentation('JD_NOT_FOUND'), { tone: 'warning', label: '京东无记录' });
  // 未取到/无记录不能当作「一致」或「空值」
  assert.equal(isDiffRow('JD_UNAVAILABLE'), true);
  assert.equal(isDiffRow('JD_NOT_FOUND'), true);
  assert.equal(isDiffRow('MISMATCH'), true);
  assert.equal(isDiffRow('MATCH'), false);
  assert.equal(isDiffRow('EMPTY'), false);
});

test('outbound recon query type and cell text helpers', () => {
  assert.equal(queryTypeLabel('OUTBOUND_ORDER_NO'), '系统出库单号');
  assert.equal(queryTypeLabel('JD_DELIVERY_NO'), '京东单号');
  assert.equal(queryTypeLabel('ORDER_NO'), '订单号');
  assert.equal(cellText(null), '—');
  assert.equal(cellText(undefined), '—');
  assert.equal(cellText('  '), '—');
  assert.equal(cellText('202608130001'), '202608130001');
  assert.deepEqual(cellText([]), '—');
  assert.equal(cellText(['A', 'B']), 'A、B');
});

test('outbound recon summary aggregates comparison rows', () => {
  const view: OutboundReconView = {
    query: { type: 'OUTBOUND_ORDER_NO', value: '202608130001' },
    audit: { request_id: 'req-1', operator: 'ops' },
    internal: { summary: {}, items: [], tracking: null },
    jd: { status: 'OK', business_code: null, message: null, client_mode: 'MOCK', summary: null, items: [] },
    comparisons: [
      { key: 'a', label: 'A', internal_value: '1', jd_value: '1', internal_present: true, jd_present: true, state: 'MATCH', note: null },
      { key: 'b', label: 'B', internal_value: '1', jd_value: '2', internal_present: true, jd_present: true, state: 'MISMATCH', note: '不一致' },
      { key: 'c', label: 'C', internal_value: 'x', jd_value: null, internal_present: true, jd_present: false, state: 'INTERNAL_ONLY', note: '仅内部' },
      { key: 'd', label: 'D', internal_value: null, jd_value: 'y', internal_present: false, jd_present: true, state: 'JD_ONLY', note: '仅京东' },
    ],
    matched_count: 1,
    mismatch_count: 3,
  };
  const summary = reconSummary(view);
  assert.equal(summary.totalRows, 4);
  assert.equal(summary.matched, 1);
  assert.equal(summary.mismatched, 3);
  assert.equal(summary.internalOnly, 1);
  assert.equal(summary.jdOnly, 1);
  assert.equal(summary.jdStatus, 'OK');
});
