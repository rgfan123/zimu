import assert from 'node:assert/strict';
import test from 'node:test';
import {
  assembleAnalyticsData,
  buildFulfillmentFunnel,
  type AnalyticsWindowSnapshot,
} from '../src/pages/analytics/analyticsTransform.ts';
import { parseChannels, serializeChannels } from '../src/pages/analytics/analyticsFilters.ts';

function snapshot(day: string, orders: number): AnalyticsWindowSnapshot {
  return {
    days: [{
      date: day,
      label: day.slice(5),
      orders,
      qty: orders,
      lines: orders,
      exceptions: 0,
      oos: 0,
      syncFailed: 0,
      byChannel: {},
      status: { PENDING_OUTBOUND: orders, AWAIT_TRACKING: 0, PENDING_SYNC: 0, SYNC_FAILED: 0 },
    }],
    totals: { orders, qty: orders, lines: orders, exceptions: 0, oos: 0, syncFailed: 0 },
    byChannel: { WECOM: { orders, qty: orders, lines: orders, exceptions: 0, oos: 0, syncFailed: 0 } },
    byProduct: [{
      key: day,
      isProduct: true,
      label: day,
      category: '',
      channel: { WECOM: orders },
      sourceMappings: { WECOM: [] },
      jdSkuCodes: [],
      total: orders,
      skus: [],
    }],
    statusTotals: { PENDING_OUTBOUND: orders, AWAIT_TRACKING: 0, PENDING_SYNC: 0, SYNC_FAILED: 0 },
    funnel: [{ name: '履约创建', value: orders }],
  };
}

test('today cards use the aggregate window while charts retain the longer series', () => {
  const series = snapshot('2026-08-01', 14);
  const aggregate = snapshot('2026-08-12', 1);
  const previous = snapshot('2026-08-11', 2);

  const result = assembleAnalyticsData(series, aggregate, previous);

  assert.equal(result.seriesDays[0].orders, 14);
  assert.equal(result.aggDays[0].orders, 1);
  assert.equal(result.totals.orders, 1);
  assert.equal(result.byChannel.WECOM.orders, 1);
  assert.equal(result.byProduct[0].total, 1);
  assert.equal(result.statusTotals.PENDING_OUTBOUND, 1);
  assert.equal(result.funnel[0].value, 1);
  assert.equal(result.prev.totals.orders, 2);
});

test('tracking funnel retains the tracking stage and exposes the synced terminal stage', () => {
  const funnel = buildFulfillmentFunnel(
    [{
      provider_id: '1',
      provider_code: 'JD',
      shipment_count: 1,
      shipped_quantity: '1.000',
      average_tracking_hours: 2,
      fulfillment_count: 1,
      shipped_shipment_count: 1,
      tracking_received_count: 1,
      synced_count: 1,
      awaiting_sync_count: 0,
      sync_failed_count: 0,
    }],
    { PENDING_OUTBOUND: 0, AWAIT_TRACKING: 0, PENDING_SYNC: 0, SYNC_FAILED: 0 },
  );

  assert.equal(funnel.at(-2)?.name, '已取得运单');
  assert.equal(funnel.at(-2)?.value, 1);
  assert.equal(funnel.at(-1)?.name, '已回传');
  assert.equal(funnel.at(-1)?.value, 1);
});

test('channel selection round-trips through the public URL state', () => {
  assert.deepEqual(parseChannels('WECOM,FEIXIANG'), ['WECOM', 'FEIXIANG']);
  assert.deepEqual(parseChannels('UNKNOWN,WECOM,WECOM'), ['WECOM']);
  assert.deepEqual(parseChannels(null), ['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'DAZHE', 'WANQI', 'WECOM', 'MANUAL']);
  assert.equal(serializeChannels(['WECOM', 'CAISHIXIAN']), 'WECOM,CAISHIXIAN');
  assert.equal(serializeChannels([]), null);
});
