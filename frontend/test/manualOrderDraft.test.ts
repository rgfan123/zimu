/**
 * 手工建单草稿接缝（src/pages/orders/manualOrderDraft.ts）的纯函数单测：
 * 编解码往返、坏数据宁丢不炸、注入式 Storage 的保存/读取/清除与配额异常兜底。
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  MANUAL_ORDER_DRAFT_KEY,
  clearManualOrderDraft,
  decodeManualOrderDraft,
  encodeManualOrderDraft,
  loadManualOrderDraft,
  saveManualOrderDraft,
} from '../src/pages/orders/manualOrderDraft.ts';
import type { ManualOrderFormValues } from '../src/api/manualOrderCreate.ts';

const SAMPLE: ManualOrderFormValues = {
  customer_code: 'C001',
  receiver: { name: '李四', phone: '13900000000', address: '河南省郑州市测试路2号' },
  // JSON 往返丢 undefined 字段：未填完的空行以 {} 形态归来，antd 表单可原样恢复。
  items: [{ sku_id: '76', quantity: '20' }, {}],
  remark: '走礼备注',
};

function memoryStorage(initial: Record<string, string> = {}) {
  const map = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => void map.set(key, value),
    removeItem: (key: string) => void map.delete(key),
    dump: () => Object.fromEntries(map),
  };
}

test('编解码往返：表单值与保存时间原样归来', () => {
  const encoded = encodeManualOrderDraft(SAMPLE, '2026-09-01T12:00:00.000Z');
  const decoded = decodeManualOrderDraft(encoded);
  assert.ok(decoded);
  assert.equal(decoded.saved_at, '2026-09-01T12:00:00.000Z');
  assert.deepEqual(decoded.values, SAMPLE);
});

test('坏数据宁丢不炸：null/坏 JSON/形状不符/values 非对象一律返回 null', () => {
  for (const bad of [
    null,
    '',
    '{broken',
    '"just a string"',
    '[]',
    JSON.stringify({ saved_at: '2026-09-01T12:00:00.000Z' }),
    JSON.stringify({ values: {} }),
    JSON.stringify({ saved_at: 123, values: {} }),
    JSON.stringify({ saved_at: '2026-09-01T12:00:00.000Z', values: 'nope' }),
    JSON.stringify({ saved_at: '2026-09-01T12:00:00.000Z', values: null }),
  ]) {
    assert.equal(decodeManualOrderDraft(bad), null, String(bad));
  }
});

test('保存→读取→清除全链路（注入式 Storage）', () => {
  const storage = memoryStorage();
  assert.equal(saveManualOrderDraft(storage, SAMPLE, '2026-09-01T12:00:00.000Z'), true);
  const loaded = loadManualOrderDraft(storage);
  assert.ok(loaded);
  assert.deepEqual(loaded.values, SAMPLE);
  clearManualOrderDraft(storage);
  assert.equal(loadManualOrderDraft(storage), null);
  assert.deepEqual(storage.dump(), {});
});

test('Storage 抛异常（配额/隐私模式）时保存返回 false、读取返回 null、清除不抛', () => {
  const throwing = {
    getItem: () => {
      throw new Error('SecurityError');
    },
    setItem: () => {
      throw new Error('QuotaExceededError');
    },
    removeItem: () => {
      throw new Error('SecurityError');
    },
  };
  assert.equal(saveManualOrderDraft(throwing, SAMPLE, '2026-09-01T12:00:00.000Z'), false);
  assert.equal(loadManualOrderDraft(throwing), null);
  assert.doesNotThrow(() => clearManualOrderDraft(throwing));
});

test('草稿键是稳定契约：换键等于丢草稿，必须显式版本化', () => {
  assert.equal(MANUAL_ORDER_DRAFT_KEY, 'zimu.manual-order-draft.v1');
  const storage = memoryStorage();
  saveManualOrderDraft(storage, SAMPLE, '2026-09-01T12:00:00.000Z');
  assert.ok(storage.dump()[MANUAL_ORDER_DRAFT_KEY]);
});
