import assert from 'node:assert/strict';
import { test } from 'node:test';
import { presentSourceSync } from '../src/pages/fulfillment/sourceSyncWording.ts';
import type { SourceSyncCheck } from '../src/api/sourceSync.ts';

function check(overrides: Partial<SourceSyncCheck>): SourceSyncCheck {
  return {
    shipment_id: 29,
    ready: false,
    check_hash: 'a'.repeat(64),
    artifact_hash: null,
    internal: {
      shipment_id: 29,
      order_id: 34,
      source_channel: 'JUFUBAO',
      source_ref: 'm95',
      source_line_ref: 's95',
      receiver_name: null,
      receiver_phone: null,
      receiver_address: null,
      ordered_source_quantity: 1,
      shipped_source_quantity: 1,
      internal_shipped_quantity: 1,
      fulfillment_outcome: 'FULLY_FULFILLED',
      carrier_code: 'JD',
      carrier_name: '京东物流',
      carrier_output_value: '京东物流',
      tracking_number: 'JDVA1',
    },
    platform: {
      available: false,
      business_code: 'X',
      message: '',
      platform_state: null,
      acceptance_required: false,
      address_status: 'UNKNOWN',
      receiver_name: null,
      receiver_phone: null,
      receiver_address: null,
      sendable_quantity: null,
      carrier_mapped: false,
      effect_hash: null,
    },
    blockers: [],
    ...overrides,
  };
}

test('已经回传过是完成态，不能渲染成阻断', () => {
  // 2026-08-29 生产实测：界面把这条好消息显示成红色的「1 项阻断，回传按钮不可用」。
  const view = presentSourceSync(check({
    blockers: [{ code: 'SOURCE_SYNC_ALREADY_SYNCED', field: 'sync_status', message: '该 Shipment 已完成来源回传' }],
  }));

  assert.equal(view.tone, 'done');
  assert.equal(view.reasons.length, 0);
  assert.match(view.headline, /已经回传过/);
});

test('各渠道的平台读不到收敛成同一句人话', () => {
  for (const code of [
    'JUFUBAO_PLATFORM_CHECK_UNAVAILABLE',
    'CAISHIXIAN_PLATFORM_CHECK_UNAVAILABLE',
    'FEIXIANG_PLATFORM_CHECK_UNAVAILABLE',
  ]) {
    const view = presentSourceSync(check({
      blockers: [{ code, field: 'platform', message: '平台当前事实不可用，禁止执行回传' }],
    }));
    assert.equal(view.tone, 'blocked');
    assert.match(view.reasons[0].text, /查不到这一单/);
  }
});

test('平台承运商无法解析时说明平台代码问题，不把本地翻译误称为白名单', () => {
  const view = presentSourceSync(check({
    blockers: [{
      code: 'SOURCE_PLATFORM_CARRIER_UNMAPPED',
      field: 'carrier',
      message: '正式物流公司无法唯一解析为来源平台接口所需代码',
    }],
  }));

  assert.match(view.reasons[0].text, /平台/);
  assert.doesNotMatch(view.reasons[0].text, /白名单|先配好映射/);
});

test('翻译不到的码原样端出服务端原文，不编', () => {
  const view = presentSourceSync(check({
    blockers: [{ code: 'SOME_FUTURE_CODE', field: null, message: '服务端自己的说法' }],
  }));

  assert.equal(view.reasons[0].text, '服务端自己的说法');
  assert.equal(view.reasons[0].code, 'SOME_FUTURE_CODE');
});

test('ready 时给出可以发的语气', () => {
  const view = presentSourceSync(check({ ready: true }));

  assert.equal(view.tone, 'ready');
  assert.equal(view.reasons.length, 0);
});
