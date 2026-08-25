import assert from 'node:assert/strict';
import test from 'node:test';

import { presentAwaitingTracking } from '../src/pages/workbench/awaitingTracking.ts';

const PROVIDERS = new Map([['1', 'JD_WAREHOUSE'], ['2', 'THIRD_PARTY']]);

function shipment(overrides: Record<string, unknown> = {}) {
  return {
    id: '1',
    shipment_no: 'SHIP-A',
    shipment_status: 'CREATED',
    provider_id: '1',
    jd_outbound: {
      erp_delivery_no: '202608250001',
      sync_status: 'SUBMITTED',
      tracking_query_status: 'PENDING',
      tracking_query_attempt_count: 3,
      tracking_last_query_at: '2026-08-25T04:00:00Z',
    },
    ...overrides,
  };
}

test('京东已提交、运单未到 → 计入等回单', () => {
  const view = presentAwaitingTracking([shipment()], PROVIDERS);
  assert.equal(view.jd.length, 1);
  assert.equal(view.total, 1);
  assert.equal(view.jd[0].erpDeliveryNo, '202608250001');
  assert.equal(view.jd[0].attempts, 3);
});

test('运单已回填（SHIPPED）不再是「等」', () => {
  const view = presentAwaitingTracking([shipment({ shipment_status: 'SHIPPED' })], PROVIDERS);
  assert.equal(view.total, 0);
});

test('TRACKED / TERMINAL_REVIEWED 属终态，不计入', () => {
  for (const status of ['TRACKED', 'TERMINAL_REVIEWED']) {
    const view = presentAwaitingTracking(
      [shipment({ jd_outbound: { ...shipment().jd_outbound, tracking_query_status: status } })],
      PROVIDERS,
    );
    assert.equal(view.total, 0, `${status} 不应计入等回单`);
  }
});

test('京东履约方但尚未建单 → 不是「等回单」，是还没发出去', () => {
  // 这正是本次事故那张单的状态：混进来会让人以为已经发了
  const view = presentAwaitingTracking([shipment({ jd_outbound: null })], PROVIDERS);
  assert.equal(view.total, 0);
});

test('SUBMITTING 仍在飞行、SYNC_FAILED 属告警区，都不计入', () => {
  for (const sync of ['SUBMITTING', 'SYNC_FAILED']) {
    const view = presentAwaitingTracking(
      [shipment({ jd_outbound: { ...shipment().jd_outbound, sync_status: sync } })],
      PROVIDERS,
    );
    assert.equal(view.total, 0, `${sync} 不应计入等回单`);
  }
});

test('第三方按「等对方回传」单独成组，不与京东混同', () => {
  const view = presentAwaitingTracking(
    [shipment({ id: '9', shipment_no: 'SHIP-TP', provider_id: '2', jd_outbound: null })],
    PROVIDERS,
  );
  assert.equal(view.jd.length, 0);
  assert.equal(view.thirdParty.length, 1);
  assert.equal(view.thirdParty[0].shipmentNo, 'SHIP-TP');
  assert.equal(view.thirdParty[0].trackingStatus, null, '第三方没有京东运单查询态');
});

test('履约方类型未知时两组都不进——宁可少显示也不猜归属', () => {
  const view = presentAwaitingTracking([shipment({ provider_id: '99' })], PROVIDERS);
  assert.equal(view.total, 0);
});

test('畸形行丢弃，不崩整块', () => {
  const view = presentAwaitingTracking(
    [null, 'x', {}, { id: '1' }, shipment()],
    PROVIDERS,
  );
  assert.equal(view.total, 1);
});
