import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  manualTrackingIdempotencyKey,
  manualTrackingRequest,
  manualTrackingTone,
} from '../src/api/manualTracking.ts';

test('幂等键按发货批次固定，误点两次不会写两遍', () => {
  // 运单号一落就会推发货卡、并可能触发来源回传真写客户平台。
  // 掺随机数等于每次都是新请求，服务端的重放收敛就失效了。
  assert.equal(manualTrackingIdempotencyKey('18'), 'shipment-manual-tracking-18');
  assert.equal(manualTrackingIdempotencyKey('18'), manualTrackingIdempotencyKey('18'));
  assert.notEqual(manualTrackingIdempotencyKey('18'), manualTrackingIdempotencyKey('19'));
});

test('请求体是 snake_case，运单号去空格', () => {
  const { path, options } = manualTrackingRequest('18', '顺丰速运', '  SF5152783768751 ', {});

  assert.equal(path, '/api/v1/shipments/18/manual-tracking');
  assert.deepEqual(options.body, { carrier: '顺丰速运', tracking_number: 'SF5152783768751' });
});

test('快递公司留空时不发该字段——由服务端按前缀推断', () => {
  // 发空串会让服务端把「没填」当成「填了个认不出的公司」而报错。
  for (const blank of [undefined, '', '   ']) {
    const { options } = manualTrackingRequest('18', blank, 'SF123', {});
    assert.equal((options.body as Record<string, unknown>).carrier, undefined, String(blank));
  }
});

test('三种结果语气分开——重复提交和冲突都不算成功', () => {
  assert.equal(manualTrackingTone('ACCEPTED'), 'success');
  assert.equal(manualTrackingTone('REPLAYED'), 'info');
  assert.equal(manualTrackingTone('CONFLICT'), 'warning');
});
