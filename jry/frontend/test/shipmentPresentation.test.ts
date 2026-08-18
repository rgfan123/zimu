import assert from 'node:assert/strict';
import test from 'node:test';
import { shipmentTimeLabel } from '../src/presentation/shipment.ts';

test('shipment pages distinguish an unknown actual shipment time from a real time', () => {
  assert.equal(shipmentTimeLabel(undefined), '未提供');
  assert.equal(shipmentTimeLabel(null), '未提供');
  assert.equal(shipmentTimeLabel('2026-08-12T04:00:00Z'), '2026-08-12 12:00');
});
