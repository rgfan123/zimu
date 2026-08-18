import assert from 'node:assert/strict';
import test from 'node:test';
import {
  jdFulfillmentPresentation,
  orderShipmentPublicFields,
} from '../src/pages/orders/orderJdFulfillment.ts';
import type { OrderShipment } from '../src/api/types.ts';

function shipment(overrides: Partial<OrderShipment> = {}): OrderShipment {
  return {
    id: '501',
    shipment_no: 'SHIP-501',
    order_id: '101',
    provider_id: '11',
    outbound_order_no: '202608140001',
    shipment_sequence: 1,
    shipment_status: 'CREATED',
    items: [],
    tracking: null,
    jd_outbound: null,
    shipped_at: null,
    created_at: '2026-08-14T00:00:00Z',
    updated_at: '2026-08-14T01:00:00Z',
    ...overrides,
  };
}

test('JD Shipment presentation distinguishes not-created, syncing, failed, reviewed and returned states', () => {
  assert.equal(jdFulfillmentPresentation(shipment()).stateLabel, '未建单');
  assert.equal(jdFulfillmentPresentation(shipment({
    jd_outbound: {
      erp_delivery_no: '202608140001',
      jd_delivery_no: null,
      sync_status: 'SUBMITTING',
      failure_phase: null,
      tracking_query_status: 'PENDING',
      updated_at: '2026-08-14T01:00:00Z',
    },
  })).stateLabel, '同步中');
  assert.equal(jdFulfillmentPresentation(shipment({
    jd_outbound: {
      erp_delivery_no: '202608140001',
      jd_delivery_no: null,
      sync_status: 'SYNC_FAILED',
      failure_phase: 'SUBMIT',
      tracking_query_status: 'NOT_QUERIED',
      updated_at: '2026-08-14T01:00:00Z',
    },
  })).stateLabel, '失败');
  assert.deepEqual(jdFulfillmentPresentation(shipment({
    jd_outbound: {
      erp_delivery_no: '202608140001',
      jd_delivery_no: 'JD-501',
      sync_status: 'SUBMITTED',
      failure_phase: null,
      tracking_query_status: 'TERMINAL_REVIEWED',
      updated_at: '2026-08-14T01:00:00Z',
    },
  })), { state: 'REVIEWED', stateLabel: '人工终结', tone: 'warning' });
  assert.equal(jdFulfillmentPresentation(shipment({
    shipment_status: 'SHIPPED',
    jd_outbound: {
      erp_delivery_no: '202608140001',
      jd_delivery_no: 'JD-501',
      sync_status: 'SUBMITTED',
      failure_phase: null,
      tracking_query_status: 'TRACKED',
      updated_at: '2026-08-14T01:00:00Z',
    },
    tracking: {
      id: '901',
      logistics_company_code: 'SF',
      logistics_company_name: '顺丰',
      tracking_number: 'SF501',
      received_at: '2026-08-14T01:00:00Z',
    },
  })).stateLabel, '已回传');
});

test('order Shipment view model exposes only the documented operations whitelist', () => {
  const visible = orderShipmentPublicFields(shipment({
    jd_outbound: {
      erp_delivery_no: '202608140001',
      jd_delivery_no: 'JD-501',
      sync_status: 'SYNC_FAILED',
      failure_phase: 'SUBMIT',
      tracking_query_status: 'NOT_QUERIED',
      updated_at: '2026-08-14T01:00:00Z',
    },
  }), '京东云仓');

  assert.deepEqual(visible, {
    providerName: '京东云仓',
    erpDeliveryNo: '202608140001',
    jdDeliveryNo: 'JD-501',
    syncState: '失败',
    failurePhase: '提交建单',
    tracking: '—',
    updatedAt: '2026-08-14T01:00:00Z',
  });
  assert.deepEqual(Object.keys(visible).sort(), [
    'erpDeliveryNo', 'failurePhase', 'jdDeliveryNo', 'providerName',
    'syncState', 'tracking', 'updatedAt',
  ]);
});

test('order Shipment view uses the newest timestamp across shipment, tracking and JD facts', () => {
  const visible = orderShipmentPublicFields(shipment({
    updated_at: '2026-08-14T03:00:00Z',
    tracking: {
      id: '901',
      logistics_company_code: 'SF',
      logistics_company_name: '顺丰',
      tracking_number: 'SF501',
      received_at: '2026-08-14T02:00:00Z',
    },
    jd_outbound: {
      erp_delivery_no: '202608140001',
      jd_delivery_no: 'JD-501',
      sync_status: 'SUBMITTED',
      failure_phase: null,
      tracking_query_status: 'TRACKED',
      updated_at: '2026-08-14T01:00:00Z',
    },
  }), '京东云仓');

  assert.equal(visible.updatedAt, '2026-08-14T03:00:00Z');
});
